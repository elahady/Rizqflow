package com.roziqrizal.rizqflow.ui.catat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContract
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.smartinput.VoiceTranscript

/**
 * Input suara (Tahap 11, Pro) lewat [RecognizerIntent.ACTION_RECOGNIZE_SPEECH] bawaan sistem: pengenal
 * dan izin mikrofon dikelola layanan sistem, aplikasi tidak merekam audio sendiri. Teks yang kembali
 * dirapikan [VoiceTranscript] lalu diisikan ke kolom input cerdas yang sama dengan ketikan manual;
 * tidak ada yang tersimpan dari sini. Bahasa dipaku ke Indonesia (`id-ID`).
 *
 * Bila perangkat tidak punya pengenal suara, peluncuran melempar `ActivityNotFoundException` ke pemanggil
 * (ditangani di [SmartInputSheet]), bukan crash.
 */
class SpeechRecognitionContract : ActivityResultContract<Unit, String?>() {

    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.catat_voice_prompt))
        }

    override fun parseResult(resultCode: Int, intent: Intent?): String? {
        if (resultCode != Activity.RESULT_OK) return null
        return VoiceTranscript.best(intent?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS))
    }
}
