package com.roziqrizal.rizqflow.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.BuildConfig
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.ui.Notifier
import com.roziqrizal.rizqflow.ui.RizqflowIcons
import com.roziqrizal.rizqflow.ui.theme.spacing
import kotlinx.coroutines.launch

/**
 * S23 Tentang dan disclaimer: versi aplikasi, ringkasan privasi, asumsi fikih zakat mal (dengan
 * penanda belum diverifikasi kitab sampai pemilik menyelesaikannya), dan disclaimer bantuan hitung.
 * Sama seperti versi ringkas di S14, tetapi lengkap. Kebijakan privasi dan lisensi dibuka sebagai
 * sheet di dalam aplikasi karena belum ada halaman web untuk itu; kirim masukan membuka email.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(notifier: Notifier, onClose: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var privacyOpen by rememberSaveable { mutableStateOf(false) }
    var licensesOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(onClick = onClose, modifier = Modifier.padding(start = spacing.s3, top = spacing.s2)) { Text(stringResource(R.string.onb_back)) }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.s5)
                .padding(bottom = spacing.s5),
        ) {
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineMedium)
            Text("Rizqflow", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = spacing.s4))
            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(stringResource(R.string.about_privacy_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s5, bottom = spacing.s1))
            Text(stringResource(R.string.about_privacy_body), style = MaterialTheme.typography.bodyMedium)

            Text(stringResource(R.string.about_fiqh_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = spacing.s5, bottom = spacing.s1))
            AssumptionRow(stringResource(R.string.about_fiqh_nisab), stringResource(R.string.about_fiqh_nisab_value))
            AssumptionRow(stringResource(R.string.about_fiqh_rate), stringResource(R.string.about_fiqh_rate_value))
            AssumptionRow(stringResource(R.string.about_fiqh_haul), stringResource(R.string.about_fiqh_haul_value))
            Text(
                stringResource(R.string.about_fiqh_source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s2),
            )

            Text(
                stringResource(R.string.about_disclaimer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s4),
            )

            HorizontalDivider(modifier = Modifier.padding(top = spacing.s5, bottom = spacing.s2))
            AboutRow(stringResource(R.string.about_privacy_policy)) { privacyOpen = true }
            AboutRow(stringResource(R.string.about_licenses)) { licensesOpen = true }
            // Teks diambil di sini (konteks komposisi) supaya ikut konfigurasi bahasa terbaru, bukan dari LocalContext di dalam klik.
            val feedbackEmail = stringResource(R.string.about_feedback_email)
            val feedbackSubject = stringResource(R.string.about_feedback_subject, BuildConfig.VERSION_NAME)
            val feedbackError = stringResource(R.string.about_feedback_error)
            AboutRow(stringResource(R.string.about_feedback)) {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:$feedbackEmail")
                    putExtra(Intent.EXTRA_SUBJECT, feedbackSubject)
                }
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    scope.launch { notifier.show(feedbackError) }
                }
            }
        }
    }

    if (privacyOpen) {
        ModalBottomSheet(onDismissRequest = { privacyOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spacing.s5).padding(bottom = spacing.s5)) {
                Text(stringResource(R.string.about_privacy_policy), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.about_privacy_draft_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.s1, bottom = spacing.s3),
                )
                Text(stringResource(R.string.about_privacy_full), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (licensesOpen) {
        ModalBottomSheet(onDismissRequest = { licensesOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spacing.s5).padding(bottom = spacing.s5)) {
                Text(stringResource(R.string.about_licenses), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.about_licenses_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.s2, bottom = spacing.s3),
                )
                LICENSES.forEach { (name, license) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s1), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AssumptionRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AboutRow(title: String, onClick: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = spacing.s3),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Icon(RizqflowIcons.PanahKanan, contentDescription = null)
    }
}

/** Pustaka sumber terbuka yang dipakai saat berjalan (gradle/libs.versions.toml); alat pengembangan (Robolectric, JUnit) tidak dicantumkan. */
private val LICENSES = listOf(
    "Kotlin" to "Apache License 2.0",
    "Jetpack Compose" to "Apache License 2.0",
    "AndroidX Room" to "Apache License 2.0",
    "AndroidX Navigation Compose" to "Apache License 2.0",
    "AndroidX Activity Compose" to "Apache License 2.0",
    "AndroidX Core Splashscreen" to "Apache License 2.0",
    "AndroidX Credentials" to "Apache License 2.0",
    "Google Identity Services" to "Apache License 2.0",
    "Google Play Services Auth" to "Android SDK License",
    "Kotlinx Coroutines" to "Apache License 2.0",
)
