package com.roziqrizal.rizqflow.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Penerima widget "Sisa aman hari ini". Disegarkan berkala oleh sistem (`updatePeriodMillis`) dan
 * saat tanggal, jam, atau zona waktu berubah, supaya sisa aman ikut berganti hari tanpa menunggu
 * pembaruan berikutnya. Pembaruan lain datang dari aplikasi sendiri ([SafeToSpendWidget.updateAll]).
 */
class SafeToSpendWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        // APPWIDGET_UPDATE ditangani super (memanggil onUpdate); perubahan waktu menyegarkan langsung.
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> refresh(context)
        }
    }

    /** `goAsync` memberi penerima beberapa detik lagi untuk membaca database tanpa memblokir thread utama. */
    private fun refresh(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SafeToSpendWidget.updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}
