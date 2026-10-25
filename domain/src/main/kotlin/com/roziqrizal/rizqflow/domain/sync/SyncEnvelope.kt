package com.roziqrizal.rizqflow.domain.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64

/** Alasan sebuah amplop sinkron ditolak. Terketik supaya pemanggil bisa memilih [SyncIssueKind] yang tepat. */
enum class EnvelopeError {
    /** Bingkai lebih pendek dari yang dijanjikan panjangnya, atau pengisinya tidak lengkap. */
    TRUNCATED,

    /** Bingkai tidak mengikuti format (panjang nol, pengisi bukan nol, JSON rusak, bidang wajib hilang). */
    MALFORMED,

    /** Melebihi batas payload 64 KB. */
    TOO_LARGE,

    /** wireId di kolom luar tidak sama dengan hash type+id di dalam JSON terdekripsi. */
    WIRE_ID_MISMATCH,

    /** Salah satu bidang AAD memuat pemisah `|` atau kosong, sehingga AAD jadi ambigu. */
    INVALID_FIELD,

    /** Tipe rekaman di dalam amplop tidak dikenal [SyncRecordType] (mis. dari versi aplikasi lebih baru): lewati dan catat, jangan terapkan. */
    UNKNOWN_TYPE,

    /** Dekripsi AEAD gagal: kunci salah, ciphertext rusak, atau salah satu bidang AAD berubah. */
    DECRYPT_FAILED,
}

class SyncEnvelopeException(val reason: EnvelopeError, message: String) : Exception(message)

/** Isi amplop yang sudah dibuka dan diperiksa. [data] belum diartikan; itu tugas penangan tipe. */
data class DecodedRecord(
    val type: String,
    val id: String,
    val version: Int,
    val data: JsonObject,
)

/** Hasil [SyncEnvelope.seal]: kolom luar [wireId] dan ciphertext (sebelum base64). */
class SealedRecord(val wireId: String, val ciphertext: ByteArray)

/**
 * Amplop umum sinkron (sync-desain.md bagian C, USULAN Claude): satu format untuk semua tipe, JSON
 * UTF-8 netral bahasa supaya klien web kelak bisa membacanya.
 *
 * - Plaintext = bingkai `[2 byte panjang big-endian][JSON][nol pengisi sampai kelipatan 256 byte]`;
 *   pengisi menyamarkan ukuran. JSON = `{"v":1,"type":"...","id":"<id lokal>","d":{...}}`.
 * - Kolom luar (cleartext di server): `wireId` = base64url tanpa pengisi dari SHA-256
 *   `"rizqflow-wire|"+type+"|"+id`, 22 karakter pertama (menyembunyikan tipe dan awalan id
 *   deterministik, tetap menautkan versi rekaman yang sama), plus `changed_at_ms`, `deleted`, scope.
 * - AAD kanonis mengikat scope, wireId, cap waktu, deleted, dan perangkat:
 *   `rizqflow-sync/1|<scope>|<wireId>|<changedAtMs>|<deleted 0/1>|<deviceId>`. Memindahkan ciphertext
 *   ke cakupan lain, memutar ulang versi lama dengan cap baru, atau memalsukan tombstone membuat
 *   dekripsi gagal. Server tetap bisa membuang data (tak terhindarkan); ia tidak bisa memalsukannya.
 * - Penerima membuktikan bahwa wireId cocok dengan type+id di dalam JSON terdekripsi.
 */
object SyncEnvelope {
    const val AAD_PREFIX = "rizqflow-sync/1"
    const val WIRE_ID_LENGTH = 22
    const val PADDING_BLOCK = 256

    /** Batas ciphertext dan bingkai plaintext sebelum base64. */
    const val MAX_PAYLOAD_BYTES = 64 * 1024

    /** Cadangan untuk tambahan AEAD (awalan kunci Tink + IV + tag = 33 byte untuk AES-GCM). */
    private const val AEAD_RESERVE_BYTES = 64

    private const val WIRE_PREFIX = "rizqflow-wire|"

    private val json = Json { ignoreUnknownKeys = true }

    fun wireId(type: String, id: String): String {
        // Tipe tidak boleh memuat pemisah: ("a|b", "c") dan ("a", "b|c") akan menghasilkan teks hash yang sama. Id boleh memuatnya.
        requireField("type", type)
        if (id.isEmpty()) throw SyncEnvelopeException(EnvelopeError.INVALID_FIELD, "id kosong")
        val digest = MessageDigest.getInstance("SHA-256").digest("$WIRE_PREFIX$type|$id".toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, WIRE_ID_LENGTH)
    }

    /** AAD kanonis sebagai teks; sama persis di Kotlin, PHP, dan JS. */
    fun aadString(scope: String, wireId: String, changedAtMillis: Long, deleted: Boolean, deviceId: String): String {
        requireField("scope", scope)
        requireField("wireId", wireId)
        requireField("deviceId", deviceId)
        return "$AAD_PREFIX|$scope|$wireId|$changedAtMillis|${if (deleted) 1 else 0}|$deviceId"
    }

    fun aad(scope: String, wireId: String, changedAtMillis: Long, deleted: Boolean, deviceId: String): ByteArray =
        aadString(scope, wireId, changedAtMillis, deleted, deviceId).toByteArray(Charsets.UTF_8)

