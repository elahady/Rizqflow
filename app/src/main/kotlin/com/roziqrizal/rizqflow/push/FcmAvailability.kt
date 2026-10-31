package com.roziqrizal.rizqflow.push

import android.content.Context
import com.google.firebase.FirebaseApp

/**
 * Apakah jalur FCM hidup di proses ini. Firebase hanya terinisialisasi bila build membawa `google-services.json`
 * (plugin google-services diterapkan bersyarat di app/build.gradle.kts). Proyek Firebase pemilik belum ada, jadi
 * saat ini selalu false: tidak ada token FCM, tidak ada pendaftaran ke server, dan tidak ada panggilan
 * `FirebaseMessaging.getInstance()` (yang melempar IllegalStateException bila Firebase belum terinisialisasi).
 * Semua pemakai FCM wajib bertanya ke sini dulu supaya jalur ini mati dengan tenang, bukan crash.
 */
object FcmAvailability {
    fun isAvailable(context: Context): Boolean =
        try {
            FirebaseApp.getApps(context.applicationContext).isNotEmpty()
        } catch (_: RuntimeException) {
            false
        }
}
