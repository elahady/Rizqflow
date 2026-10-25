package com.roziqrizal.rizqflow

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.roziqrizal.rizqflow.auth.AuthController
import com.roziqrizal.rizqflow.auth.GoogleAuthProvider
import com.roziqrizal.rizqflow.auth.GoogleClientId
import com.roziqrizal.rizqflow.auth.SharedPrefsAccountStore
import com.roziqrizal.rizqflow.auth.SharedPrefsSecurityStore
import com.roziqrizal.rizqflow.auth.SharedPrefsSessionStore
import com.roziqrizal.rizqflow.domain.auth.AppLockService
import com.roziqrizal.rizqflow.domain.auth.LocalAccountService
import com.roziqrizal.rizqflow.domain.auth.Pbkdf2PasswordHasher
import com.roziqrizal.rizqflow.sync.DeviceId
import com.roziqrizal.rizqflow.ui.AppRoot
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.roziqrizal.rizqflow.ui.theme.RizqflowTheme
import com.roziqrizal.rizqflow.billing.PurchaseStore
import com.roziqrizal.rizqflow.domain.entitlement.LivePlanEntitlements
import com.roziqrizal.rizqflow.ui.theme.ThemePreference
import com.roziqrizal.rizqflow.ui.theme.effective
import com.roziqrizal.rizqflow.ui.theme.resolveDarkTheme
import java.util.UUID

/**
 * Titik masuk. Alur: splash sistem, lalu halaman masuk (bila belum pernah masuk) atau langsung
 * menu utama (bila sudah punya riwayat masuk).
 *
 * Target SDK 35 ke atas memaksa tampilan edge-to-edge; Scaffold di RizqflowApp yang menghormati
 * inset sistem. Warna ikon bar sistem mengikuti tema terang atau gelap (`enableEdgeToEdge`).
 */
class MainActivity : FragmentActivity() {
    /** Bertambah setiap ada permintaan Catat kilat (pintasan atau tile); layar membandingkannya dengan yang sudah ditangani. */
    private val quickCatatRequests = MutableStateFlow(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        noteQuickCatat(intent)
    }

    private fun noteQuickCatat(intent: Intent?) {
        if (intent?.action == ACTION_QUICK_CATAT) quickCatatRequests.value += 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Harus dipanggil sebelum super.onCreate: mengganti tema splash ke tema aplikasi.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Hanya saat dibuat baru: setelah layar diputar, intent lama tidak boleh membuka sheet lagi.
        if (savedInstanceState == null) noteQuickCatat(intent)

        val controller = AuthController(
            store = SharedPrefsSessionStore(this),
            provider = GoogleAuthProvider(this, GoogleClientId.clean(getString(R.string.google_web_client_id))),
            accounts = LocalAccountService(
                store = SharedPrefsAccountStore(this),
                hasher = Pbkdf2PasswordHasher(),
                newAccountId = { UUID.randomUUID().toString() },
            ),
            scope = lifecycleScope,
            // Masuk email+sandi ke server (Tahap 14): dijalankan di IO karena membaca berkas ID perangkat.
            emailSignIn = { email, password ->
                withContext(Dispatchers.IO) {
                    AccountWorkspace.signInRemote().loginWithEmail(email, password, DeviceId.read(this@MainActivity))
                }
            },
        )
        // Splash sistem hanya latar kosong dan menutup begitu frame pertama tergambar; selama riwayat
        // masuk dibaca, AppRoot menampilkan wordmark penuh (splash sistem tak bisa selebar layar).
        controller.start()

        val appLock = AppLockService(SharedPrefsSecurityStore(this), Pbkdf2PasswordHasher())
        val themePreference = ThemePreference(this)
        val purchases = PurchaseStore.shared(this)

        setContent {
            val mode by themePreference.mode.collectAsState()
            val darkTheme = mode.resolveDarkTheme()
            // Palet Pro hanya berlaku bila paket membukanya (satu lapisan entitlement); membaca `plans`
            // di komposisi membuat tema langsung ikut berubah begitu Pro dibeli atau dipulihkan.
            val storedPalette by themePreference.palette.collectAsState()
            val plans by purchases.plans.collectAsState()
            val palette = storedPalette.effective(LivePlanEntitlements { plans })
            // enableEdgeToEdge dipanggil sekali di onCreate hanya cocok untuk mode Otomatis; layar
            // Tampilan bisa memaksa terang/gelap berbeda dari sistem, jadi gaya bar sistem dihitung ulang.
            LaunchedEffect(darkTheme) {
                val style = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            RizqflowTheme(darkTheme = darkTheme, palette = palette) {
                AppRoot(
                    controller = controller,
                    appLock = appLock,
                    themePreference = themePreference,
                    showDebugLogin = BuildConfig.DEBUG,
                    quickCatatRequest = quickCatatRequests.collectAsState().value,
                )
            }
        }
    }
}
