package com.roziqrizal.rizqflow.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.roziqrizal.rizqflow.auth.SharedPrefsSessionStore
import com.roziqrizal.rizqflow.billing.PurchaseStore
import com.roziqrizal.rizqflow.domain.entitlement.LivePlanEntitlements
import com.roziqrizal.rizqflow.notifications.ReminderNotifier
import kotlinx.coroutines.runBlocking

/**
 * Penerima FCM (Tahap 9/13, wiring 2026-11-01). Hanya dipanggil Firebase bila Firebase terinisialisasi, yaitu bila
 * build membawa `google-services.json` (belum ada; lihat [FcmAvailability]). Tanpa itu kelas ini tidak pernah jalan.
 *
 * Pesan dari server WAJIB data message murni (`data` = `roomId` + `kind`, tanpa blok `notification`): blok
 * `notification` ditampilkan sistem sendiri saat aplikasi di latar tanpa melewati gerbang Sync dan parser di sini.
 * Nama ruang tidak dibaca dari database di sini (teks generik saja), supaya penerima tidak membuka database akun di
 * latar; nominal dan isi catatan tidak pernah ditampilkan.
 */
class RizqflowMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val context = applicationContext
        // Firebase memanggil ini di thread latar miliknya, jadi membaca berkas sesi secara sinkron aman di sini.
        val session = runBlocking { SharedPrefsSessionStore(context).current() }
        val receiver = SyncPushReceiver(
            entitlements = LivePlanEntitlements { PurchaseStore.shared(context).plans.value },
            hasServerAccount = { isServerAccount(session) },
            show = { payload -> ReminderNotifier.showSync(context, payload, roomName = null) },
        )
        val decision = receiver.onData(message.data)
        // Hanya jenis keputusannya yang dicatat, tidak pernah isi data push.
        if (decision !is SyncPushDecision.Shown) Log.i(TAG, "Push sync tidak ditampilkan: ${decision::class.simpleName}")
    }

    private companion object {
        const val TAG = "RizqflowPush"
    }
}
