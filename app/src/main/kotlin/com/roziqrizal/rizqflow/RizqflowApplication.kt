package com.roziqrizal.rizqflow

import android.app.Application
import com.roziqrizal.rizqflow.billing.BillingGateway
import com.roziqrizal.rizqflow.billing.ClaimedTokenMemory
import com.roziqrizal.rizqflow.billing.PlayBillingGateway
import com.roziqrizal.rizqflow.notifications.ReminderNotifier
import com.roziqrizal.rizqflow.sync.RecoveryCodeHolder
import com.roziqrizal.rizqflow.sync.ServerSignOut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RizqflowApplication : Application() {
    /**
     * Satu gateway Play Billing untuk seluruh proses (BillingClient tidak boleh dibuat berulang).
     * Pembelian terikat ke akun Play perangkat, bukan ke akun ledger, jadi tidak per ruang kerja.
     */
    val billing: BillingGateway by lazy { PlayBillingGateway(this) }

    /**
     * Token pembelian yang sudah dilaporkan ke server dan dijawab tegas, seumur proses (Tahap 9). Satu
     * instans untuk seluruh proses, bukan per ruang kerja, supaya satu token tidak dikirim ulang dan
     * pesan "dipakai akun lain" tidak muncul lagi tiap ruang kerja dibuka ulang (lihat [ClaimedTokenMemory]).
     */
    val claimedPurchaseTokens = ClaimedTokenMemory()

    /**
     * Keluar akun terhadap server (Tahap 12): pencabutan token di server berjalan di scope proses ini, bukan
     * scope layar, karena ruang kerja dan menu utama sudah dibongkar begitu pengguna keluar.
     */
    val serverSignOut = ServerSignOut(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /**
     * Kode pemulihan 24 kata yang sedang ditampilkan, hanya di memori proses (Tahap 13), supaya memutar layar tidak
     * membuangnya di tengah mencatat. Tidak pernah disimpan ke disk atau state tersimpan; lihat [RecoveryCodeHolder].
     */
    val recoveryCodeHolder = RecoveryCodeHolder()

    override fun onCreate() {
        super.onCreate()
        ReminderNotifier.ensureChannel(this)
    }
}
