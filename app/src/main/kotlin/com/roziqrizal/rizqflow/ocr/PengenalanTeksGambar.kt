package com.roziqrizal.rizqflow.ocr

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Adaptor ML Kit Text Recognition (model bawaan, jalan sepenuhnya di perangkat). Gambar tidak pernah
 * dikirim ke luar perangkat. Hanya mengubah gambar menjadi teks mentah; penguraian nominal, tanggal, dan
 * deskripsi ada di :domain (ParserTeksTransaksi), supaya bisa diuji tanpa perangkat.
 */
class PengenalanTeksGambar(private val context: Context) {

    /** Mengembalikan teks mentah dari gambar di [uri]. Melempar galat bila gambar tidak bisa dibaca ML Kit. */
    suspend fun kenali(uri: Uri): String {
        val pengenal = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val gambar = InputImage.fromFilePath(context, uri)
            return pengenal.process(gambar).await().text
        } finally {
            pengenal.close()
        }
    }
}

/** Membungkus [Task] ML Kit menjadi suspend function tanpa menarik kotlinx-coroutines-play-services. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { lanjutan ->
    addOnSuccessListener { hasil -> lanjutan.resume(hasil) }
    addOnFailureListener { galat -> lanjutan.resumeWithException(galat) }
    addOnCanceledListener { lanjutan.cancel() }
}
