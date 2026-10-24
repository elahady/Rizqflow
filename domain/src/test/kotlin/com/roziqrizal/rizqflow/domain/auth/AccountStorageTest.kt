package com.roziqrizal.rizqflow.domain.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccountStorageTest {

    private val shape = Regex("rizqflow-[0-9a-f]{32}\\.db")

    @Test
    fun `akun yang sama selalu mendapat nama berkas yang sama`() {
        assertEquals(AccountStorage.databaseName("roziq@example.com"), AccountStorage.databaseName("roziq@example.com"))
    }

    @Test
    fun `akun berbeda mendapat berkas berbeda sehingga data tidak tercampur`() {
        val nama = listOf("roziq@example.com", "lain@example.com", "b4a00324-cf1f-49a4-b01a-11ab4ec8e88e", "debug").map(AccountStorage::databaseName)
        assertEquals(nama.size, nama.toSet().size)
    }

    @Test
    fun `nama berkas berbentuk aman dan tidak memuat pengenal akun`() {
        listOf("roziq@example.com", "a/b\\c..", "b4a00324-cf1f-49a4-b01a-11ab4ec8e88e", "debug", "  spasi  ", "日本語").forEach {
            val nama = AccountStorage.databaseName(it)
            assertTrue(shape.matches(nama), nama)
            assertFalse(nama.contains(it.trim()), "pengenal tidak boleh muncul di nama berkas: $it")
        }
    }

    @Test
    fun `email tidak bocor ke nama berkas`() {
        assertFalse(AccountStorage.databaseName("roziqrizal881992@gmail.com").contains("roziq"))
    }

    @Test
    fun `pengenal membedakan huruf besar dan kecil`() {
        assertNotEquals(AccountStorage.databaseName("Roziq"), AccountStorage.databaseName("roziq"))
    }

    @Test
    fun `nama berkas tetap stabil antar versi aplikasi`() {
        // Nilai ini tidak boleh berubah: mengubahnya membuat data akun lama seolah hilang.
        assertEquals("rizqflow-0b8e9e995d8d77f1e4770f0f79665aee.db", AccountStorage.databaseName("debug"))
        assertEquals("rizqflow-f8eb8e95c25184708dc678d50035eee4.db", AccountStorage.databaseName("roziq@example.com"))
    }

    @Test
    fun `pengenal kosong ditolak`() {
        assertFailsWith<IllegalArgumentException> { AccountStorage.databaseName("") }
        assertFailsWith<IllegalArgumentException> { AccountStorage.databaseName("   ") }
        assertFailsWith<IllegalArgumentException> { AccountStorage.scopeKey("") }
        assertFailsWith<IllegalArgumentException> { AccountStorage.scopeKey("   ") }
    }

    @Test
    fun `kunci per akun aman, stabil, dan membedakan akun`() {
        val key = AccountStorage.scopeKey("roziq@example.com")

        assertTrue(Regex("[0-9a-f]{32}").matches(key), key)
        assertEquals(key, AccountStorage.scopeKey("roziq@example.com"))
        assertNotEquals(key, AccountStorage.scopeKey("lain@example.com"))
        assertFalse(key.contains("roziq"))
        // Nama database memakai kunci yang sama, jadi dua penyimpanan satu akun selalu searah.
        assertEquals("rizqflow-$key.db", AccountStorage.databaseName("roziq@example.com"))
    }
}
