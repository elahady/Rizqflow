package com.roziqrizal.rizqflow.data

import android.content.Context
import androidx.room.Room as AndroidRoom
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.ledger.FirstAccount
import com.roziqrizal.rizqflow.domain.ledger.LedgerResult
import com.roziqrizal.rizqflow.domain.ledger.LedgerService
import com.roziqrizal.rizqflow.domain.ledger.NewExpense
import com.roziqrizal.rizqflow.domain.ledger.NewIncome
import com.roziqrizal.rizqflow.domain.ledger.RoomTemplate
import com.roziqrizal.rizqflow.domain.ledger.WorkspaceSetup
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.money.Money
import com.roziqrizal.rizqflow.domain.sync.SyncClock
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/** Satu baris `sync_record` apa adanya, untuk asersi. */
internal data class SyncRow(
    val scope: String,
    val type: String,
    val id: String,
    val changedAt: Long,
    val deleted: Boolean,
    val dirty: Boolean,
    val pushedChangedAt: Long?,
    val author: String?,
    val deviceId: String?,
)

/**
 * Database Room di memori + [LocalLedger] dengan jam sinkron yang bisa diatur, sudah di-onboarding (Tiga hak + satu
 * akun). Tes recorder dan store memakainya supaya jalur tulis yang diuji adalah jalur produksi yang sama.
 */
internal class SyncFixture(
    var nowMillis: Long = 1_000_000L,
    /** Lingkup sinkron otomatis; tes yang menguji pemicu memberi lingkup yang deterministik (mis. Unconfined). */
    autoSyncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    val db: RizqflowDatabase = AndroidRoom.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        RizqflowDatabase::class.java,
    ).allowMainThreadQueries().build()

    val clock = SyncClock { nowMillis }
    val local = LocalLedger(db, clock, autoSyncScope)
    private var counter = 0
    val newId: () -> String = { "id-${++counter}" }
    val ledger = LedgerService(local.accounts, local.rooms, local.transactions, newId) { nowMillis }
    val today: LocalDate = LocalDate.of(2026, 10, 25)

    init {
        runBlocking {
            WorkspaceSetup(local.workspace, newId).setUp(RoomTemplate.TIGA_HAK, FirstAccount("Dompet", AccountKind.CASH, Money.rupiah(5_000_000)))
        }
    }

    fun close() = db.close()

    fun rows(): List<SyncRow> = db.openHelper.readableDatabase.query(
        "SELECT scope, record_type, record_id, changed_at, deleted, dirty, pushed_changed_at, author, device_id FROM sync_record ORDER BY scope, record_type, record_id",
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    SyncRow(
                        scope = c.getString(0),
                        type = c.getString(1),
                        id = c.getString(2),
                        changedAt = c.getLong(3),
                        deleted = c.getInt(4) != 0,
                        dirty = c.getInt(5) != 0,
                        pushedChangedAt = if (c.isNull(6)) null else c.getLong(6),
                        author = c.getString(7),
                        deviceId = c.getString(8),
                    ),
                )
            }
        }
    }

    fun row(type: SyncRecordType, id: String): SyncRow? = rows().firstOrNull { it.type == type.wire && it.id == id }

    fun ids(type: SyncRecordType): Set<String> = rows().filter { it.type == type.wire }.mapTo(mutableSetOf()) { it.id }

    /** Mengosongkan `sync_record` (mis. setelah onboarding) supaya tes hanya melihat jejak operasi yang diuji. */
    fun clearSync() = db.openHelper.writableDatabase.execSQL("DELETE FROM sync_record")

    /** Menandai semua rekaman bersih, seolah sudah terkirim. */
    fun markAllClean() = db.openHelper.writableDatabase.execSQL("UPDATE sync_record SET dirty = 0, pushed_changed_at = changed_at")

    val personal = SyncScope.Personal

    suspend fun roomIds() = local.rooms.activeRooms()

    suspend fun accountId() = local.accounts.activeAccounts().first().id

    suspend fun income(amount: Long = 1_000_000): com.roziqrizal.rizqflow.domain.model.TransactionId {
        val result = ledger.recordIncome(NewIncome(Money.rupiah(amount), accountId(), "Gaji", today))
        return (result as LedgerResult.Success).value.transaction.id
    }

    suspend fun expense(amount: Long = 25_000, roomIndex: Int = 0): com.roziqrizal.rizqflow.domain.model.TransactionId {
        val room = local.rooms.activeRooms()[roomIndex]
        val category = local.rooms.categories(room.id).first()
        val result = ledger.recordExpense(NewExpense(Money.rupiah(amount), accountId(), room.id, category.id, today))
        return (result as LedgerResult.Success).value.id
    }
}
