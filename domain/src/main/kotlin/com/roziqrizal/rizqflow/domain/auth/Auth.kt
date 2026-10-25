package com.roziqrizal.rizqflow.domain.auth

/**
 * Cara pengguna masuk. [PASSWORD] adalah akun lokal (nama pengguna dan sandi) yang hanya ada di ponsel ini.
 * [EMAIL] adalah akun server (email dan sandi, Tahap 14) yang sama dengan masuk web: token server-nya didapat
 * lewat `AccountRemote.loginWithEmail`. [DEBUG] hanya ada di build debug untuk menguji alur tanpa akun Google.
 */
enum class AuthProviderType { GOOGLE, PASSWORD, EMAIL, DEBUG }

/**
 * Riwayat masuk yang disimpan di ponsel. Tidak menyimpan token atau sandi: sesi hanya penanda
 * bahwa pengguna sudah pernah masuk, supaya berikutnya langsung ke menu utama.
 * [gmailConnected] menandai izin baca Gmail sudah diberikan; aplikasi belum membaca email apa pun.
 * Akun Google dan akun email+sandi punya [email]; akun lokal ([AuthProviderType.PASSWORD]) punya [username] dan tanpa email.
 */
data class AuthSession(
    val accountId: String,
    val email: String?,
    val displayName: String?,
    val provider: AuthProviderType,
    val gmailConnected: Boolean = false,
    val username: String? = null,
) {
    init {
        require(accountId.isNotBlank()) { "Pengenal akun tidak boleh kosong" }
        when (provider) {
            AuthProviderType.GOOGLE, AuthProviderType.EMAIL -> require(!email.isNullOrBlank()) { "Email tidak boleh kosong" }
            AuthProviderType.PASSWORD -> require(!username.isNullOrBlank()) { "Nama pengguna tidak boleh kosong" }
            AuthProviderType.DEBUG -> Unit
        }
    }

    /** Yang ditampilkan sebagai identitas akun: email, atau nama pengguna untuk akun lokal. */
    val identifier: String get() = email?.takeIf { it.isNotBlank() } ?: username ?: accountId
}

/** Penyimpanan sesi. Implementasinya di lapisan aplikasi (SharedPreferences). */
interface SessionStore {
    suspend fun current(): AuthSession?

    suspend fun save(session: AuthSession)

    suspend fun clear()
}

enum class StartDestination { LOGIN, MAIN }

/** Menentukan halaman setelah splash: sudah punya riwayat masuk langsung ke menu utama. */
object StartRouter {
    fun decide(session: AuthSession?): StartDestination =
        if (session == null) StartDestination.LOGIN else StartDestination.MAIN
}

/** Hasil masuk dengan Google. */
sealed interface SignInResult {
    /**
     * [idToken] adalah token Google mentah, cuma untuk diteruskan sekali ke [AccountRemote][com.roziqrizal.rizqflow.domain.sync.AccountRemote]
     * (Tahap 9) dan dilupakan — sengaja tidak masuk [AuthSession] karena itu disimpan lewat [SessionStore].
     */
    data class Success(val session: AuthSession, val idToken: String) : SignInResult

    /** Pengguna menutup dialog pilih akun. Bukan galat, tidak perlu pesan. */
    data object Cancelled : SignInResult

    /** Build ini belum diberi client ID Google (lihat docs/auth-google.md). */
    data object NotConfigured : SignInResult

    data class Failed(val reason: String) : SignInResult
}

/** Hasil meminta izin baca Gmail. */
sealed interface GmailConnectResult {
    data object Connected : GmailConnectResult

    /** Pengguna menolak izin. Aplikasi tetap bisa dipakai tanpa Gmail. */
    data object Denied : GmailConnectResult

    data object NotConfigured : GmailConnectResult

    data class Failed(val reason: String) : GmailConnectResult
}
