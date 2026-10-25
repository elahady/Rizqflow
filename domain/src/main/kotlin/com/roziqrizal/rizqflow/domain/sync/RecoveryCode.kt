package com.roziqrizal.rizqflow.domain.sync

import java.security.MessageDigest
import java.security.SecureRandom

/** Alasan sebuah kode pemulihan ditolak, supaya UI memilih pesan terjemahan yang tepat tanpa mengurai [InvalidRecoveryCode.message]. */
enum class InvalidRecoveryCodeKind {
    /** Jumlah kata bukan 24. */
    WRONG_COUNT,

    /** Ada kata yang tidak ada di daftar baku (salah ketik). */
    UNKNOWN_WORD,

    /** Semua kata dikenal tetapi checksum tidak cocok (salah urutan atau satu kata keliru). */
    BAD_CHECKSUM,
}

/** Kode pemulihan salah ketik, jumlah kata salah, atau bukan kode Rizqflow — pesan boleh spesifik (beda dari sandi) karena checksum ada justru untuk menangkap salah ketik lebih awal, bukan menyembunyikan tebakan. */
class InvalidRecoveryCode(
    message: String,
    val kind: InvalidRecoveryCodeKind = InvalidRecoveryCodeKind.BAD_CHECKSUM,
) : Exception(message)

/** 2048 kata baku (skema sama dengan BIP-39 Inggris) dimuat sekali dari `bip39-english.txt`. */
private object RecoveryWordlist {
    val words: List<String> by lazy {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("bip39-english.txt")) {
            "bip39-english.txt tidak ditemukan di classpath"
        }
        stream.bufferedReader(Charsets.US_ASCII).readLines().also {
            check(it.size == WORD_LIST_SIZE) { "Wordlist harus berisi $WORD_LIST_SIZE kata, ada ${it.size}" }
        }
    }

    val indexOf: Map<String, Int> by lazy { words.withIndex().associate { (i, w) -> w to i } }

    const val WORD_LIST_SIZE = 2048
}

/**
 * Kode pemulihan akun Tahap 13 (server-sync.md): 24 kata yang membawa 256 bit entropi acak plus
 * 8 bit checksum (SHA-256 byte pertama dari entropi), persis skema BIP-39 24-kata — dipilih karena
 * sudah baku dan teruji, bukan dirancang sendiri. Ditampilkan sekali saat Sync diaktifkan; kode ini
 * sendiri (bukan server) yang membuktikan pengguna mengetik ulang dengan benar lewat checksum.
 */
class RecoveryCode private constructor(val words: List<String>, internal val entropy: ByteArray) {

    /** Frasa 24 kata dipisah spasi tunggal, dipakai sebagai masukan penurunan kunci pembungkus. */
    internal fun asPhrase(): String = words.joinToString(" ")

    override fun equals(other: Any?): Boolean = other is RecoveryCode && words == other.words
    override fun hashCode(): Int = words.hashCode()

    /** Sengaja menyembunyikan isi: objek ini tidak boleh membocorkan kata ke log atau laporan crash lewat string. */
    override fun toString(): String = "RecoveryCode(disembunyikan)"

    companion object {
        /** Jumlah kata satu kode pemulihan. */
        const val WORD_COUNT = 24

        /** Apakah [word] (sudah huruf kecil dan tanpa spasi) ada di daftar baku; untuk validasi per kata di layar. */
        fun isKnownWord(word: String): Boolean = word in RecoveryWordlist.indexOf

        private const val ENTROPY_BYTES = 32
        private const val BITS_PER_WORD = 11
        private const val TOTAL_BITS = WORD_COUNT * BITS_PER_WORD // 264 = (32 + 1) byte tepat

        fun generate(random: SecureRandom = SecureRandom()): RecoveryCode {
            val entropy = ByteArray(ENTROPY_BYTES).also(random::nextBytes)
            return RecoveryCode(wordsFor(entropy), entropy)
        }

        /** @throws InvalidRecoveryCode bila jumlah kata salah, ada kata tak dikenal, atau checksum tidak cocok (salah ketik). */
        fun parse(words: List<String>): RecoveryCode {
            if (words.size != WORD_COUNT) {
                throw InvalidRecoveryCode("Kode pemulihan harus $WORD_COUNT kata, ada ${words.size}", InvalidRecoveryCodeKind.WRONG_COUNT)
            }
            val indices = words.map { word ->
                RecoveryWordlist.indexOf[word]
                    ?: throw InvalidRecoveryCode("Kata \"$word\" tidak ada di daftar kode pemulihan", InvalidRecoveryCodeKind.UNKNOWN_WORD)
            }
            val bits = ByteArray(TOTAL_BITS / 8)
            indices.forEachIndexed { i, index -> writeBits(bits, i * BITS_PER_WORD, index) }
            val entropy = bits.copyOfRange(0, ENTROPY_BYTES)
            val expectedChecksumByte = bits[ENTROPY_BYTES]
            if (checksumByte(entropy) != expectedChecksumByte) {
                throw InvalidRecoveryCode("Checksum kode pemulihan tidak cocok — periksa kembali urutan kata", InvalidRecoveryCodeKind.BAD_CHECKSUM)
            }
            return RecoveryCode(words, entropy)
        }

        private fun wordsFor(entropy: ByteArray): List<String> {
            val bits = entropy + checksumByte(entropy)
            return (0 until WORD_COUNT).map { i -> RecoveryWordlist.words[readBits(bits, i * BITS_PER_WORD)] }
        }

        private fun checksumByte(entropy: ByteArray): Byte =
            MessageDigest.getInstance("SHA-256").digest(entropy)[0]

        /** Membaca 11 bit sebagai angka 0-2047, dari bit paling signifikan (MSB) duluan. */
        private fun readBits(bytes: ByteArray, bitOffset: Int): Int {
            var value = 0
            for (i in 0 until BITS_PER_WORD) {
                val bitPos = bitOffset + i
                val bit = (bytes[bitPos / 8].toInt() shr (7 - bitPos % 8)) and 1
                value = (value shl 1) or bit
            }
            return value
        }

        private fun writeBits(bytes: ByteArray, bitOffset: Int, value: Int) {
            for (i in 0 until BITS_PER_WORD) {
                val bit = (value shr (BITS_PER_WORD - 1 - i)) and 1
                if (bit == 1) {
                    val bitPos = bitOffset + i
                    val byteIndex = bitPos / 8
                    bytes[byteIndex] = (bytes[byteIndex].toInt() or (1 shl (7 - bitPos % 8))).toByte()
                }
            }
        }
    }
}
