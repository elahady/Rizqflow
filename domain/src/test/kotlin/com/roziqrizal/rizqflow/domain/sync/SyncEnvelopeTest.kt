package com.roziqrizal.rizqflow.domain.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** AEAD AES-256-GCM JDK sungguhan (bukan identitas) supaya uji AAD bermakna. Format: iv(12) || ciphertext || tag. */
internal class JdkAesGcmAead(private val key: ByteArray = ByteArray(32) { it.toByte() }) : SyncAead {
    private val random = SecureRandom()

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData)
        return iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        if (ciphertext.size < 12 + 16) throw GeneralSecurityException("Ciphertext terlalu pendek")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
    }
}

class SyncEnvelopeTest {
    private val golden: JsonObject = checkNotNull(javaClass.getResourceAsStream("/sync/golden/envelope.v1.json")) { "Berkas golden tidak ada" }
        .use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun sampleData() = buildJsonObject {
        put("amount", 25_000)
        put("note", "kopi")
    }

    private fun assertRejected(reason: EnvelopeError, block: () -> Unit) {
        val e = assertFailsWith<SyncEnvelopeException> { block() }
        assertEquals(reason, e.reason)
    }

    // ---- golden: wireId dan AAD

    @Test
    fun `wireId cocok dengan semua vektor emas yang dihitung terpisah dengan JDK`() {
        for (v in golden["wireIdVectors"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(
                v["wireId"]!!.jsonPrimitive.content,
                SyncEnvelope.wireId(v["type"]!!.jsonPrimitive.content, v["id"]!!.jsonPrimitive.content),
                "wireId ${v["type"]} ${v["id"]}",
            )
        }
    }

    @Test
    fun `wireId panjangnya 22 karakter base64url tanpa pengisi dan deterministik`() {
        val w = SyncEnvelope.wireId("transaction", "tx-1")
        assertEquals(22, w.length)
        assertTrue(w.all { it.isLetterOrDigit() || it == '-' || it == '_' }, w)
        assertEquals(w, SyncEnvelope.wireId("transaction", "tx-1"))
        assertNotEquals(w, SyncEnvelope.wireId("debt", "tx-1"), "tipe ikut di hash")
        assertNotEquals(w, SyncEnvelope.wireId("transaction", "tx-2"), "id ikut di hash")
    }

    @Test
    fun `AAD cocok persis dengan semua vektor emas`() {
        for (v in golden["aadVectors"]!!.jsonArray.map { it.jsonObject }) {
            val wireId = SyncEnvelope.wireId(v["type"]!!.jsonPrimitive.content, v["id"]!!.jsonPrimitive.content)
            val aad = SyncEnvelope.aadString(
                scope = v["scope"]!!.jsonPrimitive.content,
                wireId = wireId,
                changedAtMillis = v["changedAtMs"]!!.jsonPrimitive.long,
                deleted = v["deleted"]!!.jsonPrimitive.boolean,
                deviceId = v["deviceId"]!!.jsonPrimitive.content,
            )
            assertEquals(v["aad"]!!.jsonPrimitive.content, aad)
        }
    }

    @Test
    fun `AAD berbentuk persis rizqflow-sync-1 dengan enam bidang`() {
        assertEquals(
            "rizqflow-sync/1|a|ABC|123|1|dev",
            SyncEnvelope.aadString("a", "ABC", 123L, true, "dev"),
        )
        assertContentEquals(
            "rizqflow-sync/1|r:r1|ABC|-5|0|dev".toByteArray(Charsets.UTF_8),
            SyncEnvelope.aad("r:r1", "ABC", -5L, false, "dev"),
        )
    }

    @Test
    fun `bidang AAD yang memuat pemisah atau kosong ditolak karena ambigu`() {
        assertRejected(EnvelopeError.INVALID_FIELD) { SyncEnvelope.aadString("a|b", "w", 1, false, "d") }
        assertRejected(EnvelopeError.INVALID_FIELD) { SyncEnvelope.aadString("a", "w|x", 1, false, "d") }
        assertRejected(EnvelopeError.INVALID_FIELD) { SyncEnvelope.aadString("a", "w", 1, false, "d|e") }
        assertRejected(EnvelopeError.INVALID_FIELD) { SyncEnvelope.aadString("a", "w", 1, false, "") }
        assertRejected(EnvelopeError.INVALID_FIELD) { SyncEnvelope.aadString("", "w", 1, false, "d") }
    }

    // ---- golden: enkripsi-dekripsi AES-GCM JDK

    private fun gcmDecrypt(key: ByteArray, iv: ByteArray, ct: ByteArray, aad: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ct)
    }

