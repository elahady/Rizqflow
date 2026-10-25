package com.roziqrizal.rizqflow.domain.smartinput

/**
 * Teks hasil pengenalan suara (Tahap 11, Pro). Pengenal suara sungguhan ada di `:app` (RecognizerIntent
 * bawaan sistem); di sini hanya aturan memilih satu kalimat dari daftar kandidat yang dikembalikan
 * pengenal. Kalimat itu lalu masuk ke [SmartInputParser] yang sama dengan ketikan manual, dan hasilnya
 * tetap hanya mengisi layar konfirmasi, tidak pernah tersimpan sendiri.
 */
object VoiceTranscript {

    /** Kandidat pertama yang tidak kosong setelah dirapikan spasinya; null bila pengenal tidak menangkap apa pun. */
    fun best(candidates: List<String?>?): String? =
        candidates.orEmpty()
            .asSequence()
            .mapNotNull { it?.trim()?.replace(WHITESPACE, " ")?.takeIf(String::isNotEmpty) }
            .firstOrNull()

    private val WHITESPACE = Regex("\\s+")
}