    /** Membungkus [jsonBytes] menjadi bingkai berpengisi. */
    fun frame(jsonBytes: ByteArray): ByteArray {
        if (jsonBytes.isEmpty()) throw SyncEnvelopeException(EnvelopeError.MALFORMED, "JSON kosong")
        val padded = ((2 + jsonBytes.size + PADDING_BLOCK - 1) / PADDING_BLOCK) * PADDING_BLOCK
        if (jsonBytes.size > 0xFFFF || padded > MAX_PAYLOAD_BYTES - AEAD_RESERVE_BYTES) {
            throw SyncEnvelopeException(EnvelopeError.TOO_LARGE, "Payload ${jsonBytes.size} byte melebihi batas")
        }
        val out = ByteArray(padded)
        out[0] = (jsonBytes.size ushr 8).toByte()
        out[1] = jsonBytes.size.toByte()
        jsonBytes.copyInto(out, 2)
        return out
    }

    /** Kebalikan [frame]; menolak bingkai terpotong, rusak, atau terlalu besar. */
    fun unframe(framed: ByteArray): ByteArray {
        if (framed.size > MAX_PAYLOAD_BYTES) throw SyncEnvelopeException(EnvelopeError.TOO_LARGE, "Bingkai melebihi batas")
        if (framed.size < 2) throw SyncEnvelopeException(EnvelopeError.TRUNCATED, "Bingkai tanpa panjang")
        val declared = ((framed[0].toInt() and 0xFF) shl 8) or (framed[1].toInt() and 0xFF)
        if (declared == 0) throw SyncEnvelopeException(EnvelopeError.MALFORMED, "Panjang JSON nol")
        if (2 + declared > framed.size) throw SyncEnvelopeException(EnvelopeError.TRUNCATED, "Bingkai terpotong")
        if (framed.size % PADDING_BLOCK != 0) throw SyncEnvelopeException(EnvelopeError.TRUNCATED, "Pengisi tidak lengkap")
        for (i in 2 + declared until framed.size) {
            if (framed[i].toInt() != 0) throw SyncEnvelopeException(EnvelopeError.MALFORMED, "Pengisi bukan nol")
        }
        return framed.copyOfRange(2, 2 + declared)
    }

    /** JSON plaintext amplop: `{"v":...,"type":...,"id":...,"d":{...}}`. */
    fun encodeJson(type: String, id: String, version: Int, data: JsonObject): ByteArray {
        require(version >= 1) { "Versi harus >= 1" }
        val obj = buildJsonObject {
            put("v", version)
            put("type", type)
            put("id", id)
            put("d", data)
        }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    /** Membungkus, memberi pengisi, lalu mengenkripsi dengan AAD kanonis. */
    fun seal(
        aead: SyncAead,
        scope: SyncScope,
        type: String,
        id: String,
        version: Int,
        data: JsonObject,
        changedAtMillis: Long,
        deleted: Boolean,
        deviceId: String,
    ): SealedRecord {
        val wireId = wireId(type, id)
        val aad = aad(scope.wire, wireId, changedAtMillis, deleted, deviceId)
        val plaintext = frame(encodeJson(type, id, version, data))
        val ciphertext = aead.encrypt(plaintext, aad)
        if (ciphertext.size > MAX_PAYLOAD_BYTES) throw SyncEnvelopeException(EnvelopeError.TOO_LARGE, "Ciphertext melebihi batas")
        return SealedRecord(wireId, ciphertext)
    }

    /**
     * Membuka satu baris dari server. Semua kolom luar ([scope], [wireId], [changedAtMillis],
     * [deleted], [deviceId]) berasal dari server dan masuk AAD, jadi selisih apa pun menggagalkan
     * dekripsi. Melempar [SyncEnvelopeException] terketik; tidak pernah mengembalikan separuh isi.
     */
    fun open(
        aead: SyncAead,
        scope: SyncScope,
        wireId: String,
        changedAtMillis: Long,
        deleted: Boolean,
        deviceId: String,
        ciphertext: ByteArray,
    ): DecodedRecord {
        if (ciphertext.size > MAX_PAYLOAD_BYTES) throw SyncEnvelopeException(EnvelopeError.TOO_LARGE, "Ciphertext melebihi batas")
        val aad = aad(scope.wire, wireId, changedAtMillis, deleted, deviceId)
        val plaintext = try {
            aead.decrypt(ciphertext, aad)
        } catch (e: Exception) {
            throw SyncEnvelopeException(EnvelopeError.DECRYPT_FAILED, "Dekripsi gagal (${e.javaClass.simpleName})")
        }
        val decoded = decodeJson(unframe(plaintext))
        if (SyncRecordType.fromWire(decoded.type) == null) {
            throw SyncEnvelopeException(EnvelopeError.UNKNOWN_TYPE, "Tipe rekaman tidak dikenal")
        }
        if (wireId(decoded.type, decoded.id) != wireId) {
            throw SyncEnvelopeException(EnvelopeError.WIRE_ID_MISMATCH, "wireId tidak cocok dengan type+id di dalam amplop")
        }
        return decoded
    }

    internal fun decodeJson(jsonBytes: ByteArray): DecodedRecord {
        val element = try {
            json.parseToJsonElement(String(jsonBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SyncEnvelopeException(EnvelopeError.MALFORMED, "JSON amplop rusak")
        }
        val obj = element as? JsonObject ?: throw SyncEnvelopeException(EnvelopeError.MALFORMED, "Amplop bukan objek JSON")
        // "v" harus bilangan JSON, bukan teks "1".
        val version = (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val data = obj["d"] as? JsonObject
        if (version == null || version < 1 || type.isNullOrEmpty() || id.isNullOrEmpty() || data == null) {
            throw SyncEnvelopeException(EnvelopeError.MALFORMED, "Bidang wajib amplop tidak lengkap")
        }
        return DecodedRecord(type, id, version, data)
    }

    private fun requireField(name: String, value: String) {
        if (value.isEmpty() || '|' in value) throw SyncEnvelopeException(EnvelopeError.INVALID_FIELD, "Bidang AAD $name tidak sah")
    }
}