    @Test
    fun `vektor AES-GCM emas terdekripsi menjadi bingkai yang berisi JSON amplop`() {
        val v = golden["encryptionVector"]!!.jsonObject
        val key = hex(v["keyHex"]!!.jsonPrimitive.content)
        val iv = hex(v["ivHex"]!!.jsonPrimitive.content)
        val ct = hex(v["ciphertextHex"]!!.jsonPrimitive.content)
        val aad = SyncEnvelope.aadString(
            v["scope"]!!.jsonPrimitive.content,
            SyncEnvelope.wireId(v["type"]!!.jsonPrimitive.content, v["id"]!!.jsonPrimitive.content),
            v["changedAtMs"]!!.jsonPrimitive.long,
            v["deleted"]!!.jsonPrimitive.boolean,
            v["deviceId"]!!.jsonPrimitive.content,
        )
        assertEquals(v["aad"]!!.jsonPrimitive.content, aad)
        assertEquals(v["wireId"]!!.jsonPrimitive.content, SyncEnvelope.wireId("transaction", "tx-1"))

        val framed = gcmDecrypt(key, iv, ct, aad)
        assertEquals(v["framedLength"]!!.jsonPrimitive.long.toInt(), framed.size)
        assertEquals(v["plaintextJson"]!!.jsonPrimitive.content, SyncEnvelope.unframe(framed).toString(Charsets.UTF_8))

        // Kode kita menghasilkan bingkai yang sama persis dengan yang dienkripsi vektor emas.
        val ours = SyncEnvelope.frame(SyncEnvelope.encodeJson("transaction", "tx-1", 1, sampleData()))
        assertContentEquals(framed, ours)
    }

    @Test
    fun `mengubah satu bidang AAD saja membuat dekripsi vektor emas gagal`() {
        val v = golden["encryptionVector"]!!.jsonObject
        val key = hex(v["keyHex"]!!.jsonPrimitive.content)
        val iv = hex(v["ivHex"]!!.jsonPrimitive.content)
        val ct = hex(v["ciphertextHex"]!!.jsonPrimitive.content)
        val wireId = v["wireId"]!!.jsonPrimitive.content
        val ms = v["changedAtMs"]!!.jsonPrimitive.long

        // Baseline: AAD asli terbuka.
        gcmDecrypt(key, iv, ct, SyncEnvelope.aadString("a", wireId, ms, false, "device-0001"))

        val tampered = mapOf(
            "scope" to SyncEnvelope.aadString("r:room-lain", wireId, ms, false, "device-0001"),
            "wireId" to SyncEnvelope.aadString("a", SyncEnvelope.wireId("transaction", "tx-2"), ms, false, "device-0001"),
            "changedAt" to SyncEnvelope.aadString("a", wireId, ms + 1, false, "device-0001"),
            "deleted" to SyncEnvelope.aadString("a", wireId, ms, true, "device-0001"),
            "deviceId" to SyncEnvelope.aadString("a", wireId, ms, false, "device-0002"),
        )
        for ((field, aad) in tampered) {
            assertFailsWith<AEADBadTagException>("bidang $field berubah harus gagal") { gcmDecrypt(key, iv, ct, aad) }
        }
    }

    // ---- bingkai

    @Test
    fun `bingkai berpengisi kelipatan 256 dan round trip`() {
        for (len in listOf(1, 10, 253, 254, 255, 256, 509, 510, 1000, 5000)) {
            val json = ByteArray(len) { ('a'.code + it % 26).toByte() }
            val framed = SyncEnvelope.frame(json)
            assertEquals(0, framed.size % 256, "len=$len")
            assertEquals(((2 + len + 255) / 256) * 256, framed.size, "len=$len")
            assertEquals(len, ((framed[0].toInt() and 0xFF) shl 8) or (framed[1].toInt() and 0xFF))
            assertTrue((2 + len until framed.size).all { framed[it].toInt() == 0 }, "pengisi harus nol")
            assertContentEquals(json, SyncEnvelope.unframe(framed), "len=$len")
        }
    }

