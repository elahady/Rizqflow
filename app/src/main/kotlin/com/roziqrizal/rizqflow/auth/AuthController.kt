package com.roziqrizal.rizqflow.auth

import com.roziqrizal.rizqflow.domain.auth.AuthProviderType
import com.roziqrizal.rizqflow.domain.auth.AuthSession
import com.roziqrizal.rizqflow.domain.auth.GmailConnectResult
import com.roziqrizal.rizqflow.domain.auth.LocalAccountService
import com.roziqrizal.rizqflow.domain.auth.PasswordSignInResult
import com.roziqrizal.rizqflow.domain.auth.RegisterResult
import com.roziqrizal.rizqflow.domain.auth.SessionStore
import com.roziqrizal.rizqflow.domain.auth.SignInResult
import com.roziqrizal.rizqflow.domain.auth.StartDestination
import com.roziqrizal.rizqflow.domain.auth.StartRouter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sumber izin Google. Implementasi nyata ada di [GoogleAuthProvider]. */
interface AuthProvider {
    suspend fun signInWithGoogle(): SignInResult

    suspend fun connectGmail(): GmailConnectResult
}

/** Pekerjaan yang sedang berjalan; tombol dinonaktifkan selama itu. */
enum class AuthBusy { GOOGLE, GMAIL, PASSWORD }

/** Pesan lembut untuk pengguna; tidak pernah menghalangi memakai aplikasi. */
enum class AuthMessage {
    NOT_CONFIGURED,
    FAILED,
    GMAIL_DENIED,
    GMAIL_UNAVAILABLE,

    /** Nama pengguna tidak ada atau sandi salah; sengaja tidak dibedakan. */
    WRONG_CREDENTIALS,
    USERNAME_TAKEN,

    /** Isian tidak lolos aturan yang seharusnya sudah dicek layar; lapis pengaman. */
    INVALID_INPUT,
}

sealed interface AuthUiState {
    /** Membaca riwayat masuk; splash masih tampil. */
    data object Loading : AuthUiState

    data class SignedOut(val busy: AuthBusy? = null, val message: AuthMessage? = null) : AuthUiState

    /**
     * [notice] ditampilkan sekali di menu utama, misalnya izin Gmail ditolak saat masuk.
     * [pendingGoogleIdToken] cuma terisi sesaat sehabis masuk Google baru (bukan riwayat lama yang
     * dimuat ulang): diambil-dan-dihapus sekali oleh pemanggil (lihat [consumeGoogleIdToken]) untuk
     * diteruskan ke `AccountRemote.loginWithGoogle` (Tahap 9) begitu ruang kerja akunnya terbuka.
     */
    data class SignedIn(
        val session: AuthSession,
        val notice: AuthMessage? = null,
        val busy: AuthBusy? = null,
        val pendingGoogleIdToken: String? = null,
    ) : AuthUiState
}

/**
 * Alur splash, masuk, dan menu utama. Tidak memakai kelas Android sehingga bisa dites di JVM.
 * Sesi yang tersimpan berarti langsung ke menu utama ([StartRouter]).
 *
 * Hash sandi sengaja lambat (ratusan milidetik), jadi dijalankan di [cpuDispatcher], bukan di thread utama.
 */
