package com.roziqrizal.rizqflow

import android.app.Application
import com.roziqrizal.rizqflow.billing.BillingGateway
import com.roziqrizal.rizqflow.billing.ClaimedTokenMemory
import com.roziqrizal.rizqflow.billing.PlayBillingGateway
import com.roziqrizal.rizqflow.notifications.ReminderNotifier

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

    override fun onCreate() {
        super.onCreate()
        ReminderNotifier.ensureChannel(this)
    }
}
