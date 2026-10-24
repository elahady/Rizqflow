package com.roziqrizal.rizqflow.auth

import android.app.Activity
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.roziqrizal.rizqflow.domain.auth.AuthProviderType
import com.roziqrizal.rizqflow.domain.auth.AuthSession
import com.roziqrizal.rizqflow.domain.auth.GmailConnectResult
import com.roziqrizal.rizqflow.domain.auth.SignInResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Masuk dengan Google lewat Credential Manager, dan izin baca Gmail lewat AuthorizationClient.
 *
 * Butuh **Web client ID** dari proyek Google Cloud pemilik (resource `google_web_client_id`),
 * lihat docs/auth-google.md. Selama kosong atau bukan berbentuk client ID Google, keduanya
 * mengembalikan `NotConfigured`. Kegagalan dari Google dicatat di Logcat (tag `RizqflowGoogle`)
 * supaya salah konfigurasi (SHA-1, package, layar persetujuan) mudah dilacak; pengguna hanya
 * melihat pesan lembut.
 *
 * ID token diteruskan lewat [SignInResult.Success.idToken] ke server Rizqflow sekali saat masuk
 * (Tahap 9, `AccountRemote.loginWithGoogle`) lalu dilupakan — kelas ini sendiri tidak menyimpannya,
 * token akses Gmail juga tidak disimpan. Harus dibuat di `onCreate` Activity karena
 * mendaftarkan peluncur hasil aktivitas.
 */
class GoogleAuthProvider(
    private val activity: ComponentActivity,
    private val webClientId: String,
) : AuthProvider {

    private val credentialManager = CredentialManager.create(activity)
    private val configured = GoogleClientId.isValid(webClientId).also { ok ->
        if (!ok && webClientId.isNotBlank()) {
            Log.w(TAG, "google_web_client_id bukan berbentuk client ID Google (<angka>-<kode>.apps.googleusercontent.com); tombol Google dinonaktifkan")
        }
    }

    /** Izin yang tertunda menunggu layar persetujuan Google selesai. */
    private var pendingConsent: CancellableContinuation<GmailConnectResult>? = null

    private val consentLauncher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val continuation = pendingConsent ?: return@registerForActivityResult
        pendingConsent = null
        if (result.resultCode != Activity.RESULT_OK) {
            continuation.resume(GmailConnectResult.Denied)
            return@registerForActivityResult
        }
        val outcome = try {
            val granted = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(result.data)
            if (granted.grantedScopes.contains(GMAIL_READONLY)) GmailConnectResult.Connected else GmailConnectResult.Denied
        } catch (e: ApiException) {
            Log.w(TAG, "membaca hasil izin Gmail gagal: kode ${e.statusCode}", e)
            GmailConnectResult.Failed(e.message ?: "gagal membaca hasil izin")
        }
        continuation.resume(outcome)
    }

    override suspend fun signInWithGoogle(): SignInResult {
        if (!configured) return SignInResult.NotConfigured
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
            .build()
        return try {
            val credential = credentialManager.getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                SignInResult.Success(
                    session = AuthSession(
                        accountId = google.id,
                        email = google.id,
                        displayName = google.displayName,
                        provider = AuthProviderType.GOOGLE,
                    ),
                    idToken = google.idToken,
                )
            } else {
                SignInResult.Failed("jenis kredensial tidak dikenal")
            }
        } catch (e: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (e: NoCredentialException) {
            Log.w(TAG, "tidak ada akun Google di perangkat (tambahkan di Pengaturan, Akun)", e)
            SignInResult.Failed("tidak ada akun Google di perangkat")
        } catch (e: GetCredentialException) {
            // Salah konfigurasi biasanya muncul di sini: SHA-1 atau package belum didaftarkan di client ID Android,
            // atau proyek masih berstatus Testing dan akun belum jadi Test user. Lihat docs/auth-google.md.
            Log.w(TAG, "masuk dengan Google gagal: ${e.type}: ${e.message}", e)
            SignInResult.Failed(e.message ?: "gagal masuk")
        }
    }

    override suspend fun connectGmail(): GmailConnectResult {
        if (!configured) return GmailConnectResult.NotConfigured
        val request = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(GMAIL_READONLY)))
            .build()
        return suspendCancellableCoroutine { continuation ->
            Identity.getAuthorizationClient(activity).authorize(request)
                .addOnSuccessListener { result: AuthorizationResult ->
                    val intent = result.pendingIntent
                    if (result.hasResolution() && intent != null) {
                        pendingConsent = continuation
                        consentLauncher.launch(IntentSenderRequest.Builder(intent.intentSender).build())
                    } else {
                        continuation.resume(GmailConnectResult.Connected)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "meminta izin Gmail gagal (kode ${(e as? ApiException)?.statusCode})", e)
                    continuation.resume(GmailConnectResult.Failed(e.message ?: "gagal meminta izin"))
                }
            continuation.invokeOnCancellation { pendingConsent = null }
        }
    }

    private companion object {
        const val TAG = "RizqflowGoogle"

        /** Izin baca saja. Ini cakupan terbatas (restricted) di Google: lihat docs/auth-google.md. */
        const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
    }
}
