package com.roziqrizal.rizqflow.data.sync

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.roziqrizal.rizqflow.domain.sync.NetworkChangeSource

/**
 * Pendengar perubahan jaringan bawaan untuk sync otomatis ([NetworkChangeSource]), memakai
 * `ConnectivityManager.registerDefaultNetworkCallback`. Orkestrator mendaftarkannya HANYA saat aplikasi di depan dan
 * menutupnya saat ke latar atau ruang kerja ditutup, jadi tidak ada callback yang hidup di latar.
 *
 * Callback ini hanya memberi tahu "ada perubahan": [AutoSyncOrchestrator][com.roziqrizal.rizqflow.domain.sync.AutoSyncOrchestrator]
 * menilai ulang jaringan lewat `AutoSyncNetwork` (jadi kapabilitas yang diterima callback tidak dipercaya mentah-mentah),
 * dan tidak memicu apa pun kecuali ada sync yang tertunda karena jaringan. Callback pertama datang segera setelah
 * mendaftar (jaringan yang sedang aktif); itu aman karena belum ada yang tertunda saat itu.
 *
 * Tidak pernah melempar: gagal mendaftar (izin, batas jumlah callback sistem) berarti [AutoCloseable] kosong dan sync
 * otomatis tetap berjalan lewat pemicu lain (aplikasi dibuka, penyimpanan lokal, tombol manual). Belum diuji di perangkat
 * sungguhan; perilaku sistem (mis. seberapa sering `onCapabilitiesChanged` datang) hanya diuji lewat shadow Robolectric.
 */
class AndroidNetworkChangeSource(
    context: Context,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : NetworkChangeSource {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    // Alasan suppress: ACCESS_NETWORK_STATE dideklarasikan di manifest :app (app/src/main/AndroidManifest.xml, juga dipakai
    // AndroidAutoSyncNetwork); :data adalah modul pustaka tanpa manifest sendiri, jadi lint tidak bisa melihatnya. Bila izin
    // itu tidak ada saat dijalankan, SecurityException ditangkap di bawah dan sync otomatis jalan lewat pemicu lain.
    @SuppressLint("MissingPermission")
    override fun watch(onChange: () -> Unit): AutoCloseable {
        val manager = connectivity ?: return AutoCloseable { }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notify(onChange)

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = notify(onChange)
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (e: RuntimeException) {
            // SecurityException (izin) dan RuntimeException "terlalu banyak callback" dari sistem.
            log("pendengar jaringan gagal dipasang (${e.javaClass.simpleName})")
            return AutoCloseable { }
        }
        return AutoCloseable {
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (e: RuntimeException) {
                // Sudah dilepas (IllegalArgumentException) atau sistem menolak: tidak ada yang bisa dilakukan lagi.
                log("pendengar jaringan gagal dilepas (${e.javaClass.simpleName})")
            }
        }
    }

    // Dipanggil di thread konektivitas sistem: galat dari pendengar tidak boleh menjatuhkannya.
    private fun notify(onChange: () -> Unit) {
        try {
            onChange()
        } catch (e: Exception) {
            log("kabar jaringan gagal diteruskan (${e.javaClass.simpleName})")
        }
    }

    private companion object {
        const val TAG = "NetworkChangeSource"
    }
}
