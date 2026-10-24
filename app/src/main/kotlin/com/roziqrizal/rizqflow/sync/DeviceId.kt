package com.roziqrizal.rizqflow.sync

import android.content.Context
import java.util.UUID

/**
 * Pengenal perangkat acak, dibuat sekali per pemasangan aplikasi dan dipakai ulang selamanya
 * (bukan per akun: berganti akun di perangkat yang sama tetap pengenal yang sama). Server
 * memakainya untuk mencabut token lama di perangkat yang sama saat masuk ulang
 * ([com.roziqrizal.rizqflow.domain.sync.AccountRemote.loginWithGoogle]). Tidak berhubungan dengan
 * Android ID atau pengenal perangkat sungguhan — murni acak, dibuang begitu aplikasi dihapus.
 */
object DeviceId {
    fun read(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("rizqflow_device", Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    private const val KEY_DEVICE_ID = "device_id"
}
