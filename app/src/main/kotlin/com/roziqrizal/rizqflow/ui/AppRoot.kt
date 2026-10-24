package com.roziqrizal.rizqflow.ui

import androidx.compose.foundation.layout.Box
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import com.roziqrizal.rizqflow.domain.auth.AppLockService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.roziqrizal.rizqflow.auth.AuthController
import com.roziqrizal.rizqflow.auth.AuthUiState
import com.roziqrizal.rizqflow.domain.auth.AuthProviderType
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.sync.DeviceId
import com.roziqrizal.rizqflow.ui.login.LoginScreen
import com.roziqrizal.rizqflow.ui.login.RegisterScreen
import com.roziqrizal.rizqflow.ui.security.AppLockScreen
import com.roziqrizal.rizqflow.ui.theme.ThemePreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SPLASH_MIN_MILLIS = 3000L

/**
 * Akar tampilan: splash (sistem) lalu halaman masuk, atau langsung menu utama bila sudah punya
 * riwayat masuk. Selama [AuthUiState.Loading] (dan minimal [SPLASH_MIN_MILLIS]) tampil [SplashContent].
 */
@Composable
fun AppRoot(
    controller: AuthController,
    appLock: AppLockService,
    themePreference: ThemePreference,
    showDebugLogin: Boolean,
    quickCatatRequest: Int = 0,
) {
    val state by controller.state.collectAsState()
    var registering by rememberSaveable { mutableStateOf(false) }
    var demo by rememberSaveable { mutableStateOf(false) }
    // Splash ditahan sebentar (seperti Al-Kaukaba) supaya wordmark sempat terlihat; riwayat masuk yang
    // terbaca cepat langsung menimpanya. Catat kilat dari pintasan/tile tidak menunggu.
    var splashDone by rememberSaveable { mutableStateOf(quickCatatRequest > 0) }
    LaunchedEffect(Unit) {
        delay(SPLASH_MIN_MILLIS)
        splashDone = true
    }
    fun openRegister(open: Boolean) {
        controller.clearMessage()
        registering = open
    }
    // Setelah masuk, halaman daftar ditutup; kalau tidak, Keluar akan kembali ke halaman daftar.
    LaunchedEffect(state is AuthUiState.SignedIn) {
        if (state is AuthUiState.SignedIn) registering = false else demo = false
    }
    // Surface menentukan warna teks bawaan (onBackground). Tanpanya teks tanpa warna eksplisit
    // memakai hitam dan tak terbaca di mode gelap.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        when (val s = if (splashDone) state else AuthUiState.Loading) {
            AuthUiState.Loading -> SplashContent()

            is AuthUiState.SignedOut -> if (registering) {
                BackHandler(enabled = s.busy == null) { openRegister(false) }
                RegisterScreen(
                    busy = s.busy,
                    message = s.message,
                    onRegister = controller::register,
                    onBackToLogin = { openRegister(false) },
                )
            } else {
                LoginScreen(
                    busy = s.busy,
                    message = s.message,
                    showDebugLogin = showDebugLogin,
                    onGoogle = { controller.signInWithGoogle(alsoConnectGmail = false) },
                    onGoogleWithGmail = { controller.signInWithGoogle(alsoConnectGmail = true) },
                    onPasswordSignIn = controller::signInWithPassword,
                    onOpenRegister = { openRegister(true) },
                    onDebug = controller::signInDebug,
                )
            }

            // Setiap akun membuka database sendiri; akun baru melewati onboarding dulu.
            is AuthUiState.SignedIn -> {
                // Kunci aplikasi (S19): berlaku untuk aplikasi, bukan akun tertentu. null = belum
                // diperiksa (tanpa PIN tidak pernah null lama, disamakan dengan terkunci supaya
                // konten tidak sempat berkedip sebelum pemeriksaan selesai).
                var locked by rememberSaveable { mutableStateOf<Boolean?>(null) }
                var backgroundedAt by rememberSaveable { mutableStateOf<Long?>(null) }
                val lockScope = rememberCoroutineScope()
                val lifecycleOwner = LocalLifecycleOwner.current

                LaunchedEffect(appLock) { locked = appLock.shouldLock(null) }

                DisposableEffect(lifecycleOwner, appLock) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_STOP -> backgroundedAt = System.currentTimeMillis()
                            Lifecycle.Event.ON_START -> backgroundedAt?.let { at ->
                                lockScope.launch { if (appLock.shouldLock(at)) locked = true }
                            }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                Box(Modifier.fillMaxSize()) {
                    WorkspaceHost(
                        accountId = if (demo) AccountStorage.DEMO_ACCOUNT_ID else s.session.accountId,
                        onDemoFailed = { demo = false },
                    ) { workspace, restoreFromBackup ->
                        // Begitu ruang kerja akun ini siap, teruskan ID token Google (kalau baru saja
                        // masuk, bukan riwayat lama) ke server sekali (Tahap 9). Gagal/offline dibiarkan
                        // diam-diam (tenang, bukan panik) -- app tetap jalan penuh tanpa server ini.
                        val localContext = LocalContext.current
                        LaunchedEffect(workspace, s.pendingGoogleIdToken) {
                            val idToken = controller.consumeGoogleIdToken() ?: return@LaunchedEffect
                            when (val result = workspace.accountRemote.loginWithGoogle(idToken, DeviceId.read(localContext))) {
                                is RemoteResult.Success -> workspace.apiTokenStore.save(result.value.apiToken)
                                is RemoteResult.Failure -> Unit
                            }
                        }
                        // Berganti antara data asli dan demo (atau setelah pulihkan cadangan) memulai
                        // menu utama dari awal (tab, layar terbuka): ruang kerjanya objek yang berbeda.
                        key(workspace) {
                            MainHost(
                                workspace = workspace,
                                onRestoreBackup = restoreFromBackup,
                                appLock = appLock,
                                themePreference = themePreference,
                                account = AccountUi(
                                    identifier = s.session.identifier,
                                    local = s.session.provider == AuthProviderType.PASSWORD,
                                    displayName = s.session.displayName,
                                    gmailConnected = s.session.gmailConnected,
                                    busy = s.busy,
                                    notice = s.notice,
                                ),
                                onConnectGmail = controller::connectGmail,
                                onSignOut = {
                                    demo = false
                                    controller.signOut()
                                },
                                onDismissNotice = controller::dismissNotice,
                                quickCatatRequest = quickCatatRequest,
                                demo = demo,
                                onEnterDemo = { demo = true },
                                onExitDemo = { demo = false },
                            )
                        }
                    }
                    if (locked != false) {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            AppLockScreen(service = appLock, onUnlocked = { locked = false })
                        }
                    }
                }
            }
        }
    }
}
