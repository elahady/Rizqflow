package com.roziqrizal.rizqflow.domain.sync

import java.security.SecureRandom
import java.util.Locale
import java.util.Random

/**
 * Kode pemulihan yang SEDANG ditampilkan sekali ke pengguna (Tahap 13). Hanya hidup di memori selama
 * layar tampil: tidak ada jalur menyimpannya (tidak ke SharedPreferences, tidak ke state tersimpan), dan
 * [toString] sengaja menyembunyikan kata supaya tidak bocor ke log atau laporan crash lewat string.
 * Dibuat hanya oleh [RecoveryKeyService.enableRecovery] SETELAH unggahan ke server berhasil.
 */
class RecoveryCodeDisplay internal constructor(private val code: RecoveryCode) {
    /** 24 kata berurutan; nomor tampil = indeks + 1. */
    val words: List<String> get() = code.words

    /** Menguji pengguna benar-benar mencatat: [RecoveryChallenge.POSITION_COUNT] posisi acak yang diundi baru tiap pemanggilan. */
    fun newChallenge(random: Random = SecureRandom()): RecoveryChallenge =
        RecoveryChallenge(RecoveryChallenge.pickPositions(random), code)

    override fun toString(): String = "RecoveryCodeDisplay(disembunyikan)"
}

/**
 * Tantangan verifikasi catatan: pengguna mengetik ulang kata pada beberapa nomor yang diundi acak
 * ([positions], berurut naik, nomor mulai 1) sebelum tombol selesai boleh aktif. Ini usulan rancangan:
 * layar tampil-sekali tidak punya cara lain membuktikan kode benar-benar tercatat (menyalin ke papan klip
 * bukan bukti). Undian baru tiap kali supaya tidak bisa dihafal dari percobaan sebelumnya.
 */
class RecoveryChallenge internal constructor(val positions: List<Int>, private val code: RecoveryCode) {

    /** Benar bila SETIAP nomor di [positions] dijawab dan cocok ([answers] = nomor ke ketikan; spasi dan huruf besar diabaikan). */
    fun isCorrect(answers: Map<Int, String>): Boolean =
        positions.all { position ->
            val typed = answers[position] ?: return@all false
            RecoveryWordsInput.normalizeWord(typed) == code.words[position - 1]
        }

    override fun toString(): String = "RecoveryChallenge(posisi=$positions)"

    companion object {
        const val POSITION_COUNT = 3

        internal fun pickPositions(random: Random): List<Int> =
            (1..RecoveryCode.WORD_COUNT).shuffled(random).take(POSITION_COUNT).sorted()
    }
}

/** Mengurai dan menormalkan ketikan kode pemulihan dari pengguna (tempel atau ketik), tanpa menyentuh server. */
object RecoveryWordsInput {
    private val NOT_LETTERS = Regex("[^a-z]+")

    /**
     * Memecah teks tempel menjadi kata: semua selain huruf a-z (spasi, baris baru, koma, nomor urut
     * seperti "1." atau "12)") dianggap pemisah, dan huruf besar diubah ke kecil. Daftar kata baku hanya
     * berisi huruf a-z, jadi tidak ada kata sah yang rusak oleh aturan ini.
     */
    fun split(text: String): List<String> =
        text.lowercase(Locale.ROOT).split(NOT_LETTERS).filter { it.isNotEmpty() }

    fun normalizeWord(word: String): String = word.trim().lowercase(Locale.ROOT)

    /** Nomor (mulai 1) dan isi kata yang tidak ada di daftar baku: bahan pesan salah ketik per kata. */
    fun unknownWords(words: List<String>): List<Pair<Int, String>> =
        words.mapIndexedNotNull { index, word ->
            if (RecoveryCode.isKnownWord(normalizeWord(word))) null else (index + 1) to word
        }

    /**
     * Perubahan teks sekaligus lebih dari satu karakter dianggap tempel (atau sisip papan ketik): berbeda dengan
     * mengetik huruf demi huruf yang menambah satu karakter. Kata terakhir hasil tempel sudah utuh.
     */
    fun isBulkChange(previous: String, current: String): Boolean = current.length - previous.length > 1

    /**
     * Kata tak dikenal yang BOLEH DIPERINGATKAN sekarang. Selagi pengguna mengetik, token terakhir belum selesai:
     * "aban" tentu belum ada di daftar, dan memperingatkannya (apalagi diumumkan pembaca layar) di setiap huruf
     * hanya mengganggu. Maka token terakhir diabaikan selama teks belum diakhiri pemisah (spasi, baris baru, tanda
     * baca) dan perubahan terakhir bukan tempel ([pasted]). Hanya untuk TAMPILAN: pemeriksaan sah-tidaknya kode
     * (tombol Pulihkan) tetap memakai [unknownWords] atas semua kata.
     */
    fun unknownWordsToWarn(text: String, pasted: Boolean): List<Pair<Int, String>> {
        val words = split(text)
        val unknown = unknownWords(words)
        val lastIsStillTyped = !pasted && text.isNotEmpty() && text.last().lowercaseChar() in 'a'..'z'
        return if (lastIsStillTyped) unknown.filter { (position, _) -> position != words.size } else unknown
    }
}