class AuthController(
    private val store: SessionStore,
    private val provider: AuthProvider,
    private val accounts: LocalAccountService,
    private val scope: CoroutineScope,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    /** Membaca riwayat masuk. Dipanggil sekali saat aplikasi dibuka. */
    fun start() {
        scope.launch {
            val session = store.current()
            _state.value = when (StartRouter.decide(session)) {
                StartDestination.MAIN -> AuthUiState.SignedIn(requireNotNull(session))
                StartDestination.LOGIN -> AuthUiState.SignedOut()
            }
        }
    }

    /** Masuk dengan Google; bila [alsoConnectGmail], sekalian meminta izin baca Gmail. */
    fun signInWithGoogle(alsoConnectGmail: Boolean = false) {
        if (_state.value !is AuthUiState.SignedOut || (_state.value as AuthUiState.SignedOut).busy != null) return
        _state.value = AuthUiState.SignedOut(busy = if (alsoConnectGmail) AuthBusy.GMAIL else AuthBusy.GOOGLE)
        scope.launch {
            when (val result = provider.signInWithGoogle()) {
                is SignInResult.Success -> {
                    var session = result.session
                    var notice: AuthMessage? = null
                    if (alsoConnectGmail) {
                        when (provider.connectGmail()) {
                            GmailConnectResult.Connected -> session = session.copy(gmailConnected = true)
                            GmailConnectResult.Denied -> notice = AuthMessage.GMAIL_DENIED
                            else -> notice = AuthMessage.GMAIL_UNAVAILABLE
                        }
                    }
                    store.save(session)
                    _state.value = AuthUiState.SignedIn(session, notice, pendingGoogleIdToken = result.idToken)
                }

                SignInResult.Cancelled -> _state.value = AuthUiState.SignedOut()
                SignInResult.NotConfigured -> _state.value = AuthUiState.SignedOut(message = AuthMessage.NOT_CONFIGURED)
                is SignInResult.Failed -> _state.value = AuthUiState.SignedOut(message = AuthMessage.FAILED)
            }
        }
    }

    /** Masuk dengan akun lokal (nama pengguna dan sandi). */
    fun signInWithPassword(username: String, password: String) {
        if (!isIdle()) return
        if (username.isBlank() || password.isEmpty()) {
            _state.value = AuthUiState.SignedOut(message = AuthMessage.WRONG_CREDENTIALS)
            return
        }
        _state.value = AuthUiState.SignedOut(busy = AuthBusy.PASSWORD)
        scope.launch {
            when (val result = withContext(cpuDispatcher) { accounts.signIn(username, password) }) {
                is PasswordSignInResult.Success -> {
                    store.save(result.session)
                    _state.value = AuthUiState.SignedIn(result.session)
                }

                PasswordSignInResult.WrongCredentials ->
                    _state.value = AuthUiState.SignedOut(message = AuthMessage.WRONG_CREDENTIALS)
            }
        }
    }

    /** Membuat akun lokal lalu langsung masuk. Isian sudah dicek layar; di sini dicek ulang. */
    fun register(displayName: String, username: String, password: String, confirmation: String) {
        if (!isIdle()) return
        _state.value = AuthUiState.SignedOut(busy = AuthBusy.PASSWORD)
        scope.launch {
            val result = withContext(cpuDispatcher) { accounts.register(displayName, username, password, confirmation) }
            when (result) {
                is RegisterResult.Success -> {
                    store.save(result.session)
                    _state.value = AuthUiState.SignedIn(result.session)
                }

                RegisterResult.UsernameTaken -> _state.value = AuthUiState.SignedOut(message = AuthMessage.USERNAME_TAKEN)
                is RegisterResult.Invalid -> _state.value = AuthUiState.SignedOut(message = AuthMessage.INVALID_INPUT)
            }
        }
    }

    /** Menghapus pesan di halaman masuk, misalnya saat pindah ke halaman daftar. */
    fun clearMessage() {
        val current = _state.value as? AuthUiState.SignedOut ?: return
        if (current.busy == null && current.message != null) _state.value = AuthUiState.SignedOut()
    }

    private fun isIdle(): Boolean = (_state.value as? AuthUiState.SignedOut)?.busy == null && _state.value is AuthUiState.SignedOut

    /** Menghubungkan Gmail belakangan, dari menu utama. */
    fun connectGmail() {
        val current = _state.value as? AuthUiState.SignedIn ?: return
        if (current.busy != null || current.session.gmailConnected) return
        _state.value = current.copy(busy = AuthBusy.GMAIL, notice = null)
        scope.launch {
            _state.value = when (provider.connectGmail()) {
                GmailConnectResult.Connected -> {
                    val updated = current.session.copy(gmailConnected = true)
                    store.save(updated)
                    AuthUiState.SignedIn(updated)
                }

                GmailConnectResult.Denied -> AuthUiState.SignedIn(current.session, AuthMessage.GMAIL_DENIED)
                else -> AuthUiState.SignedIn(current.session, AuthMessage.GMAIL_UNAVAILABLE)
            }
        }
    }

    /** Masuk uji tanpa akun Google. Hanya dipanggil dari tombol yang muncul di build debug. */
    fun signInDebug() {
        if (_state.value !is AuthUiState.SignedOut) return
        scope.launch {
            val session = AuthSession("debug", "uji@rizqflow.local", "Pengguna uji", AuthProviderType.DEBUG)
            store.save(session)
            _state.value = AuthUiState.SignedIn(session)
        }
    }

    fun dismissNotice() {
        val current = _state.value as? AuthUiState.SignedIn ?: return
        _state.value = current.copy(notice = null)
    }

    /**
     * Ambil-dan-hapus [AuthUiState.SignedIn.pendingGoogleIdToken] sekali: dipanggil begitu ruang
     * kerja akun ini terbuka dan siap meneruskannya ke `AccountRemote.loginWithGoogle` (Tahap 9).
     * Null di panggilan berikutnya (riwayat masuk lama, atau sudah pernah diambil) supaya tidak
     * terkirim ulang tiap rekomposisi.
     */
    fun consumeGoogleIdToken(): String? {
        val current = _state.value as? AuthUiState.SignedIn ?: return null
        val token = current.pendingGoogleIdToken ?: return null
        _state.value = current.copy(pendingGoogleIdToken = null)
        return token
    }

    fun signOut() {
        scope.launch {
            store.clear()
            _state.value = AuthUiState.SignedOut()
        }
    }
}
