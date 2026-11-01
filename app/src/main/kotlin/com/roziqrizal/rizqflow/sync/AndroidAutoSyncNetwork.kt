package com.roziqrizal.rizqflow.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.roziqrizal.rizqflow.domain.sync.AutoSyncNetwork
import com.roziqrizal.rizqflow.domain.sync.AutoSyncNetworkPolicy
import com.roziqrizal.rizqflow.domain.sync.AutoSyncSettings
import com.roziqrizal.rizqflow.domain.sync.NetworkCheck
import com.roziqrizal.rizqflow.domain.sync.NetworkKind

/**
 * Membaca jaringan aktif untuk sync otomatis (Tahap 6, keputusan #15). Yang dinilai "Wi-Fi" di sini adalah jaringan yang
 * Android sendiri tandai TIDAK berbayar (`NET_CAPABILITY_NOT_METERED`), bukan nama transportnya: Wi-Fi yang ditandai
 * berbayar oleh pengguna (mis. hotspot ponsel lain) tidak dianggap Wi-Fi, dan paket seluler tanpa batas yang
 * ditandai tidak berbayar oleh operator boleh. Pilihan ini usulan Claude (belum dikonfirmasi pemilik); salahnya hanya
 * menunda sync otomatis, tidak pernah menghilangkan data, dan tombol manual tidak terpengaruh.
 *
 * Hanya MEMBACA keadaan saat dipanggil (tiap pemicu), tidak mendengarkan perubahan jaringan; lihat batas ini di
 * sync-desain.md bagian H.
 */
class AndroidAutoSyncNetwork(context: Context, private val settings: AutoSyncSettings) : AutoSyncNetwork {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun check(): NetworkCheck = AutoSyncNetworkPolicy.check(settings.wifiOnly, currentKind())

    private fun currentKind(): NetworkKind {
        val manager = connectivity ?: return NetworkKind.METERED
        return try {
            val network = manager.activeNetwork ?: return NetworkKind.NONE
            val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkKind.NONE
            networkKindOf(
                hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                notMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
        } catch (_: SecurityException) {
            // Izin dicabut/dibatasi: tidak bisa menilai, anggap berbayar (aman untuk saklar hanya Wi-Fi).
            NetworkKind.METERED
        }
    }
}

/** Pemetaan murni kemampuan jaringan ke [NetworkKind] (diuji tanpa Android). */
internal fun networkKindOf(hasInternet: Boolean, notMetered: Boolean): NetworkKind = when {
    !hasInternet -> NetworkKind.NONE
    notMetered -> NetworkKind.UNMETERED
    else -> NetworkKind.METERED
}
