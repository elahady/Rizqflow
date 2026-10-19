package com.roziqrizal.rizqflow.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.roziqrizal.rizqflow.MainActivity
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.bill.BillReminder
import com.roziqrizal.rizqflow.domain.bill.BillReminderStage
import com.roziqrizal.rizqflow.domain.debt.DebtDirection
import com.roziqrizal.rizqflow.domain.debt.DebtReminder
import com.roziqrizal.rizqflow.domain.role.DcaView
import com.roziqrizal.rizqflow.domain.sync.SyncNotificationKind
import com.roziqrizal.rizqflow.domain.sync.SyncNotificationPayload
import com.roziqrizal.rizqflow.domain.zakat.HaulStatus
import com.roziqrizal.rizqflow.ui.formatRupiah

/** Menyusun dan menampilkan notifikasi pengingat malam (S26) beserta aksi Balas dan Tidak ada. */
object ReminderNotifier {
    const val CHANNEL_ID = "reminder_daily"
    const val NOTIFICATION_ID = 2601
    const val EXTRA_ACCOUNT_ID = "account_id"
    const val ACTION_REPLY = "com.roziqrizal.rizqflow.action.REMINDER_REPLY"
    const val ACTION_DISMISS = "com.roziqrizal.rizqflow.action.REMINDER_DISMISS"
    const val REMOTE_INPUT_KEY = "reminder_reply"

    /** Pemicu khusus haul (Tahap 6): channel dan id notifikasi terpisah, tapi menumpang worker dan izin yang sama. */
    private const val CHANNEL_ID_HAUL = "reminder_haul"
    private const val NOTIFICATION_ID_HAUL_BASE = 2610

    /** Pemicu jadwal DCA (sistem per peran, Pro): channel dan id terpisah, menumpang worker yang sama. */
    private const val CHANNEL_ID_DCA = "reminder_dca"
    private const val NOTIFICATION_ID_DCA_BASE = 2900

    /** Pemicu tagihan dan cicilan (Tahap 10): channel dan id terpisah, menumpang worker yang sama. */
    private const val CHANNEL_ID_BILL = "reminder_bill"
    private const val NOTIFICATION_ID_BILL_BASE = 3200

    /** Pemicu utang-piutang (Tahap 10): channel dan id terpisah, menumpang worker yang sama. */
    private const val CHANNEL_ID_DEBT = "reminder_debt"
    private const val NOTIFICATION_ID_DEBT_BASE = 3500

