package com.roziqrizal.rizqflow.domain.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Konfirmasi tambahan Hapus akun server: PIN benar/salah, kata HAPUS benar/salah, dan tombol aktif/nonaktif. */
class DeleteAccountConfirmationTest {

    private class MemorySecurityStore : SecurityStore {
        var pin: String? = null
        var biometric = false
        var mode = AutoLockMode.IMMEDIATELY
        var attempts = 0
        var lockedUntil: Long? = null

        override suspend fun pinHash() = pin
        override suspend fun savePinHash(hash: String?) { pin = hash }
        override suspend fun biometricEnabled() = biometric
        override suspend fun saveBiometricEnabled(enabled: Boolean) { biometric = enabled }
        override suspend fun autoLockMode() = mode
        override suspend fun saveAutoLockMode(mode: AutoLockMode) { this.mode = mode }
        override suspend fun failedAttempts() = attempts
        override suspend fun saveFailedAttempts(count: Int) { attempts = count }
        override suspend fun lockedUntilMillis() = lockedUntil
        override suspend fun saveLockedUntilMillis(until: Long?) { lockedUntil = until }
    }

    /** Pengolah tiruan: cepat, tanpa PBKDF2 sungguhan. */
    private class FakeHasher : PasswordHasher {
        override fun hash(password: String) = "hash($password)"
        override fun verify(password: String, encoded: String) = encoded == "hash($password)"
    }

    private val store = MemorySecurityStore()
    private var now = 1_000_000L
    private val appLock = AppLockService(store, FakeHasher()) { now }
    private val confirmation = DeleteAccountConfirmation(appLock)

    // ------------------------------------------------------------------ syarat konfirmasi

    @Test
    fun `tanpa kunci aplikasi syaratnya mengetik HAPUS`() {
        assertEquals(DeleteAccountRequirement.TYPED_WORD, runSuspend { confirmation.requirement() })
    }

    @Test
    fun `dengan kunci aplikasi aktif syaratnya PIN`() {
        runSuspend { appLock.setPin("123456") }

        assertEquals(DeleteAccountRequirement.PIN, runSuspend { confirmation.requirement() })
    }

    @Test
    fun `setelah PIN dihapus kembali ke syarat mengetik HAPUS`() {
        runSuspend { appLock.setPin("123456") }
        runSuspend { appLock.clearPin() }

        assertEquals(DeleteAccountRequirement.TYPED_WORD, runSuspend { confirmation.requirement() })
    }

    // ------------------------------------------------------------------ PIN

    @Test
    fun `PIN yang benar lolos`() {
        runSuspend { appLock.setPin("123456") }

        assertEquals(DeleteAccountPinCheck.Verified, runSuspend { confirmation.verifyPin("123456") })
    }

    @Test
    fun `PIN yang salah ditolak dan menyisakan percobaan`() {
        runSuspend { appLock.setPin("123456") }

        val hasil = runSuspend { confirmation.verifyPin("000000") }

        assertEquals(DeleteAccountPinCheck.Wrong(AppLockService.MAX_ATTEMPTS - 1), hasil)
    }

    @Test
    fun `lima kali PIN salah menahan konfirmasi sementara`() {
        runSuspend { appLock.setPin("123456") }
        repeat(AppLockService.MAX_ATTEMPTS - 1) { runSuspend { confirmation.verifyPin("000000") } }

        val terkunci = runSuspend { confirmation.verifyPin("000000") }
        assertIs<DeleteAccountPinCheck.LockedOut>(terkunci)
        assertEquals(now + AppLockService.LOCKOUT_MILLIS, terkunci.untilMillis)

        // Selama masih terkunci, PIN yang benar pun ditolak: mekanisme kunci layar yang sama.
        assertIs<DeleteAccountPinCheck.LockedOut>(runSuspend { confirmation.verifyPin("123456") })
    }

    @Test
    fun `PIN benar setelah salah beberapa kali tetap lolos dan mereset hitungan`() {
        runSuspend { appLock.setPin("123456") }
        runSuspend { confirmation.verifyPin("000000") }
        runSuspend { confirmation.verifyPin("000000") }

        assertEquals(DeleteAccountPinCheck.Verified, runSuspend { confirmation.verifyPin("123456") })
        assertEquals(0, store.attempts)
    }

    // ------------------------------------------------------------------ kata HAPUS

    @Test
    fun `kata HAPUS persis lolos`() {
        assertTrue(DeleteAccountConfirmation.typedWordMatches("HAPUS"))
    }

    @Test
    fun `kata HAPUS dengan huruf kecil atau spasi ditolak`() {
        assertFalse(DeleteAccountConfirmation.typedWordMatches("hapus"))
        assertFalse(DeleteAccountConfirmation.typedWordMatches("Hapus"))
        assertFalse(DeleteAccountConfirmation.typedWordMatches(" HAPUS"))
        assertFalse(DeleteAccountConfirmation.typedWordMatches("HAPUS "))
        assertFalse(DeleteAccountConfirmation.typedWordMatches(""))
        assertFalse(DeleteAccountConfirmation.typedWordMatches("HAPUSS"))
    }

    // ------------------------------------------------------------------ tombol aktif atau nonaktif

    @Test
    fun `tombol nonaktif tanpa PIN benar walau kata sudah diketik`() {
        assertFalse(DeleteAccountConfirmation.canConfirm(DeleteAccountRequirement.PIN, typed = "HAPUS", pinVerified = false))
    }

    @Test
    fun `tombol aktif setelah PIN benar, kata yang diketik tidak lagi dihitung`() {
        assertTrue(DeleteAccountConfirmation.canConfirm(DeleteAccountRequirement.PIN, typed = "", pinVerified = true))
    }

    @Test
    fun `tombol nonaktif tanpa kata HAPUS yang persis`() {
        assertFalse(DeleteAccountConfirmation.canConfirm(DeleteAccountRequirement.TYPED_WORD, typed = "", pinVerified = false))
        assertFalse(DeleteAccountConfirmation.canConfirm(DeleteAccountRequirement.TYPED_WORD, typed = "hapus", pinVerified = false))
        // PIN yang benar tidak membuka tombol saat syaratnya kata: syarat ditentukan kunci aplikasi saat dibuka.
        assertFalse(DeleteAccountConfirmation.canConfirm(DeleteAccountRequirement.TYPED_WORD, typed = "", pinVerified = true))
    }

    @Test
    fun `tombol aktif hanya setelah kata HAPUS persis diketik`() {
        assertTrue(DeleteAccountConfirmation.canConfirm(DeleteAccountRequirement.TYPED_WORD, typed = "HAPUS", pinVerified = false))
    }
}
