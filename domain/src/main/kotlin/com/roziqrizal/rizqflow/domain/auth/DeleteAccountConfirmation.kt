package com.roziqrizal.rizqflow.domain.auth

/** Cara konfirmasi yang diminta sebelum Hapus akun server (Tahap 9, keputusan pemilik 2026-10-24). */
enum class DeleteAccountRequirement {
    /** Kunci aplikasi (PIN) aktif: PIN yang benar harus dimasukkan dulu. */
    PIN,

    /** Kunci aplikasi tidak aktif: pengguna mengetik [DeleteAccountConfirmation.CONFIRM_WORD]. */
    TYPED_WORD,
}

/** Hasil satu percobaan PIN di layar Hapus akun; penghitung salah dan penguncian sementara sama dengan layar kunci. */
sealed interface DeleteAccountPinCheck {
    data object Verified : DeleteAccountPinCheck

    data class Wrong(val attemptsLeft: Int) : DeleteAccountPinCheck

    data class LockedOut(val untilMillis: Long) : DeleteAccountPinCheck
}

/**
 * Konfirmasi tambahan sebelum Hapus akun server. Bila kunci aplikasi aktif, pengguna harus memasukkan PIN
 * yang benar lewat [AppLockService.verifyPin] (memakai ulang mekanisme PIN yang sudah ada, bukan sistem baru).
 * Bila tidak aktif, ia mengetik [CONFIRM_WORD] persis. Kunci aplikasi berlaku untuk aplikasi, bukan satu
 * akun, sehingga "aktif di akun itu" berarti PIN aplikasi sedang terpasang.
 */
class DeleteAccountConfirmation(private val appLock: AppLockService) {

    suspend fun requirement(): DeleteAccountRequirement =
        if (appLock.isPinSet()) DeleteAccountRequirement.PIN else DeleteAccountRequirement.TYPED_WORD

    suspend fun verifyPin(pin: String): DeleteAccountPinCheck = when (val result = appLock.verifyPin(pin)) {
        UnlockResult.Success -> DeleteAccountPinCheck.Verified
        is UnlockResult.WrongPin -> DeleteAccountPinCheck.Wrong(result.attemptsLeft)
        is UnlockResult.LockedOut -> DeleteAccountPinCheck.LockedOut(result.untilMillis)
    }

    companion object {
        /** Kata konfirmasi; dibandingkan persis (huruf besar, tanpa pemangkasan spasi). */
        const val CONFIRM_WORD = "HAPUS"

        fun typedWordMatches(typed: String): Boolean = typed == CONFIRM_WORD

        /** Tombol Hapus akun hanya aktif bila syarat [requirement] sudah terpenuhi. */
        fun canConfirm(requirement: DeleteAccountRequirement, typed: String, pinVerified: Boolean): Boolean = when (requirement) {
            DeleteAccountRequirement.PIN -> pinVerified
            DeleteAccountRequirement.TYPED_WORD -> typedWordMatches(typed)
        }
    }
}
