package com.roziqrizal.rizqflow.auth

import com.roziqrizal.rizqflow.domain.auth.AccountStore
import com.roziqrizal.rizqflow.domain.auth.AuthProviderType
import com.roziqrizal.rizqflow.domain.auth.AuthSession
import com.roziqrizal.rizqflow.domain.auth.GmailConnectResult
import com.roziqrizal.rizqflow.domain.auth.LocalAccount
import com.roziqrizal.rizqflow.domain.auth.LocalAccountService
import com.roziqrizal.rizqflow.domain.auth.Pbkdf2PasswordHasher
import com.roziqrizal.rizqflow.domain.auth.SessionStore
import com.roziqrizal.rizqflow.domain.auth.SignInResult
import com.roziqrizal.rizqflow.domain.sync.ApiAccount
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthControllerTest {

    private class FakeStore(var stored: AuthSession? = null) : SessionStore {
        override suspend fun current() = stored

        override suspend fun save(session: AuthSession) {
            stored = session
        }

        override suspend fun clear() {
            stored = null
        }
    }

    private class FakeProvider(
        var signIn: suspend () -> SignInResult = { SignInResult.Cancelled },
        var gmail: suspend () -> GmailConnectResult = { GmailConnectResult.Connected },
    ) : AuthProvider {
        override suspend fun signInWithGoogle() = signIn()

        override suspend fun connectGmail() = gmail()
    }

    private class FakeAccounts : AccountStore {
        val accounts = linkedMapOf<String, LocalAccount>()

        override suspend fun find(username: String) = accounts[username]

        override suspend fun add(account: LocalAccount): Boolean {
            if (account.username in accounts) return false
            accounts[account.username] = account
            return true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var counter = 0
    private val accountStore = FakeAccounts()

    // Putaran rendah supaya tes cepat; hash sungguhan tetap dipakai agar alur sandi diuji utuh.
    private val accounts = LocalAccountService(accountStore, Pbkdf2PasswordHasher(iterations = 1_000)) { "acc-${++counter}" }
    private val sesi = AuthSession("g-1", "roziq@example.com", "Roziq", AuthProviderType.GOOGLE)
    private val idToken = "fake-id-token"

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun controller(
        store: FakeStore = FakeStore(),
        provider: FakeProvider = FakeProvider(),
        email: EmailSignIn = { _, _ -> RemoteResult.Failure(RemoteError.Unreachable) },
    ) = AuthController(store, provider, accounts, scope, cpuDispatcher = Dispatchers.Unconfined, emailSignIn = email)

    private val akunServer = ApiAccount(apiToken = "token-server", userId = 9, name = "Roziq", email = "roziq@example.com")

    @Test
    fun `sebelum dimulai status masih memuat sehingga splash tetap tampil`() {
        assertEquals(AuthUiState.Loading, controller().state.value)
    }

    @Test
    fun `tanpa riwayat masuk pengguna melihat halaman masuk`() {
        val c = controller()
        c.start()

        assertEquals(AuthUiState.SignedOut(), c.state.value)
    }

    @Test
    fun `dengan riwayat masuk pengguna langsung ke menu utama`() {
        val c = controller(FakeStore(sesi))
        c.start()

        assertEquals(AuthUiState.SignedIn(sesi), c.state.value)
    }

    @Test
    fun `masuk dengan Google menyimpan riwayat lalu ke menu utama`() {
        val store = FakeStore()
        val c = controller(store, FakeProvider(signIn = { SignInResult.Success(sesi, idToken) }))
        c.start()

        c.signInWithGoogle()

        assertEquals(AuthUiState.SignedIn(sesi, pendingGoogleIdToken = idToken), c.state.value)
        assertEquals(sesi, store.stored)
    }

    @Test
    fun `selama masuk berjalan tombol dinonaktifkan lewat status sibuk`() {
        val menunggu = CompletableDeferred<SignInResult>()
        val c = controller(provider = FakeProvider(signIn = { menunggu.await() }))
        c.start()

        c.signInWithGoogle()
        assertEquals(AuthUiState.SignedOut(busy = AuthBusy.GOOGLE), c.state.value)

        menunggu.complete(SignInResult.Success(sesi, idToken))
        assertIs<AuthUiState.SignedIn>(c.state.value)
    }

    @Test
    fun `ketukan kedua saat masih sibuk diabaikan`() {
        var panggilan = 0
        val menunggu = CompletableDeferred<SignInResult>()
        val c = controller(provider = FakeProvider(signIn = { panggilan++; menunggu.await() }))
        c.start()

        c.signInWithGoogle()
        c.signInWithGoogle()

        assertEquals(1, panggilan)
    }

    @Test
    fun `menutup dialog akun tidak menampilkan pesan`() {
        val c = controller(provider = FakeProvider(signIn = { SignInResult.Cancelled }))
        c.start()

        c.signInWithGoogle()

        assertEquals(AuthUiState.SignedOut(), c.state.value)
    }

    @Test
    fun `build tanpa client ID menampilkan pesan belum disiapkan`() {
        val c = controller(provider = FakeProvider(signIn = { SignInResult.NotConfigured }))
        c.start()

        c.signInWithGoogle()

        assertEquals(AuthUiState.SignedOut(message = AuthMessage.NOT_CONFIGURED), c.state.value)
    }

    @Test
    fun `gagal masuk menampilkan pesan lembut dan tidak menyimpan apa pun`() {
        val store = FakeStore()
        val c = controller(store, FakeProvider(signIn = { SignInResult.Failed("boom") }))
        c.start()

        c.signInWithGoogle()

        assertEquals(AuthUiState.SignedOut(message = AuthMessage.FAILED), c.state.value)
        assertNull(store.stored)
    }

    @Test
    fun `masuk sekaligus Gmail menandai Gmail terhubung`() {
        val store = FakeStore()
        val c = controller(store, FakeProvider(signIn = { SignInResult.Success(sesi, idToken) }))
        c.start()

        c.signInWithGoogle(alsoConnectGmail = true)

        val masuk = assertIs<AuthUiState.SignedIn>(c.state.value)
        assertTrue(masuk.session.gmailConnected)
        assertTrue(store.stored!!.gmailConnected)
        assertNull(masuk.notice)
    }

    @Test
    fun `izin Gmail ditolak tetap masuk tanpa Gmail dan ada catatan`() {
        val c = controller(provider = FakeProvider(signIn = { SignInResult.Success(sesi, idToken) }, gmail = { GmailConnectResult.Denied }))
        c.start()

        c.signInWithGoogle(alsoConnectGmail = true)

        val masuk = assertIs<AuthUiState.SignedIn>(c.state.value)
        assertFalse(masuk.session.gmailConnected)
        assertEquals(AuthMessage.GMAIL_DENIED, masuk.notice)
    }

    @Test
    fun `menghubungkan Gmail belakangan dari menu utama`() {
        val store = FakeStore(sesi)
        val c = controller(store, FakeProvider(gmail = { GmailConnectResult.Connected }))
        c.start()

        c.connectGmail()

        assertTrue((c.state.value as AuthUiState.SignedIn).session.gmailConnected)
        assertTrue(store.stored!!.gmailConnected)
    }

    @Test
    fun `Gmail yang sudah terhubung tidak diminta lagi`() {
        var panggilan = 0
        val c = controller(FakeStore(sesi.copy(gmailConnected = true)), FakeProvider(gmail = { panggilan++; GmailConnectResult.Connected }))
        c.start()

        c.connectGmail()

        assertEquals(0, panggilan)
    }

    @Test
    fun `catatan dihapus setelah dibaca`() {
        val c = controller(provider = FakeProvider(signIn = { SignInResult.Success(sesi, idToken) }, gmail = { GmailConnectResult.Denied }))
        c.start()
        c.signInWithGoogle(alsoConnectGmail = true)

        c.dismissNotice()

        assertNull((c.state.value as AuthUiState.SignedIn).notice)
    }

    @Test
    fun `keluar menghapus riwayat sehingga berikutnya kembali ke halaman masuk`() {
        val store = FakeStore(sesi)
        val c = controller(store)
        c.start()

        c.signOut()

        assertEquals(AuthUiState.SignedOut(), c.state.value)
        assertNull(store.stored)
    }

    @Test
    fun `masuk uji menyimpan sesi debug`() {
        val store = FakeStore()
        val c = controller(store)
        c.start()

        c.signInDebug()

        assertEquals(AuthProviderType.DEBUG, store.stored!!.provider)
        assertIs<AuthUiState.SignedIn>(c.state.value)
    }

    // ---- Akun lokal: nama pengguna dan sandi ----

    private fun daftarLalu(c: AuthController) {
        c.register("Roziq", "roziq", "sandi-panjang-1", "sandi-panjang-1")
        c.signOut()
    }

    @Test
    fun `daftar membuat akun lalu langsung ke menu utama dan menyimpan riwayat`() {
        val store = FakeStore()
        val c = controller(store)
        c.start()

        c.register("Roziq", "Roziq", "sandi-panjang-1", "sandi-panjang-1")

        val masuk = assertIs<AuthUiState.SignedIn>(c.state.value)
        assertEquals(AuthProviderType.PASSWORD, masuk.session.provider)
        assertEquals("roziq", masuk.session.username)
        assertEquals(masuk.session, store.stored)
    }

    @Test
    fun `sandi tidak pernah tersimpan di riwayat maupun di penyimpanan akun`() {
        val store = FakeStore()
        val c = controller(store)
        c.start()

        c.register("Roziq", "roziq", "sandi-panjang-1", "sandi-panjang-1")

        assertFalse("sandi-panjang-1" in store.stored.toString())
        assertFalse("sandi-panjang-1" in accountStore.accounts.values.toString())
    }

    @Test
    fun `daftar dengan nama pengguna yang sudah ada menampilkan pesan dan tidak masuk`() {
        val store = FakeStore()
        val c = controller(store)
        c.start()
        daftarLalu(c)

        c.register("Lain", "ROZIQ", "sandi-lain-9999", "sandi-lain-9999")

        assertEquals(AuthUiState.SignedOut(message = AuthMessage.USERNAME_TAKEN), c.state.value)
        assertNull(store.stored)
    }

    @Test
    fun `daftar dengan isian tidak sah ditolak sebagai lapis pengaman`() {
        val c = controller()
        c.start()

        c.register("", "a", "123", "456")

        assertEquals(AuthUiState.SignedOut(message = AuthMessage.INVALID_INPUT), c.state.value)
        assertTrue(accountStore.accounts.isEmpty())
    }

    @Test
    fun `masuk dengan sandi yang benar ke menu utama`() {
        val store = FakeStore()
        val c = controller(store)
        c.start()
        daftarLalu(c)

        c.signInWithPassword("Roziq", "sandi-panjang-1")

        val masuk = assertIs<AuthUiState.SignedIn>(c.state.value)
        assertEquals("roziq", masuk.session.username)
        assertEquals(masuk.session, store.stored)
    }

    @Test
    fun `sandi salah menampilkan pesan yang sama dengan nama pengguna tak dikenal`() {
        val c = controller()
        c.start()
        daftarLalu(c)

        c.signInWithPassword("roziq", "salah-total-1")
        val salah = c.state.value
        c.signInWithPassword("tidak-ada", "sandi-panjang-1")

        assertEquals(AuthUiState.SignedOut(message = AuthMessage.WRONG_CREDENTIALS), salah)
        assertEquals(salah, c.state.value)
    }

    @Test
    fun `isian kosong tidak sampai ke pengecekan sandi`() {
        val c = controller()
        c.start()

        c.signInWithPassword("  ", "apa-saja")
        assertEquals(AuthUiState.SignedOut(message = AuthMessage.WRONG_CREDENTIALS), c.state.value)

        c.signInWithPassword("roziq", "")
        assertEquals(AuthUiState.SignedOut(message = AuthMessage.WRONG_CREDENTIALS), c.state.value)
    }

    @Test
    fun `selama sandi diperiksa tombol sibuk dan ketukan kedua diabaikan`() {
        val gerbang = CompletableDeferred<Unit>()
        val menunggu = object : AccountStore {
            override suspend fun find(username: String): LocalAccount? {
                gerbang.await()
                return null
            }

            override suspend fun add(account: LocalAccount) = true
        }
        val lambat = LocalAccountService(menunggu, Pbkdf2PasswordHasher(iterations = 1_000)) { "x" }
        val c = AuthController(FakeStore(), FakeProvider(), lambat, scope, Dispatchers.Unconfined)
        c.start()

        c.signInWithPassword("roziq", "sandi-panjang-1")
        assertEquals(AuthUiState.SignedOut(busy = AuthBusy.PASSWORD), c.state.value)
        c.register("Roziq", "roziq", "sandi-panjang-1", "sandi-panjang-1")
        assertEquals(AuthUiState.SignedOut(busy = AuthBusy.PASSWORD), c.state.value)

        gerbang.complete(Unit)
        assertEquals(AuthUiState.SignedOut(message = AuthMessage.WRONG_CREDENTIALS), c.state.value)
    }

    @Test
    fun `akun tetap ada setelah keluar sehingga bisa masuk lagi`() {
        val c = controller()
        c.start()
        daftarLalu(c)
        assertEquals(AuthUiState.SignedOut(), c.state.value)

        c.signInWithPassword("roziq", "sandi-panjang-1")

        assertIs<AuthUiState.SignedIn>(c.state.value)
    }

    @Test
    fun `akun lokal dengan riwayat masuk langsung ke menu utama saat aplikasi dibuka`() {
        val sesiLokal = AuthSession("acc-1", null, "Roziq", AuthProviderType.PASSWORD, username = "roziq")
        val c = controller(FakeStore(sesiLokal))

        c.start()

        assertEquals(AuthUiState.SignedIn(sesiLokal), c.state.value)
    }

    @Test
    fun `membersihkan pesan saat pindah halaman`() {
        val c = controller()
        c.start()
        c.signInWithPassword("roziq", "salah-total-1")
        assertEquals(AuthUiState.SignedOut(message = AuthMessage.WRONG_CREDENTIALS), c.state.value)

        c.clearMessage()

        assertEquals(AuthUiState.SignedOut(), c.state.value)
    }

    @Test
    fun `masuk dengan Google tetap bisa saat akun lokal ada`() {
        val c = controller(provider = FakeProvider(signIn = { SignInResult.Success(sesi, idToken) }))
        c.start()
        daftarLalu(c)

        c.signInWithGoogle()

        assertEquals(AuthUiState.SignedIn(sesi, pendingGoogleIdToken = idToken), c.state.value)
    }

    @Test
    fun `consumeGoogleIdToken mengambil sekali lalu null`() {
        val c = controller(provider = FakeProvider(signIn = { SignInResult.Success(sesi, idToken) }))
        c.start()
        c.signInWithGoogle()

        assertEquals(idToken, c.consumeGoogleIdToken())
        assertNull(c.consumeGoogleIdToken())
        assertNull((c.state.value as AuthUiState.SignedIn).pendingGoogleIdToken)
    }

    @Test
    fun `ID token yang tiba saat ruang kerja demo aktif dibuang, tidak diteruskan dan tidak tertinggal`() {
        val c = controller(provider = FakeProvider(signIn = { SignInResult.Success(sesi, idToken) }))
        c.start()
        c.signInWithGoogle()

        // Ruang kerja aktifnya milik akun demo: token akun asli tidak boleh sampai ke berkas token akun demo.
        assertNull(c.consumeGoogleIdToken(forDemoWorkspace = true))

        assertNull((c.state.value as AuthUiState.SignedIn).pendingGoogleIdToken, "dibersihkan, tidak menunggu giliran")
        assertNull(c.consumeGoogleIdToken(), "tidak terkirim belakangan ke ruang kerja yang salah")
    }

    @Test
    fun `ID token diteruskan seperti biasa bila ruang kerja bukan demo`() {
        val c = controller(provider = FakeProvider(signIn = { SignInResult.Success(sesi, idToken) }))
        c.start()
        c.signInWithGoogle()

        assertEquals(idToken, c.consumeGoogleIdToken(forDemoWorkspace = false))
    }

    @Test
    fun `riwayat masuk lama tidak punya token tertunda`() {
        val c = controller(FakeStore(sesi))
        c.start()

        assertNull(c.consumeGoogleIdToken())
    }

    // --- Sambungkan ulang ke server (Tahap 12) ---

    @Test
    fun `sambungkan ulang memakai akun yang sama dan menyerahkan ID token ke jalur masuk server`() {
        val store = FakeStore(sesi)
        val c = controller(store, FakeProvider(signIn = { SignInResult.Success(sesi, idToken) }))
        c.start()

        c.reconnectServer()

        val state = c.state.value as AuthUiState.SignedIn
        assertEquals(sesi, state.session, "sesi lokal tidak berganti")
        assertNull(state.busy)
        assertNull(state.notice)
        assertEquals(idToken, c.consumeGoogleIdToken(), "ID token sekali pakai untuk AccountRemote.loginWithGoogle")
        assertEquals(sesi, store.stored)
    }

    @Test
    fun `selama dialog Google terbuka status sibuk dan ketukan kedua diabaikan`() {
        var panggilan = 0
        val menunggu = CompletableDeferred<SignInResult>()
        val c = controller(FakeStore(sesi), FakeProvider(signIn = { panggilan++; menunggu.await() }))
        c.start()

        c.reconnectServer()
        c.reconnectServer()

        assertEquals(AuthBusy.GOOGLE, (c.state.value as AuthUiState.SignedIn).busy)
        assertEquals(1, panggilan)
        menunggu.complete(SignInResult.Success(sesi, idToken))
        assertNull((c.state.value as AuthUiState.SignedIn).busy)
    }

    @Test
    fun `akun Google yang berbeda ditolak dan tidak ada ID token yang diteruskan`() {
        val lain = AuthSession("g-2", "lain@example.com", "Lain", AuthProviderType.GOOGLE)
        val c = controller(FakeStore(sesi), FakeProvider(signIn = { SignInResult.Success(lain, idToken) }))
        c.start()

        c.reconnectServer()

        val state = c.state.value as AuthUiState.SignedIn
        assertEquals(sesi, state.session)
        assertEquals(AuthMessage.RECONNECT_WRONG_ACCOUNT, state.notice)
        assertNull(state.pendingGoogleIdToken)
        assertNull(state.busy)
    }

    @Test
    fun `menutup dialog akun saat sambung ulang kembali seperti semula tanpa pesan`() {
        val c = controller(FakeStore(sesi), FakeProvider(signIn = { SignInResult.Cancelled }))
        c.start()

        c.reconnectServer()

        assertEquals(AuthUiState.SignedIn(sesi), c.state.value)
    }

    @Test
    fun `sambung ulang yang gagal memberi pesan lembut dan tidak macet`() {
        val c = controller(FakeStore(sesi), FakeProvider(signIn = { SignInResult.Failed("x") }))
        c.start()

        c.reconnectServer()

        assertEquals(AuthUiState.SignedIn(sesi, notice = AuthMessage.FAILED), c.state.value)
        // Bisa dicoba lagi.
        c.reconnectServer()
        assertNull((c.state.value as AuthUiState.SignedIn).busy)
    }

    @Test
    fun `build tanpa client ID memberi pesan belum disiapkan saat sambung ulang`() {
        val c = controller(FakeStore(sesi), FakeProvider(signIn = { SignInResult.NotConfigured }))
        c.start()

        c.reconnectServer()

        assertEquals(AuthUiState.SignedIn(sesi, notice = AuthMessage.NOT_CONFIGURED), c.state.value)
    }

    @Test
    fun `akun lokal dan akun uji tidak bisa menyambung ulang ke server`() {
        var panggilan = 0
        val provider = FakeProvider(signIn = { panggilan++; SignInResult.Success(sesi, idToken) })
        for (jenis in listOf(AuthProviderType.PASSWORD, AuthProviderType.DEBUG)) {
            val bukanGoogle = AuthSession("akun-1", null, "Lokal", jenis, username = "lokal")
            val c = controller(FakeStore(bukanGoogle), provider)
            c.start()

            c.reconnectServer()

            assertEquals(AuthUiState.SignedIn(bukanGoogle), c.state.value)
        }
        assertEquals(0, panggilan)
    }

    @Test
    fun `keluar saat dialog Google terbuka tidak dihidupkan lagi oleh hasil yang datang terlambat`() {
        val menunggu = CompletableDeferred<SignInResult>()
        val c = controller(FakeStore(sesi), FakeProvider(signIn = { menunggu.await() }))
        c.start()
        c.reconnectServer()

        c.signOut()
        menunggu.complete(SignInResult.Success(sesi, idToken))

        assertEquals(AuthUiState.SignedOut(), c.state.value)
    }

    // --- Masuk email+sandi ke server (Tahap 14) ---

    @Test
    fun `masuk email yang diterima server membuat sesi email dan menitipkan token ke ruang kerja`() {
        val store = FakeStore()
        val c = controller(store, email = { _, _ -> RemoteResult.Success(akunServer) })
        c.start()

        c.signInWithEmail("roziq@example.com", "kopi-susu")

        val session = AuthSession("roziq@example.com", "roziq@example.com", "Roziq", AuthProviderType.EMAIL)
        assertEquals(AuthUiState.SignedIn(session, pendingServerAccount = akunServer), c.state.value)
        assertEquals(session, store.stored)
    }

    @Test
    fun `token email hanya diambil sekali dan tidak dikirim ke ruang kerja demo`() {
        val c = controller(email = { _, _ -> RemoteResult.Success(akunServer) })
        c.start()
        c.signInWithEmail("roziq@example.com", "kopi-susu")

        assertEquals(null, c.consumePendingServerAccount(forDemoWorkspace = true), "demo: token dibuang")
        assertEquals(null, c.consumePendingServerAccount(), "sudah dibuang di atas")
    }

    @Test
    fun `token email diambil sekali untuk ruang kerja akun ini`() {
        val c = controller(email = { _, _ -> RemoteResult.Success(akunServer) })
        c.start()
        c.signInWithEmail("roziq@example.com", "kopi-susu")

        assertEquals(akunServer, c.consumePendingServerAccount())
        assertEquals(null, c.consumePendingServerAccount())
    }

    @Test
    fun `sandi salah ditolak server tidak membuat sesi dan menampilkan pesan server apa adanya`() {
        val store = FakeStore()
        val c = controller(store, email = { _, _ -> RemoteResult.Failure(RemoteError.Rejected("Email atau kata sandi salah.")) })
        c.start()

        c.signInWithEmail("roziq@example.com", "salah")

        assertEquals(
            AuthUiState.SignedOut(message = AuthMessage.SERVER_REJECTED, detail = "Email atau kata sandi salah."),
            c.state.value,
        )
        assertEquals(null, store.stored)
    }

    @Test
    fun `server tidak terjangkau memberi pesan lembut dan tidak membuat sesi`() {
        val store = FakeStore()
        val c = controller(store)
        c.start()

        c.signInWithEmail("roziq@example.com", "kopi-susu")

        assertEquals(AuthUiState.SignedOut(message = AuthMessage.SERVER_UNREACHABLE), c.state.value)
        assertEquals(null, store.stored)
    }

    @Test
    fun `galat lain dari server dilaporkan sebagai gagal umum`() {
        val c = controller(email = { _, _ -> RemoteResult.Failure(RemoteError.Unexpected(500)) })
        c.start()

        c.signInWithEmail("roziq@example.com", "kopi-susu")

        assertEquals(AuthUiState.SignedOut(message = AuthMessage.FAILED), c.state.value)
    }

    @Test
    fun `isian email atau sandi kosong tidak memanggil server`() {
        var panggilan = 0
        val c = controller(email = { _, _ -> panggilan++; RemoteResult.Success(akunServer) })
        c.start()

        c.signInWithEmail("  ", "kopi-susu")
        c.signInWithEmail("roziq@example.com", "")

        assertEquals(0, panggilan)
        assertEquals(AuthUiState.SignedOut(message = AuthMessage.WRONG_CREDENTIALS), c.state.value)
    }

    @Test
    fun `ketukan kedua masuk email saat masih sibuk diabaikan`() {
        var panggilan = 0
        val menunggu = CompletableDeferred<RemoteResult<ApiAccount>>()
        val c = controller(email = { _, _ -> panggilan++; menunggu.await() })
        c.start()

        c.signInWithEmail("roziq@example.com", "kopi-susu")
        c.signInWithEmail("roziq@example.com", "kopi-susu")

        assertEquals(1, panggilan)
        assertEquals(AuthUiState.SignedOut(busy = AuthBusy.EMAIL), c.state.value)
    }
}