    @Test
    fun `bingkai ukuran berbeda yang masih satu blok tampak sama panjang supaya ukuran tersamar`() {
        assertEquals(SyncEnvelope.frame(ByteArray(20) { 65 }).size, SyncEnvelope.frame(ByteArray(200) { 65 }).size)
    }

    @Test
    fun `bingkai terpotong ditolak sebagai TRUNCATED`() {
        val framed = SyncEnvelope.frame("{\"v\":1}".repeat(100).toByteArray())
        assertRejected(EnvelopeError.TRUNCATED) { SyncEnvelope.unframe(ByteArray(0)) }
        assertRejected(EnvelopeError.TRUNCATED) { SyncEnvelope.unframe(byteArrayOf(0)) }
        assertRejected(EnvelopeError.TRUNCATED) { SyncEnvelope.unframe(framed.copyOf(100)) } // panjang JSON dijanjikan 700
        assertRejected(EnvelopeError.TRUNCATED) { SyncEnvelope.unframe(framed.copyOf(framed.size - 1)) } // pengisi tidak lengkap
    }

    @Test
    fun `bingkai rusak ditolak sebagai MALFORMED`() {
        val ok = SyncEnvelope.frame("{\"a\":1}".toByteArray())
        assertRejected(EnvelopeError.MALFORMED) { SyncEnvelope.unframe(ByteArray(256)) } // panjang nol
        val dirtyPadding = ok.copyOf().also { it[255] = 1 }
        assertRejected(EnvelopeError.MALFORMED) { SyncEnvelope.unframe(dirtyPadding) }
        assertRejected(EnvelopeError.MALFORMED) { SyncEnvelope.frame(ByteArray(0)) }
    }

    @Test
    fun `bingkai terlalu besar ditolak baik saat membungkus maupun membuka`() {
        // Batas payload 64 KB sebelum base64.
        assertRejected(EnvelopeError.TOO_LARGE) { SyncEnvelope.frame(ByteArray(SyncEnvelope.MAX_PAYLOAD_BYTES)) }
        assertRejected(EnvelopeError.TOO_LARGE) { SyncEnvelope.frame(ByteArray(70_000)) }
        assertRejected(EnvelopeError.TOO_LARGE) { SyncEnvelope.frame(ByteArray(65_279)) } // satu byte di atas blok terakhir yang muat
        assertRejected(EnvelopeError.TOO_LARGE) { SyncEnvelope.unframe(ByteArray(SyncEnvelope.MAX_PAYLOAD_BYTES + 256)) }
        // Yang tepat di bawah batas (blok penuh terakhir yang muat bersama cadangan AEAD) masih lolos.
        val biggest = SyncEnvelope.frame(ByteArray(65_270) { 'x'.code.toByte() })
        assertTrue(biggest.size <= SyncEnvelope.MAX_PAYLOAD_BYTES)
        assertEquals(65_270, SyncEnvelope.unframe(biggest).size)
    }

    // ---- seal / open

    @Test
    fun `seal lalu open mengembalikan type id versi dan data yang sama`() {
        val aead = JdkAesGcmAead()
        val sealed = SyncEnvelope.seal(aead, SyncScope.Personal, "transaction", "tx-9", 1, sampleData(), 1_000L, false, "dev-a")
        assertEquals(SyncEnvelope.wireId("transaction", "tx-9"), sealed.wireId)
        val decoded = SyncEnvelope.open(aead, SyncScope.Personal, sealed.wireId, 1_000L, false, "dev-a", sealed.ciphertext)
        assertEquals("transaction", decoded.type)
        assertEquals("tx-9", decoded.id)
        assertEquals(1, decoded.version)
        assertEquals(sampleData(), decoded.data)
    }

    @Test
    fun `tombstone dibungkus dengan data kosong dan tetap terbuka`() {
        val aead = JdkAesGcmAead()
        val sealed = SyncEnvelope.seal(aead, SyncScope.Personal, "debt", "d1", 1, JsonObject(emptyMap()), 5L, true, "dev-a")
        val decoded = SyncEnvelope.open(aead, SyncScope.Personal, sealed.wireId, 5L, true, "dev-a", sealed.ciphertext)
        assertEquals(JsonObject(emptyMap()), decoded.data)
    }

