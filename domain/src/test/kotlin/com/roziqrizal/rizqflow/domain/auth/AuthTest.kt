package com.roziqrizal.rizqflow.domain.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AuthTest {

    private val sesi = AuthSession("id-1", "roziq@example.com", "Roziq", AuthProviderType.GOOGLE)

    @Test
    fun `tanpa riwayat masuk pengguna diarahkan ke halaman masuk`() {
        assertEquals(StartDestination.LOGIN, StartRouter.decide(null))
    }

    @Test
    fun `dengan riwayat masuk pengguna langsung ke menu utama`() {
        assertEquals(StartDestination.MAIN, StartRouter.decide(sesi))
    }

    @Test
    fun `sesi baru belum menghubungkan Gmail`() {
        assertFalse(sesi.gmailConnected)
    }

    @Test
    fun `pengenal akun dan email wajib terisi`() {
        assertFailsWith<IllegalArgumentException> { AuthSession(" ", "a@b.c", null, AuthProviderType.GOOGLE) }
        assertFailsWith<IllegalArgumentException> { AuthSession("id", "", null, AuthProviderType.GOOGLE) }
    }

    @Test
    fun `akun lokal wajib punya nama pengguna dan tidak wajib email`() {
        val lokal = AuthSession("id", null, null, AuthProviderType.PASSWORD, username = "roziq")
        assertEquals("roziq", lokal.identifier)
        assertFailsWith<IllegalArgumentException> { AuthSession("id", null, null, AuthProviderType.PASSWORD) }
        assertFailsWith<IllegalArgumentException> { AuthSession("id", null, null, AuthProviderType.PASSWORD, username = " ") }
    }

    @Test
    fun `akun email+sandi server wajib punya email dan memakai email sebagai identitas`() {
        val akun = AuthSession("roziq@example.com", "roziq@example.com", "Roziq", AuthProviderType.EMAIL)
        assertEquals("roziq@example.com", akun.identifier)
        assertFailsWith<IllegalArgumentException> { AuthSession("id", null, null, AuthProviderType.EMAIL) }
    }

    @Test
    fun `akun Google wajib punya email`() {
        assertFailsWith<IllegalArgumentException> { AuthSession("id", null, null, AuthProviderType.GOOGLE) }
        assertEquals("roziq@example.com", sesi.identifier)
    }

    @Test
    fun `akun lokal dengan riwayat masuk langsung ke menu utama`() {
        val lokal = AuthSession("id", null, null, AuthProviderType.PASSWORD, username = "roziq")
        assertEquals(StartDestination.MAIN, StartRouter.decide(lokal))
    }

    @Test
    fun `nama tampilan boleh kosong`() {
        assertEquals(null, AuthSession("id", "a@b.c", null, AuthProviderType.DEBUG).displayName)
    }
}
