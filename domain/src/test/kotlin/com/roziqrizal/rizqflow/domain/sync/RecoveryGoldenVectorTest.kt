package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Golden test kode pemulihan + bungkusan kunci akun + RecoveryBundle, dibaca dari
 * `/sync/golden/recovery.v2.json`. Vektor itu adalah data uji deterministik (bukan rahasia): entropi,
 * garam, dan IV ditetapkan tangan, dan keluarannya dicetak sekali dari kode produksi lalu disimpan.
 * Tes ini memastikan kode produksi masih menghasilkan byte yang sama persis, sehingga klien web
 * (Tahap 14) punya acuan tetap untuk interoperabilitas. Lihat `docs/sync-desain.md` bagian K.
 *
 * Kunci acaknya: [AccountKeyRecoveryCrypto.wrap] dan [RecoveryCode.generate] menerima SecureRandom
 * lewat parameter, jadi [FixedRandom] memberi urutan byte tetap. Tidak ada mock dan tidak ada
 * perubahan kode produksi.
 */
class RecoveryGoldenVectorTest {

    /** Urutan byte tetap: setiap [nextBytes] mengambil byte berikutnya dari [stream]. */
    private class FixedRandom(private val stream: ByteArray) : SecureRandom() {
        private var pos = 0
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = stream[pos++]
        }
    }

    private val golden: JsonObject = checkNotNull(javaClass.getResourceAsStream("/sync/golden/recovery.v2.json")) {
        "Berkas golden recovery.v2.json tidak ada"
    }.use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private val phrase = golden.obj("phrase")
    private val entropy = hex(phrase.str("entropyHex"))
    private val words: List<String> = phrase.getValue("words").jsonArray.map { it.jsonPrimitive.content }

    private val bundleInput = golden.obj("bundleInput")
    private val accountKey = hex(bundleInput.str("accountKeyHex"))
    private val roomKeys: List<RecoveryBundle.RoomKeyEntry> =
        bundleInput.getValue("roomKeys").jsonArray.map { element ->
            val room = element.jsonObject
            RecoveryBundle.RoomKeyEntry(RoomId(room.str("roomId")), hex(room.str("exportedRoomKeyHex")))
        }

    private val injected = golden.obj("injectedRandom")
    private val wrapRandom = hex(injected.str("wrapSaltHex")) + hex(injected.str("wrapIvHex"))
    private val legacyRandom = hex(injected.str("legacySaltHex")) + hex(injected.str("legacyIvHex"))

    private val bundleSection = golden.obj("bundle")
    private val plainHex = bundleSection.str("plaintextHex")

    private val wrappedHex = golden.obj("wrapped").str("wrappedHex")
    private val legacyWrappedHex = golden.obj("legacyVersion1").str("legacyWrappedHex")

    /** Kode dari vektor, dibangun dari entropi tetap lewat jalur produksi [RecoveryCode.generate]. */
    private fun goldenCode(): RecoveryCode = RecoveryCode.generate(FixedRandom(entropy))

    @Test
    fun `entropi tetap menghasilkan 24 kata yang sama persis dengan vektor`() {
        val code = goldenCode()

        assertEquals(words, code.words)
        assertEquals(phrase.str("phraseSpaced"), code.words.joinToString(" "))
    }

    @Test
    fun `24 kata vektor lolos parse dengan entropi yang sama`() {
        val parsed = RecoveryCode.parse(words)

        assertContentEquals(entropy, parsed.entropy)
        assertEquals(24, phrase.getValue("words").jsonArray.size)
        assertEquals(RecoveryCode.WORD_COUNT, parsed.words.size)
    }

    @Test
    fun `encode RecoveryBundle menghasilkan byte plaintext PERSIS vektor`() {
        val bundle = RecoveryBundle(accountKey, roomKeys)

        assertContentEquals(hex(plainHex), bundle.encode())
    }

    @Test
    fun `decode plaintext vektor menghasilkan kunci akun dan semua kunci ruang yang benar`() {
        val decoded = RecoveryBundle.decode(hex(plainHex))

        assertContentEquals(accountKey, decoded.accountKey)
        assertEquals(roomKeys, decoded.roomKeys)
    }

    @Test
    fun `CRC32 di akhir plaintext vektor cocok dengan CRC32 atas seluruh payload sebelumnya`() {
        val plain = hex(plainHex)
        val payload = plain.copyOfRange(0, plain.size - 4)
        val crc = CRC32().apply { update(payload) }.value.toInt()
        val storedCrc = plain.copyOfRange(plain.size - 4, plain.size).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

        assertEquals(bundleSection.str("crc32Hex"), "%08x".format(crc))
        assertEquals(crc, storedCrc)
    }

    @Test
    fun `wrap dengan garam dan IV tetap menghasilkan bungkusan PERSIS vektor`() {
        val wrapped = AccountKeyRecoveryCrypto.wrap(hex(plainHex), goldenCode(), FixedRandom(wrapRandom))

        assertEquals(wrappedHex, wrapped.toHex())
    }

    @Test
    fun `unwrap bungkusan vektor dengan kode pemulihan vektor mengembalikan plaintext bundle asli`() {
        val plain = AccountKeyRecoveryCrypto.unwrap(hex(wrappedHex), goldenCode())

        assertContentEquals(hex(plainHex), plain)
        assertEquals(RecoveryBundle(accountKey, roomKeys), RecoveryBundle.decode(plain))
    }

    @Test
    fun `format version 1 lama (kunci akun mentah) tetap terbuka dengan kode vektor`() {
        val legacyCode = goldenCode()

        assertContentEquals(accountKey, AccountKeyRecoveryCrypto.unwrap(hex(legacyWrappedHex), legacyCode))
        assertEquals(
            legacyWrappedHex,
            AccountKeyRecoveryCrypto.wrap(accountKey, legacyCode, FixedRandom(legacyRandom)).toHex(),
        )
    }

    @Test
    fun `kode pemulihan lain tidak bisa membuka bungkusan vektor`() {
        val kodeLain = RecoveryCode.generate(FixedRandom(ByteArray(32) { 0xff.toByte() }))

        assertFailsWith<RecoveryCodeInvalid> { AccountKeyRecoveryCrypto.unwrap(hex(wrappedHex), kodeLain) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