    @Test
    fun `memindahkan ciphertext ke cakupan lain atau mengubah kolom luar membuat open gagal DECRYPT_FAILED`() {
        val aead = JdkAesGcmAead()
        val scope = SyncScope.SharedRoom(com.roziqrizal.rizqflow.domain.model.RoomId("room-1"))
        val sealed = SyncEnvelope.seal(aead, scope, "transaction", "tx-1", 1, sampleData(), 1_000L, false, "dev-a")
        val w = sealed.wireId
        val c = sealed.ciphertext
        SyncEnvelope.open(aead, scope, w, 1_000L, false, "dev-a", c) // baseline sah

        assertRejected(EnvelopeError.DECRYPT_FAILED) { SyncEnvelope.open(aead, SyncScope.Personal, w, 1_000L, false, "dev-a", c) }
        assertRejected(EnvelopeError.DECRYPT_FAILED) { SyncEnvelope.open(aead, scope, SyncEnvelope.wireId("transaction", "tx-2"), 1_000L, false, "dev-a", c) }
        assertRejected(EnvelopeError.DECRYPT_FAILED) { SyncEnvelope.open(aead, scope, w, 1_001L, false, "dev-a", c) } // memutar ulang dengan cap baru
        assertRejected(EnvelopeError.DECRYPT_FAILED) { SyncEnvelope.open(aead, scope, w, 1_000L, true, "dev-a", c) } // memalsukan tombstone
        assertRejected(EnvelopeError.DECRYPT_FAILED) { SyncEnvelope.open(aead, scope, w, 1_000L, false, "dev-b", c) }
        assertRejected(EnvelopeError.DECRYPT_FAILED) { SyncEnvelope.open(aead, scope, w, 1_000L, false, "dev-a", c.copyOf().also { it[20] = (it[20] + 1).toByte() }) }
    }

    @Test
    fun `ciphertext lebih dari 64 KB ditolak sebelum didekripsi`() {
        assertRejected(EnvelopeError.TOO_LARGE) {
            SyncEnvelope.open(JdkAesGcmAead(), SyncScope.Personal, "w", 1L, false, "d", ByteArray(SyncEnvelope.MAX_PAYLOAD_BYTES + 1))
        }
    }

    @Test
    fun `payload terlalu besar ditolak saat seal`() {
        val huge = buildJsonObject { put("blob", "x".repeat(70_000)) }
        assertRejected(EnvelopeError.TOO_LARGE) { SyncEnvelope.seal(JdkAesGcmAead(), SyncScope.Personal, "transaction", "t", 1, huge, 1L, false, "d") }
    }

