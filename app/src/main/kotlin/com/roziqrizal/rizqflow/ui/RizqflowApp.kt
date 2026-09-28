package com.roziqrizal.rizqflow.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.roziqrizal.rizqflow.auth.AuthBusy
import com.roziqrizal.rizqflow.auth.AuthMessage
import com.roziqrizal.rizqflow.ui.login.messageText
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.ui.theme.CappedFontScale
import com.roziqrizal.rizqflow.ui.theme.NAV_LABEL_MAX_FONT_SCALE
import com.roziqrizal.rizqflow.ui.theme.rizqflowTonalButtonColors
import com.roziqrizal.rizqflow.ui.theme.spacing

/**
 * Empat tab utama (docs/ui-flow.md, "Kerangka navigasi"). Tombol Catat hanya muncul di Denah
 * dan Transaksi.
 */
enum class TopTab(
    val route: String,
    @StringRes val label: Int,
    @StringRes val placeholder: Int,
    val icon: ImageVector,
    val showsFab: Boolean,
) {
    Denah("denah", R.string.tab_denah, R.string.placeholder_denah, RizqflowIcons.Denah, showsFab = true),
    Transaksi("transaksi", R.string.tab_transaksi, R.string.placeholder_transaksi, RizqflowIcons.Transaksi, showsFab = true),
    Ruang("ruang", R.string.tab_ruang, R.string.placeholder_ruang, RizqflowIcons.Ruang, showsFab = false),
    Lainnya("lainnya", R.string.tab_lainnya, R.string.placeholder_lainnya, RizqflowIcons.Lainnya, showsFab = false),
    ;

    companion object {
        fun fromRoute(route: String?): TopTab = entries.firstOrNull { it.route == route } ?: Denah
    }
}

/** Kerangka aplikasi: bottom navigation, tombol Catat, dan isi tiap tab (sementara masih kerangka). */
@Composable
fun RizqflowApp(
    account: AccountUi? = null,
    onCatat: () -> Unit = {},
    onConnectGmail: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onDismissNotice: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    transaksiContent: (@Composable () -> Unit)? = null,
    ruangContent: (@Composable () -> Unit)? = null,
    denahContent: (@Composable () -> Unit)? = null,
    onOpenRules: (() -> Unit)? = null,
    onOpenManage: (() -> Unit)? = null,
    demo: Boolean = false,
    onToggleDemo: (() -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null,
    onOpenReconciliation: (() -> Unit)? = null,
    onOpenReminder: (() -> Unit)? = null,
    onOpenSecurity: (() -> Unit)? = null,
    onOpenCsv: (() -> Unit)? = null,
    onOpenBackup: (() -> Unit)? = null,
    onOpenTampilan: (() -> Unit)? = null,
    onOpenPaywall: (() -> Unit)? = null,
    onOpenRecurring: (() -> Unit)? = null,
    onOpenBills: (() -> Unit)? = null,
    onOpenDebts: (() -> Unit)? = null,
    onOpenReport: (() -> Unit)? = null,
    /** Paket Pro sudah dimiliki perangkat ini (lihat `PurchaseStore`); menentukan subjudul baris Rizqflow Pro. */
    proOwned: Boolean = false,
    /** Saklar Pro untuk uji; hanya diisi di build debug (null = baris tidak tampil). */
    onToggleProDebug: (() -> Unit)? = null,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = TopTab.fromRoute(backStack?.destination?.route)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            CappedFontScale(NAV_LABEL_MAX_FONT_SCALE) {
                NavigationBar {
                    TopTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == current,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            // Label sudah menjelaskan ikon, jadi ikon tidak perlu deskripsi sendiri.
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                            // Penanda tab aktif hijau seperti prototipe, bukan biru bawaan secondaryContainer.
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryFixed,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (current.showsFab) {
                ExtendedFloatingActionButton(
                    onClick = onCatat,
                    icon = { Icon(RizqflowIcons.Tambah, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_catat)) },
                    // Hijau tua dengan teks putih seperti prototipe (bawaan Material memakai primaryContainer).
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = TopTab.Denah.route,
            modifier = Modifier.padding(padding),
        ) {
            TopTab.entries.forEach { tab ->
                composable(tab.route) {
                    if (tab == TopTab.Lainnya && account != null) {
                        LainnyaScreen(account, onConnectGmail, onSignOut, onDismissNotice, onOpenRules, onOpenManage, demo, onToggleDemo, onOpenAbout, onOpenReconciliation, onOpenReminder, onOpenSecurity, onOpenCsv, onOpenBackup, onOpenTampilan, onOpenPaywall, proOwned, onOpenRecurring, onOpenBills, onOpenDebts, onOpenReport, onToggleProDebug)
                    } else if (tab == TopTab.Transaksi && transaksiContent != null) {
                        transaksiContent()
                    } else if (tab == TopTab.Ruang && ruangContent != null) {
                        ruangContent()
                    } else if (tab == TopTab.Denah && denahContent != null) {
                        denahContent()
                    } else {
                        PlaceholderScreen(title = stringResource(tab.label), note = stringResource(tab.placeholder))
                    }
                }
            }
        }
    }
}

/** Isi sementara tiap tab sampai layarnya dikerjakan (Tahap 3 dan 4). */
@Composable
private fun PlaceholderScreen(title: String, note: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MaterialTheme.spacing.s4, vertical = MaterialTheme.spacing.s5),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MaterialTheme.spacing.s2),
        )
    }
}

/** Baris menu di tab Lainnya: judul, keterangan, dan panah. */
@Composable
private fun MenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(top = MaterialTheme.spacing.s3)
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = MaterialTheme.spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(RizqflowIcons.PanahKanan, contentDescription = null)
    }
}

