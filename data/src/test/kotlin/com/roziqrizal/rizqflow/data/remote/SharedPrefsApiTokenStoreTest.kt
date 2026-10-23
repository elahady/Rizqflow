package com.roziqrizal.rizqflow.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPrefsApiTokenStoreTest {

    private fun newStore() = SharedPrefsApiTokenStore(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `belum pernah disimpan mengembalikan null`() {
        assertNull(newStore().read())
    }

    @Test
    fun `token yang disimpan terbaca lagi oleh store baru untuk konteks yang sama`() {
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
}
