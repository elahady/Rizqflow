package com.roziqrizal.rizqflow.notifications

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.widget.SafeToSpendWidget
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import java.time.LocalDate

/**
 * Melakukan pekerjaan sebenarnya dari aksi notifikasi (balasan kilat atau "Tidak ada"). Dijalankan
 * lewat WorkManager, bukan langsung di [ReminderActionReceiver.onReceive], karena membuka
 * [AccountWorkspace] (koneksi Room baru) dan menyimpan transaksi bisa melebihi jendela waktu
 * `goAsync()` milik BroadcastReceiver.
 */
class ReminderActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return Result.success()

        AccountWorkspace.open(applicationContext, accountId).use { workspace ->
            when (inputData.getString(KEY_ACTION)) {
                ReminderNotifier.ACTION_REPLY -> {
                    val text = inputData.getString(KEY_REPLY_TEXT)
                    val parsed = text?.let { workspace.reminder.parseQuickReply(it) }
                    val result = text?.let { workspace.reminder.recordQuickReply(it, LocalDate.now()) }
                    if (result is LedgerResult.Success && parsed != null) {
                        val message = applicationContext.getString(R.string.reminder_reply_saved, formatRupiah(parsed.amount))
                        ReminderNotifier.showReplySaved(applicationContext, message)
                        // Pengeluaran baru lewat balasan notifikasi mengubah sisa aman hari ini di widget.
                        SafeToSpendWidget.updateAll(applicationContext, workspace)
                    } else {
                        // Tidak bisa diuraikan: tampilkan lagi notifikasi aslinya (dengan aksi) supaya bisa dicoba ulang.
                        ReminderNotifier.show(applicationContext, accountId)
                    }
                }

                ReminderNotifier.ACTION_DISMISS -> {
                    workspace.reminder.dismissToday(LocalDate.now())
                    NotificationManagerCompat.from(applicationContext).cancel(ReminderNotifier.NOTIFICATION_ID)
                }
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_ACTION = "action"
        const val KEY_REPLY_TEXT = "reply_text"
    }
}