/** Data akun untuk tab Lainnya. */
data class AccountUi(
    /** Email (akun Google) atau nama pengguna (akun lokal). */
    val identifier: String,
    val local: Boolean,
    val displayName: String?,
    val gmailConnected: Boolean,
    val busy: AuthBusy? = null,
    val notice: AuthMessage? = null,
)

/** Tab Lainnya sementara: hanya bagian Akun (masuk, Gmail, keluar); menu lain menyusul di Tahap 3 dan 6. */
@Composable
private fun LainnyaScreen(
    account: AccountUi,
    onConnectGmail: () -> Unit,
    onSignOut: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenRules: (() -> Unit)? = null,
    onOpenManage: (() -> Unit)? = null,
    demo: Boolean = false,
    onToggleDemo: (() -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null,
    onOpenReconciliation: (() -> Unit)? = null,
    onOpenReminder: (() -> Unit)? = null,
    onOpenSecurity: (() -> Unit)? = null,
    onOpenCsv: (() -> Unit)? = null,
    onOpenBackup: (() -> Unit)? = null,
    onOpenTampilan: (() -> Unit)? = null,
    onOpenPaywall: (() -> Unit)? = null,
    proOwned: Boolean = false,
    onOpenRecurring: (() -> Unit)? = null,
    onOpenBills: (() -> Unit)? = null,
    onOpenDebts: (() -> Unit)? = null,
    onOpenReport: (() -> Unit)? = null,
    onToggleProDebug: (() -> Unit)? = null,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.s4, vertical = spacing.s5),
    ) {
        Text(stringResource(R.string.tab_lainnya), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(R.string.placeholder_lainnya),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s2),
        )
        if (onOpenRules != null) MenuRow(stringResource(R.string.menu_rules), stringResource(R.string.menu_rules_sub), onOpenRules)
        if (onOpenManage != null) MenuRow(stringResource(R.string.menu_manage), stringResource(R.string.menu_manage_sub), onOpenManage)
        if (onOpenRecurring != null) MenuRow(stringResource(R.string.menu_recurring), stringResource(R.string.menu_recurring_sub), onOpenRecurring)
        if (onOpenBills != null) MenuRow(stringResource(R.string.menu_bills), stringResource(R.string.menu_bills_sub), onOpenBills)
        if (onOpenDebts != null) MenuRow(stringResource(R.string.menu_debts), stringResource(R.string.menu_debts_sub), onOpenDebts)
        if (onOpenReport != null) MenuRow(stringResource(R.string.menu_report), stringResource(R.string.menu_report_sub), onOpenReport)
        if (onToggleDemo != null) {
            MenuRow(
                stringResource(R.string.menu_demo),
                stringResource(if (demo) R.string.menu_demo_on else R.string.menu_demo_sub),
                onToggleDemo,
            )
        }
        if (onOpenAbout != null) MenuRow(stringResource(R.string.menu_about), stringResource(R.string.menu_about_sub), onOpenAbout)
        if (onOpenReconciliation != null) MenuRow(stringResource(R.string.menu_reconcile), stringResource(R.string.menu_reconcile_sub), onOpenReconciliation)
        if (onOpenReminder != null) MenuRow(stringResource(R.string.menu_reminder), stringResource(R.string.menu_reminder_sub), onOpenReminder)
        if (onOpenSecurity != null) MenuRow(stringResource(R.string.menu_security), stringResource(R.string.menu_security_sub), onOpenSecurity)
        if (onOpenCsv != null) MenuRow(stringResource(R.string.menu_csv), stringResource(R.string.menu_csv_sub), onOpenCsv)
        if (onOpenBackup != null) MenuRow(stringResource(R.string.menu_backup), stringResource(R.string.menu_backup_sub), onOpenBackup)
        if (onOpenTampilan != null) MenuRow(stringResource(R.string.menu_tampilan), stringResource(R.string.menu_tampilan_sub), onOpenTampilan)
        if (onOpenPaywall != null) {
            MenuRow(
                stringResource(R.string.menu_pro),
                stringResource(if (proOwned) R.string.menu_pro_sub_owned else R.string.menu_pro_sub_free),
                onOpenPaywall,
            )
        }
        if (onToggleProDebug != null) {
            MenuRow(
                stringResource(R.string.menu_pro_debug),
                stringResource(if (proOwned) R.string.menu_pro_debug_on else R.string.menu_pro_debug_off),
                onToggleProDebug,
            )
        }
        Text(
            stringResource(R.string.account_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = spacing.s5, bottom = spacing.s2),
        )
        Text(account.displayName ?: account.identifier, style = MaterialTheme.typography.titleMedium)
        if (account.displayName != null) {
            Text(account.identifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (account.local) {
            Text(
                stringResource(R.string.account_local),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(if (account.gmailConnected) R.string.account_gmail_on else R.string.account_gmail_off),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = spacing.s3),
        )
        if (account.notice != null) {
            Row(modifier = Modifier.padding(top = spacing.s2)) {
                Text(stringResource(messageText(account.notice)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            OutlinedButton(onClick = onDismissNotice) { Text(stringResource(R.string.action_ok)) }
        }
        if (!account.gmailConnected) {
            FilledTonalButton(
                onClick = onConnectGmail,
                enabled = account.busy == null,
                colors = rizqflowTonalButtonColors(),
                modifier = Modifier.padding(top = spacing.s3),
            ) {
                if (account.busy == AuthBusy.GMAIL) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.login_gmail))
                }
            }
        }
        OutlinedButton(onClick = onSignOut, modifier = Modifier.padding(top = spacing.s3)) {
            Text(stringResource(R.string.action_sign_out))
        }
    }
}
