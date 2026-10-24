package com.roziqrizal.rizqflow

import android.app.Application
import com.roziqrizal.rizqflow.billing.BillingGateway
import com.roziqrizal.rizqflow.billing.PlayBillingGateway
import com.roziqrizal.rizqflow.notifications.ReminderNotifier

class RizqflowApplication : Application() {
    /**
     * Satu gateway Play Billing untuk seluruh proses (BillingClient tidak boleh dibuat berulang).
     * Pembelian terikat ke akun Play perangkat, bukan ke akun ledger, jadi tidak per ruang kerja.
     */
    val billing: BillingGateway by lazy { PlayBillingGateway(this) }

    override fun onCreate() {
        super.onCreate()
        ReminderNotifier.ensureChannel(this)
    }
}