    /** Pemicu notifikasi sync generik (Tahap 13): channel dan id terpisah, menumpang worker yang sama. */
    private const val CHANNEL_ID_SYNC = "sync_generic"
    private const val NOTIFICATION_ID_SYNC_BASE = 3800

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.reminder_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    private fun ensureHaulChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID_HAUL,
            context.getString(R.string.reminder_haul_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.reminder_haul_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    private fun ensureDcaChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID_DCA,
            context.getString(R.string.reminder_dca_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.reminder_dca_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    private fun ensureBillChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID_BILL,
            context.getString(R.string.reminder_bill_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.reminder_bill_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    private fun ensureDebtChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID_DEBT,
            context.getString(R.string.reminder_debt_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.reminder_debt_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    private fun ensureSyncChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID_SYNC,
            context.getString(R.string.reminder_sync_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.reminder_sync_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    fun show(context: Context, accountId: String) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val openIntent = openIntent(context)
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel(context.getString(R.string.reminder_reply_hint)).build()
        val replyIntent = Intent(context, ReminderActionReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_ACCOUNT_ID, accountId)
        val replyPending = PendingIntent.getBroadcast(context, 1, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val replyAction = NotificationCompat.Action.Builder(0, context.getString(R.string.reminder_reply_action), replyPending)
            .addRemoteInput(remoteInput)
            .build()

        val dismissIntent = Intent(context, ReminderActionReceiver::class.java).setAction(ACTION_DISMISS).putExtra(EXTRA_ACCOUNT_ID, accountId)
        val dismissPending = PendingIntent.getBroadcast(context, 2, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismissAction = NotificationCompat.Action.Builder(0, context.getString(R.string.reminder_dismiss_action), dismissPending).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_catat)
            .setContentTitle(context.getString(R.string.reminder_notif_title))
            .setContentText(context.getString(R.string.reminder_notif_body))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(replyAction)
            .addAction(dismissAction)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Menimpa notifikasi yang sama dengan versi tanpa aksi setelah balasan berhasil dicatat.
     * `cancel()` biasa tidak cukup: sistem menahan notifikasi berbalasan langsung (flag
     * `LIFETIME_EXTENDED_BY_DIRECT_REPLY`) sampai kontennya diperbarui, bukan sekadar dibatalkan.
     */
    fun showReplySaved(context: Context, message: String) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_catat)
            .setContentTitle(message)
            .setContentIntent(openIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Haul genap atau mendekati genap (ambang [com.roziqrizal.rizqflow.domain.zakat.HaulReminderService.APPROACHING_THRESHOLD_DAYS]
     * hari): satu notifikasi per ruang Memberi bermode zakat, tanpa aksi Balas/Tidak ada (beda
     * konteks dari pengingat malam), tanpa mengganggu notifikasi pengingat malam yang mungkin
     * tampil hari yang sama karena channel dan id-nya terpisah.
     */
    fun showHaul(context: Context, roomId: String, profileId: String, profileName: String?, status: HaulStatus) {
        if (!canNotify(context)) return
        ensureHaulChannel(context)

        val title: String
        val body: String
        when (status) {
            is HaulStatus.Completed -> {
                title = context.getString(R.string.reminder_haul_completed_title)
                body = context.getString(R.string.reminder_haul_completed_body)
            }

            is HaulStatus.Running -> {
                title = context.getString(R.string.reminder_haul_approaching_title, status.daysLeft.toInt())
                body = context.getString(R.string.reminder_haul_approaching_body)
            }

            HaulStatus.BelowNisab -> return
        }

        // Dengan beberapa profil harta, nama profil disebut supaya jelas haul siapa yang dimaksud.
        val text = if (profileName != null) context.getString(R.string.reminder_haul_profile_body, body, profileName) else body
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_HAUL)
            .setSmallIcon(R.drawable.ic_quick_catat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_HAUL_BASE + ((roomId + profileId).hashCode() and 0xFF), notification)
    }

    /**
     * Jadwal DCA yang tanggalnya tiba dan belum dicatat bulan ini: satu notifikasi per ruang, sekali
     * per bulan (penandanya ada di `DcaReminderService`). Tanpa aksi; mengetuknya membuka aplikasi.
     */
    fun showDca(context: Context, roomId: String, roomName: String, view: DcaView) {
        if (!canNotify(context)) return
        ensureDcaChannel(context)

        val amount = formatRupiah(view.plan.amount)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DCA)
            .setSmallIcon(R.drawable.ic_quick_catat)
            .setContentTitle(context.getString(R.string.reminder_dca_title))
            .setContentText(context.getString(R.string.reminder_dca_body, amount, view.accountName, roomName))
            .setContentIntent(openIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_DCA_BASE + (roomId.hashCode() and 0xFF), notification)
    }

    /**
     * Tagihan atau cicilan yang mendekati jatuh tempo, jatuh tempo hari ini, atau terlambat: satu
     * notifikasi per tagihan, paling banyak tiga kali per jatuh tempo (penandanya ada di
     * `BillReminderService`). Tanpa aksi; mengetuknya membuka aplikasi. Bayar dilakukan di layar
     * Tagihan, bukan dari notifikasi, supaya nominalnya bisa diperiksa dulu.
     */
    fun showBill(context: Context, reminder: BillReminder, roomName: String, accountName: String) {
        if (!canNotify(context)) return
        ensureBillChannel(context)

        val bill = reminder.bill
        val title = when (reminder.stage) {
            BillReminderStage.APPROACHING ->
                context.resources.getQuantityString(R.plurals.reminder_bill_approaching, reminder.daysLeft, bill.name, reminder.daysLeft)

            BillReminderStage.DUE_TODAY -> context.getString(R.string.reminder_bill_today, bill.name)

            BillReminderStage.OVERDUE ->
                context.resources.getQuantityString(R.plurals.reminder_bill_overdue, -reminder.daysLeft, bill.name, -reminder.daysLeft)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_BILL)
            .setSmallIcon(R.drawable.ic_quick_catat)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.reminder_bill_body, formatRupiah(bill.amount), accountName, roomName))
            .setContentIntent(openIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_BILL_BASE + (bill.id.hashCode() and 0xFF), notification)
    }

    /**
     * Utang atau piutang yang mendekati jatuh tempo, jatuh tempo hari ini, atau terlambat: satu
     * notifikasi per catatan, paling banyak tiga kali per jatuh tempo (penandanya ada di
     * `DebtReminderService`). Piutang memakai kata yang lembut karena yang diingatkan adalah
     * menagih orang lain. Tanpa aksi; mengetuknya membuka aplikasi.
     */
    fun showDebt(context: Context, reminder: DebtReminder) {
        if (!canNotify(context)) return
        ensureDebtChannel(context)

        val debt = reminder.debt
        val lent = debt.direction == DebtDirection.LENT
        val resources = context.resources
        val title = when (reminder.stage) {
            BillReminderStage.APPROACHING ->
                resources.getQuantityString(if (lent) R.plurals.reminder_debt_lent_approaching else R.plurals.reminder_debt_borrowed_approaching, reminder.daysLeft, debt.party, reminder.daysLeft)

            BillReminderStage.DUE_TODAY -> context.getString(if (lent) R.string.reminder_debt_lent_today else R.string.reminder_debt_borrowed_today, debt.party)

            BillReminderStage.OVERDUE ->
                resources.getQuantityString(if (lent) R.plurals.reminder_debt_lent_overdue else R.plurals.reminder_debt_borrowed_overdue, -reminder.daysLeft, debt.party, -reminder.daysLeft)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DEBT)
            .setSmallIcon(R.drawable.ic_quick_catat)
            .setContentTitle(title)
            .setContentText(context.getString(if (lent) R.string.reminder_debt_body_lent else R.string.reminder_debt_body_borrowed, formatRupiah(reminder.outstanding)))
            .setContentIntent(openIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_DEBT_BASE + (debt.id.hashCode() and 0xFF), notification)
    }

    /**
     * Notifikasi sync generik (Tahap 13, server-sync.md bagian "Notifikasi"): isinya cuma kabar
     * umum dari [payload.kind][SyncNotificationPayload.kind], ditambah [roomName] yang sudah ada
     * tersimpan terdekripsi di perangkat ini sendiri (bukan dari payload push, lihat
     * [SyncNotificationPayload]) — nominal dan isi catatan tidak pernah ditampilkan di sini, dan
     * baru didapat setelah aplikasi mengambil dan mendekripsi data sungguhan sendiri (belum ada,
     * menunggu server Tahap 9). Mengetuknya hanya membuka aplikasi, tanpa aksi.
     */
    fun showSync(context: Context, payload: SyncNotificationPayload, roomName: String?) {
        if (!canNotify(context)) return
        ensureSyncChannel(context)

        val body = when (payload.kind) {
            SyncNotificationKind.NEW_RECORD ->
                if (roomName != null) context.getString(R.string.reminder_sync_new_record_room, roomName)
                else context.getString(R.string.reminder_sync_new_record)

            SyncNotificationKind.MEMBER_REMOVED -> context.getString(R.string.reminder_sync_member_removed)
            SyncNotificationKind.ROOM_KEY_ROTATED -> context.getString(R.string.reminder_sync_room_key_rotated)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SYNC)
            .setSmallIcon(R.drawable.ic_quick_catat)
            .setContentText(body)
            .setContentIntent(openIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SYNC_BASE + (payload.roomId.value.hashCode() and 0xFF), notification)
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