    /** Aead yang mengabaikan AAD, seperti FakeSyncAead lama: dipakai untuk menguji pemeriksaan wireId yang berdiri sendiri. */
    private class NoAadAead : SyncAead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = ciphertext
    }

    @Test
    fun `wireId yang tidak cocok dengan type dan id di dalam JSON ditolak walau dekripsi lolos`() {
        val aead = NoAadAead()
        val plaintext = SyncEnvelope.frame(SyncEnvelope.encodeJson("transaction", "tx-1", 1, sampleData()))
        // Kolom luar mengaku id lain, padahal isinya tx-1: penerima wajib menolak.
        assertRejected(EnvelopeError.WIRE_ID_MISMATCH) {
            SyncEnvelope.open(aead, SyncScope.Personal, SyncEnvelope.wireId("transaction", "tx-LAIN"), 1L, false, "d", plaintext)
        }
        assertRejected(EnvelopeError.WIRE_ID_MISMATCH) {
            SyncEnvelope.open(aead, SyncScope.Personal, SyncEnvelope.wireId("debt", "tx-1"), 1L, false, "d", plaintext)
        }
        // Dan yang cocok lolos.
        val ok = SyncEnvelope.open(aead, SyncScope.Personal, SyncEnvelope.wireId("transaction", "tx-1"), 1L, false, "d", plaintext)
        assertEquals("tx-1", ok.id)
    }

    @Test
    fun `JSON amplop yang rusak atau kehilangan bidang wajib ditolak MALFORMED`() {
        val aead = NoAadAead()
        fun open(json: String) = SyncEnvelope.open(aead, SyncScope.Personal, "w", 1L, false, "d", SyncEnvelope.frame(json.toByteArray()))
        assertRejected(EnvelopeError.MALFORMED) { open("bukan json") }
        assertRejected(EnvelopeError.MALFORMED) { open("[1,2]") }
        assertRejected(EnvelopeError.MALFORMED) { open("""{"v":1,"type":"t","id":"i"}""") } // tanpa d
        assertRejected(EnvelopeError.MALFORMED) { open("""{"v":1,"type":"t","d":{}}""") } // tanpa id
        assertRejected(EnvelopeError.MALFORMED) { open("""{"type":"t","id":"i","d":{}}""") } // tanpa v
        assertRejected(EnvelopeError.MALFORMED) { open("""{"v":0,"type":"t","id":"i","d":{}}""") }
        assertRejected(EnvelopeError.MALFORMED) { open("""{"v":1,"type":5,"id":"i","d":{}}""") } // type bukan teks
        assertRejected(EnvelopeError.MALFORMED) { open("""{"v":1,"type":"t","id":"i","d":[]}""") }
    }

    @Test
    fun `bidang tak dikenal di amplop diabaikan dan versi lebih tinggi tetap terbaca untuk diputuskan pemanggil`() {
        val aead = NoAadAead()
        val json = """{"v":7,"type":"transaction","id":"tx-1","d":{"x":1},"future":true}"""
        val decoded = SyncEnvelope.open(
            aead, SyncScope.Personal, SyncEnvelope.wireId("transaction", "tx-1"), 1L, false, "d",
            SyncEnvelope.frame(json.toByteArray()),
        )
        assertEquals(7, decoded.version)
        assertEquals(JsonPrimitive(1), decoded.data["x"])
    }

    @Test
    fun `id unicode ikut di wireId dan bingkai UTF-8 round trip`() {
        val aead = JdkAesGcmAead()
        val id = "kopi pagi é世"
        val data = buildJsonObject { put("note", "世界 é") }
        val sealed = SyncEnvelope.seal(aead, SyncScope.Personal, "quick_favorite", id, 1, data, 42L, false, "device-0001")
        val decoded = SyncEnvelope.open(aead, SyncScope.Personal, sealed.wireId, 42L, false, "device-0001", sealed.ciphertext)
        assertEquals(id, decoded.id)
        assertEquals(data, decoded.data)
    }
    @Test
    fun `versi bertipe teks ditolak MALFORMED walau isinya angka`() {
        val aead = NoAadAead()
        fun open(json: String) = SyncEnvelope.open(
            aead, SyncScope.Personal, SyncEnvelope.wireId("transaction", "tx-1"), 1L, false, "d", SyncEnvelope.frame(json.toByteArray()),
        )
        assertRejected(EnvelopeError.MALFORMED) { open("""{"v":"1","type":"transaction","id":"tx-1","d":{}}""") }
        assertRejected(EnvelopeError.MALFORMED) { open("""{"v":1.5,"type":"transaction","id":"tx-1","d":{}}""") }
        assertEquals(1, open("""{"v":1,"type":"transaction","id":"tx-1","d":{}}""").version)
    }

    @Test
    fun `tipe yang tidak dikenal ditolak UNKNOWN_TYPE walau wireId dan JSON-nya cocok`() {
        val aead = NoAadAead()
        val plaintext = SyncEnvelope.frame(SyncEnvelope.encodeJson("tipe_masa_depan", "x-1", 1, sampleData()))
        assertRejected(EnvelopeError.UNKNOWN_TYPE) {
            SyncEnvelope.open(aead, SyncScope.Personal, SyncEnvelope.wireId("tipe_masa_depan", "x-1"), 1L, false, "d", plaintext)
        }
    }

    @Test
    fun `tipe yang memuat pemisah ditolak karena wireId-nya ambigu tetapi id boleh memuatnya`() {
        assertRejected(EnvelopeError.INVALID_FIELD) { SyncEnvelope.wireId("a|b", "c") }
        assertRejected(EnvelopeError.INVALID_FIELD) { SyncEnvelope.wireId("", "c") }
        assertRejected(EnvelopeError.INVALID_FIELD) { SyncEnvelope.wireId("transaction", "") }
        assertRejected(EnvelopeError.INVALID_FIELD) {
            SyncEnvelope.seal(JdkAesGcmAead(), SyncScope.Personal, "a|b", "c", 1, sampleData(), 1L, false, "d")
        }
        // Id dengan pemisah tetap sah dan tidak bertabrakan dengan tipe lain karena tipe tak pernah memuat pemisah.
        assertEquals(22, SyncEnvelope.wireId("transaction", "id|dengan|pipa").length)
        assertNotEquals(SyncEnvelope.wireId("transaction", "b|c"), SyncEnvelope.wireId("debt", "b|c"))
    }
}
