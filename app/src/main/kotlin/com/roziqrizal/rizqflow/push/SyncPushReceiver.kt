package com.roziqrizal.rizqflow.push

import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import com.roziqrizal.rizqflow.domain.auth.AuthProviderType
import com.roziqrizal.rizqflow.domain.auth.AuthSession
import com.roziqrizal.rizqflow.domain.entitlement.Entitlements
import com.roziqrizal.rizqflow.domain.entitlement.Feature
import com.roziqrizal.rizqflow.domain.sync.SyncNotificationPayload
import com.roziqrizal.rizqflow.domain.sync.SyncNotificationPayloadCorrupt

/** Nasib satu data push yang diterima; hanya [Shown] yang menghasilkan notifikasi. */
sealed interface SyncPushDecision {
    data class Shown(val payload: SyncNotificationPayload) : SyncPushDecision

    /** Tidak ada akun server yang sedang masuk (belum masuk, akun lokal, atau mode demo). */
    data object NoServerAccount : SyncPushDecision

    /** Pengguna tidak punya Sync (lewat lapisan entitlement): notifikasi sync tidak ditampilkan. */
    data object NoSync : SyncPushDecision

    /** Data push tidak sesuai kontrak (kunci berlebih, `kind` tak dikenal, `roomId` kosong): dibuang utuh. */
    data object Rejected : SyncPushDecision
}

/**
 * Keputusan penerima FCM (Tahap 9/13), dipisah dari `RizqflowMessagingService` supaya bisa diuji tanpa Firebase:
 * notifikasi hanya muncul bila ada akun server yang masuk, akun itu punya Sync ([Feature.ENCRYPTED_SYNC], lewat
 * satu lapisan entitlement), dan data push lolos [SyncNotificationPayload.parse] (hanya `roomId` + `kind`).
 * Gerbang diperiksa SEBELUM data dibaca. Isi data push tidak pernah dicatat ke log.
 */
class SyncPushReceiver(
    private val entitlements: Entitlements,
    private val hasServerAccount: () -> Boolean,
    private val show: (SyncNotificationPayload) -> Unit,
) {
    fun onData(data: Map<String, String>): SyncPushDecision {
        if (!hasServerAccount()) return SyncPushDecision.NoServerAccount
        if (!entitlements.isEnabled(Feature.ENCRYPTED_SYNC)) return SyncPushDecision.NoSync
        val payload = try {
            SyncNotificationPayload.parse(data)
        } catch (_: SyncNotificationPayloadCorrupt) {
            return SyncPushDecision.Rejected
        }
        show(payload)
        return SyncPushDecision.Shown(payload)
    }
}

/**
 * Akun yang punya akun server (masuk dengan Google atau email+sandi) dan bukan mode demo. Akun lokal
 * ([AuthProviderType.PASSWORD]) dan akun uji ([AuthProviderType.DEBUG]) tidak pernah menerima atau mendaftarkan push.
 */
fun isServerAccount(session: AuthSession?): Boolean =
    session != null &&
        session.accountId != AccountStorage.DEMO_ACCOUNT_ID &&
        (session.provider == AuthProviderType.GOOGLE || session.provider == AuthProviderType.EMAIL)
