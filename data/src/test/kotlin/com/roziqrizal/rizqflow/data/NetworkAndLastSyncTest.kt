package com.roziqrizal.rizqflow.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.data.sync.AndroidNetworkChangeSource
import com.roziqrizal.rizqflow.data.sync.SharedPrefsLastSyncStore
import com.roziqrizal.rizqflow.domain.sync.sanitizeLastSync
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sisi Android dari pemicu perubahan jaringan dan waktu sinkron terakhir yang persisten (Tahap 6 sync-desain.md bagian H).
 * Hanya shadow Robolectric: belum membuktikan perilaku `ConnectivityManager` di perangkat sungguhan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkAndLastSyncTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val connectivityShadow get() = shadowOf(context.getSystemService(ConnectivityManager::class.java))

    // ---- AndroidNetworkChangeSource ----

    @Test
    fun `pendengar jaringan terdaftar saat watch dan dilepas saat ditutup`() {
        val source = AndroidNetworkChangeSource(context, log = {})
        assertEquals(0, connectivityShadow.networkCallbacks.size)

        val watch = source.watch { }
        assertEquals(1, connectivityShadow.networkCallbacks.size, "terdaftar hanya selama dipantau")

        watch.close()
        assertEquals(0, connectivityShadow.networkCallbacks.size, "dilepas saat orkestrator ke latar")
    }

    @Test
    fun `perubahan jaringan diteruskan ke pendengar`() {
        var changes = 0
        AndroidNetworkChangeSource(context, log = {}).watch { changes++ }
        val callback = connectivityShadow.networkCallbacks.single()
        val network = ShadowNetwork.newInstance(1)

        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, NetworkCapabilities())

        assertEquals(2, changes)
    }

    @Test
    fun `galat dari pendengar tidak sampai ke thread konektivitas sistem`() {
        val logs = mutableListOf<String>()
        AndroidNetworkChangeSource(context, log = { logs += it }).watch { error("bug pemanggil") }
        val callback = connectivityShadow.networkCallbacks.single()

        callback.onAvailable(ShadowNetwork.newInstance(1))

        assertTrue(logs.single().contains("IllegalStateException"), "hanya nama kelas galat yang dicatat")
    }

    @Test
    fun `menutup dua kali tidak melempar`() {
        val watch = AndroidNetworkChangeSource(context, log = {}).watch { }

        watch.close()
        watch.close()

        assertEquals(0, connectivityShadow.networkCallbacks.size)
    }

    // ---- SharedPrefsLastSyncStore ----

    @Test
    fun `waktu sinkron terakhir bertahan lintas objek seperti setelah proses mati`() {
        assertNull(SharedPrefsLastSyncStore(context, "akun-1").read(), "belum pernah")

        SharedPrefsLastSyncStore(context, "akun-1").write(1_800_000_000_000L)

        assertEquals(1_800_000_000_000L, SharedPrefsLastSyncStore(context, "akun-1").read())
    }

    @Test
    fun `waktu sinkron terakhir per akun, akun lain tidak ikut`() {
        SharedPrefsLastSyncStore(context, "akun-1").write(1_800_000_000_000L)

        assertNull(SharedPrefsLastSyncStore(context, "akun-2").read())
        SharedPrefsLastSyncStore(context, "akun-2").write(1_800_000_500_000L)
        assertEquals(1_800_000_000_000L, SharedPrefsLastSyncStore(context, "akun-1").read())
    }

    @Test
    fun `nilai rusak atau bertipe lain dibaca sebagai belum pernah`() {
        val store = SharedPrefsLastSyncStore(context, "akun-rusak")
        store.write(-5L)
        assertNull(store.read(), "nol atau negatif bukan waktu")

        // Berkas yang sama diisi tipe lain (mis. bug versi lain): tidak boleh melempar.
        val file = "rizqflow_auto_sync_last_" + com.roziqrizal.rizqflow.domain.auth.AccountStorage.scopeKey("akun-rusak")
        context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().putString("last_synced_at_ms", "kemarin").commit()
        assertNull(SharedPrefsLastSyncStore(context, "akun-rusak").read())
    }

    @Test
    fun `waktu tersimpan dari masa depan dijepit saat dipakai, dibaca apa adanya dari penyimpanan`() {
        val store = SharedPrefsLastSyncStore(context, "akun-jam")
        store.write(1_900_000_000_000L)

        assertEquals(1_900_000_000_000L, store.read(), "penyimpanan tidak mengubah apa yang ditulis")
        assertEquals(1_800_000_000_000L, sanitizeLastSync(store.read(), nowMs = 1_800_000_000_000L), "kebijakan jam mundur ada di pemakai")
    }
}
