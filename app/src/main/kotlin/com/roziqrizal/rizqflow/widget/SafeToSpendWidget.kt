package com.roziqrizal.rizqflow.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.roziqrizal.rizqflow.ACTION_QUICK_CATAT
import com.roziqrizal.rizqflow.MainActivity
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.auth.SharedPrefsSecurityStore
import com.roziqrizal.rizqflow.auth.SharedPrefsSessionStore
import com.roziqrizal.rizqflow.billing.PurchaseStore
import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import com.roziqrizal.rizqflow.domain.auth.AppLockService
import com.roziqrizal.rizqflow.domain.auth.Pbkdf2PasswordHasher
import com.roziqrizal.rizqflow.domain.entitlement.Feature
import com.roziqrizal.rizqflow.domain.entitlement.LivePlanEntitlements
import com.roziqrizal.rizqflow.domain.ledger.DenahLoader
import com.roziqrizal.rizqflow.domain.ledger.SafeToSpend
import com.roziqrizal.rizqflow.ui.formatRupiah
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import java.time.LocalDate

/**
 * Widget layar utama "Sisa aman hari ini" dengan tombol Catat (Pro, `Feature.HOME_WIDGET`). Tombol
 * membuka Catat kilat (S24) lewat aksi yang sama dengan pintasan ikon dan tile Quick Settings; area lain
 * membuka aplikasi. Memakai RemoteViews biasa (tanpa pustaka Glance) karena isinya hanya teks dan satu tombol.
 *
 * Disegarkan tiga cara: berkala oleh sistem (paling sering tiap 30 menit), saat tanggal berganti
 * ([SafeToSpendWidgetProvider]), dan oleh aplikasi sendiri tiap ada transaksi atau paket berubah
 * ([updateAll] dari `MainHost`). Nominal disembunyikan bila kunci aplikasi aktif ([WidgetState.Locked]).
 */
object SafeToSpendWidget {

    /**
     * Memperbarui semua widget terpasang. [workspace] diisi bila pemanggil sudah punya ruang kerja yang
     * terbuka (dari aplikasi), supaya tidak membuka koneksi database kedua; kosong berarti dibuka
     * sendiri lalu ditutup (dari penerima siaran sistem). Tanpa widget terpasang tidak melakukan apa pun.
     */
    suspend fun updateAll(context: Context, workspace: AccountWorkspace? = null) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(ComponentName(appContext, SafeToSpendWidgetProvider::class.java))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, render(appContext, resolve(appContext, workspace)))
    }

    private suspend fun resolve(context: Context, workspace: AccountWorkspace?): WidgetState {
        val pro = LivePlanEntitlements { PurchaseStore.shared(context).plans.value }.isEnabled(Feature.HOME_WIDGET)
        val accountId = SharedPrefsSessionStore(context).current()?.accountId?.takeIf { it != AccountStorage.DEMO_ACCOUNT_ID }
        // Dibaca sebelum data: bila terkunci, ruang kerja tidak perlu dibuka sama sekali.
        val locked = AppLockService(SharedPrefsSecurityStore(context), Pbkdf2PasswordHasher()).isPinSet()
        return WidgetStateResolver.resolve(pro, signedIn = accountId != null, lockEnabled = locked) {
            val today = LocalDate.now()
            if (workspace != null) {
                safeToSpend(workspace, today)
            } else {
                AccountWorkspace.open(context, requireNotNull(accountId)).use { safeToSpend(it, today) }
            }
        }
    }

    private suspend fun safeToSpend(workspace: AccountWorkspace, today: LocalDate): SafeToSpend? {
        val repos = workspace.repositories
        return DenahLoader(repos.rooms, repos.transactions, repos.accounts).safeToSpend(today)
    }

    private fun render(context: Context, state: WidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_safe_to_spend)
        val label: String
        val amount: String
        val hint: String
        var showButton = true
        when (state) {
            WidgetState.NeedsPro -> {
                label = context.getString(R.string.menu_pro)
                amount = context.getString(R.string.widget_pro_title)
                hint = context.getString(R.string.widget_pro_hint)
                showButton = false
            }

            WidgetState.SignedOut -> {
                label = context.getString(R.string.widget_label)
                amount = context.getString(R.string.widget_none)
                hint = context.getString(R.string.widget_signed_out)
            }

            WidgetState.Locked -> {
                label = context.getString(R.string.widget_label)
                amount = context.getString(R.string.widget_hidden_amount)
                hint = context.getString(R.string.widget_locked)
            }

            WidgetState.NoBudget -> {
                label = context.getString(R.string.widget_label)
                amount = context.getString(R.string.widget_none)
                hint = context.getString(R.string.widget_no_budget)
            }

            is WidgetState.Safe -> {
                label = context.getString(R.string.widget_label)
                amount = formatRupiah(state.remaining)
                hint = if (state.isOver) {
                    context.getString(R.string.widget_over)
                } else {
                    context.getString(R.string.widget_daily_budget, formatRupiah(state.dailyBudget))
                }
            }
        }
        views.setTextViewText(R.id.widget_label, label)
        views.setTextViewText(R.id.widget_amount, amount)
        views.setTextViewText(R.id.widget_hint, hint)
        views.setViewVisibility(R.id.widget_catat, if (showButton) View.VISIBLE else View.GONE)
        views.setContentDescription(R.id.widget_catat, context.getString(R.string.widget_catat_description))
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
        views.setOnClickPendingIntent(R.id.widget_catat, openQuickCatat(context))
        return views
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openQuickCatat(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_QUICK_CATAT,
        Intent(context, MainActivity::class.java).setAction(ACTION_QUICK_CATAT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val REQUEST_OPEN_APP = 1001
    private const val REQUEST_QUICK_CATAT = 1002
}
