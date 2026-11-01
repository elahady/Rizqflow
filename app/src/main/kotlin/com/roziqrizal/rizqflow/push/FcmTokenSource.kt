package com.roziqrizal.rizqflow.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.roziqrizal.rizqflow.domain.sync.PushTokenSync
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Token FCM perangkat ini, atau null bila jalur FCM mati (Firebase tidak terinisialisasi karena build tanpa
 * `google-services.json`), token belum bisa diambil, atau terjadi galat apa pun. Tidak pernah melempar.
 */
object FcmTokenSource {
    suspend fun currentToken(context: Context, timeoutMillis: Long = 10_000L): String? {
        if (!FcmAvailability.isAvailable(context)) return null
        return try {
            withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        val token = if (task.isSuccessful) task.result else null
                        if (continuation.isActive) continuation.resume(token?.takeIf { it.isNotBlank() })
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Mencabut token FCM perangkat ini dengan [apiToken] yang masih berlaku, sebagai upaya terbaik dengan batas waktu
 * (dipakai sebelum Hapus akun). Gagal, offline, atau kehabisan waktu tidak menghalangi apa pun: begitu akun server
 * dihapus, server tidak lagi punya alasan mengirim push ke perangkat ini.
 */
suspend fun PushTokenSync.revokeBestEffort(apiToken: String, timeoutMillis: Long = 5_000L) {
    try {
        withTimeoutOrNull(timeoutMillis) { revoke(apiToken) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Upaya terbaik.
    }
}
