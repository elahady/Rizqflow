package com.roziqrizal.rizqflow.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPrefsApiTokenStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun newStore(accountId: String = "akun-a") = SharedPrefsApiTokenStore(context, accountId)

    @Test
    fun `belum pernah disimpan mengembalikan null`() {
        assertNull(newStore().read())
    }

    @Test
    fun `token yang disimpan terbaca lagi oleh store baru untuk akun dan konteks yang sama`() {
        newStore().save("token-abc")

        assertEquals("token-abc", newStore().read())
    }

    @Test
    fun `clear menghapus token tersimpan`() {
        val store = newStore()
        store.save("token-abc")

        store.clear()

        assertNull(store.read())
    }

    @Test
    fun `save menimpa token lama`() {
        val store = newStore()
        store.save("token-lama")

        store.save("token-baru")

        assertEquals("token-baru", store.read())
    }

    // --- Token milik satu akun, bukan satu untuk seluruh aplikasi ---

    @Test
    fun `akun lain di perangkat yang sama tidak membaca token akun sebelumnya`() {
        newStore("akun-a").save("token-a")

        // Akun A keluar tanpa membersihkan token; akun B masuk di perangkat yang sama.
        assertNull(newStore("akun-b").read())
    }

    @Test
    fun `tiap akun menyimpan tokennya sendiri`() {
        newStore("akun-a").save("token-a")
        newStore("akun-b").save("token-b")

        assertEquals("token-a", newStore("akun-a").read())
        assertEquals("token-b", newStore("akun-b").read())
    }

    @Test
    fun `clear akun satu tidak menghapus token akun lain`() {
        newStore("akun-a").save("token-a")
        newStore("akun-b").save("token-b")

        newStore("akun-a").clear()

        assertNull(newStore("akun-a").read())
        assertEquals("token-b", newStore("akun-b").read())
    }

    @Test
    fun `token berkas lama satu-untuk-semua-akun tidak dipakai dan dibuang`() {
        // Versi sebelumnya menyimpan satu token untuk seluruh aplikasi; pemiliknya tak diketahui.
        val legacy = context.getSharedPreferences("rizqflow_api_token", Context.MODE_PRIVATE)
        legacy.edit().putString("token", "token-lama-entah-milik-siapa").commit()

        assertNull(newStore("akun-a").read())
        assertNull(newStore("akun-b").read())
        assertNull(legacy.getString("token", null))
    }

    @Test
    fun `pengenal akun kosong ditolak`() {
        assertFailsWith<IllegalArgumentException> { newStore("") }
        assertFailsWith<IllegalArgumentException> { newStore("   ") }
    }
}
