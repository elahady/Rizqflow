package com.roziqrizal.rizqflow.workspace

import android.content.Context
import com.roziqrizal.rizqflow.billing.PurchaseStore
import com.roziqrizal.rizqflow.data.LocalLedger
import com.roziqrizal.rizqflow.domain.bill.BillReminderService
import com.roziqrizal.rizqflow.domain.bill.BillService
import com.roziqrizal.rizqflow.domain.debt.DebtReminderService
import com.roziqrizal.rizqflow.domain.debt.DebtService
import com.roziqrizal.rizqflow.domain.entitlement.Entitlements
import com.roziqrizal.rizqflow.domain.entitlement.LivePlanEntitlements
import com.roziqrizal.rizqflow.domain.ledger.DemoData
import com.roziqrizal.rizqflow.domain.ledger.FavoriteService
import com.roziqrizal.rizqflow.domain.ledger.LedgerService
import com.roziqrizal.rizqflow.domain.ledger.ManagementService
import com.roziqrizal.rizqflow.domain.ledger.ReconciliationService
import com.roziqrizal.rizqflow.domain.ledger.ReminderService
import com.roziqrizal.rizqflow.domain.ledger.RuleService
import com.roziqrizal.rizqflow.domain.ledger.WorkspaceSetup
import com.roziqrizal.rizqflow.domain.role.DcaReminderService
import com.roziqrizal.rizqflow.domain.recurring.RecurringService
import com.roziqrizal.rizqflow.domain.role.RoleService
import com.roziqrizal.rizqflow.domain.smartinput.SmartInputService
import com.roziqrizal.rizqflow.domain.zakat.HaulReminderService
import com.roziqrizal.rizqflow.domain.zakat.ZakatService
import java.util.UUID

/**
 * Ruang kerja satu akun yang sedang masuk: databasenya dan layanan di atasnya. Dibuat saat akun
 * masuk dan ditutup saat keluar; akun lain membuka berkas lain (satu database per akun).
 * Pengenal dibuat sebagai UUID dan waktu diambil dari jam sistem.
 */
class AccountWorkspace private constructor(private val local: LocalLedger, val purchases: PurchaseStore) : AutoCloseable {
    private val newId: () -> String = { UUID.randomUUID().toString() }

    val repositories: LocalLedger get() = local
    val setup = WorkspaceSetup(local.workspace, newId)
    val ledger = LedgerService(local.accounts, local.rooms, local.transactions, newId, System::currentTimeMillis)

    // Status pembelian sungguhan lewat Google Play Billing menyusul (Tahap 7 lanjutan, perlu listing
    // Play Console dulu); [purchases] sudah jadi satu-satunya sumber, dibaca ulang tiap dipanggil
    // supaya perubahan paket langsung berlaku tanpa membuka ulang ruang kerja ini.
    private val entitlements: Entitlements = LivePlanEntitlements { purchases.plans.value }
    val rules = RuleService(local.rooms, entitlements, newId)
    val management = ManagementService(local.accounts, local.rooms, newId)
    val favorites = FavoriteService(local.favorites, local.accounts, local.rooms, ledger, newId, System::currentTimeMillis)
    val zakat = ZakatService(local.rooms, local.transactions, local.zakat, ledger, newId, entitlements = entitlements)
    val reconciliation = ReconciliationService(local.accounts, local.rooms, local.transactions, ledger)
    val reminder = ReminderService(local.settings, local.accounts, local.rooms, local.transactions, ledger)
    val haulReminder = HaulReminderService(local.rooms, zakat, local.settings)
    val roles = RoleService(local.rooms, local.accounts, local.transactions, local.roles, ledger, entitlements)
    val dcaReminder = DcaReminderService(roles, local.settings)
    val recurring = RecurringService(local.recurring, local.accounts, local.rooms, local.transactions, ledger, newId)
    val bills = BillService(local.bills, local.accounts, local.rooms, local.transactions, ledger, newId)
    val billReminder = BillReminderService(local.bills, local.settings)
    val debts = DebtService(local.debts, local.accounts, local.transactions, ledger, newId)
    val debtReminder = DebtReminderService(local.debts, local.settings)
    val smartInput = SmartInputService(local.transactions)
    val demo = DemoData(setup, ledger, management, favorites, local.rooms)

    override fun close() = local.close()

    companion object {
        fun open(context: Context, accountId: String): AccountWorkspace =
            AccountWorkspace(LocalLedger.open(context, accountId), PurchaseStore(context))
    }
}
