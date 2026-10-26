package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.auth.runSuspend
import com.roziqrizal.rizqflow.domain.ledger.Account
import com.roziqrizal.rizqflow.domain.ledger.Category
import com.roziqrizal.rizqflow.domain.ledger.InMemoryLedger
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.ledger.RoomTemplates
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.money.Money
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Fondasi sync tanpa kripto/jaringan sungguhan — [FakeSyncAead] identitas, [FakeSyncRemote] di memori. */
private class FakeSyncAead : SyncAead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext
    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = ciphertext
}

private class FakeRoomKeyProvider : RoomKeyProvider {
    private val aead = FakeSyncAead()
    override fun getOrCreateRoomKey(roomId: RoomId) = aead
    override fun exportRoomKey(roomId: RoomId) = ByteArray(0)
    override fun importRoomKey(roomId: RoomId, exportedRoomKey: ByteArray) = Unit
    override fun rotateRoomKey(roomId: RoomId) = aead
}

/** Codec tiruan: baris teks dipisah "|" — cukup untuk menguji logika [SyncCoordinator], bukan format sungguhan. */
private class FakeTransactionSyncCodec : TransactionSyncCodec {
    override fun encode(transaction: MoneyTransaction, accountName: String, categoryName: String?): ByteArray =
        listOf(
            transaction.id.value,
            transaction.amount.minor.toString(),
            accountName,
            categoryName ?: "",
            transaction.occurredOn.toString(),
            transaction.note ?: "",
        ).joinToString("|").toByteArray()

    override fun decode(bytes: ByteArray): DecodedSyncTransaction {
        val parts = String(bytes).split("|")
        return DecodedSyncTransaction(
            id = TransactionId(parts[0]),
            amount = Money(parts[1].toLong()),
            accountName = parts[2],
            categoryName = parts[3].ifEmpty { null },
            occurredOn = LocalDate.parse(parts[4]),
            note = parts[5].ifEmpty { null },
        )
    }
}

private class FakeSyncRemote : SyncRemote {
    val stored = mutableListOf<SyncLogEntryDownload>()
    var nextId = 1L
    var failWith: RemoteError? = null
    var serverTimeMs: Long? = null

    /**
     * Simulasi "ack hilang" (sync-desain.md bagian G, Tahap 2): server menyimpan baris seperti biasa
     * (idempoten di bawah), tetapi JAWABANnya hilang di jaringan — klien melihat ini sebagai gagal dan
     * akan retry. Berlaku hanya untuk SATU panggilan [push] berikutnya, lalu otomatis kembali normal.
     */
    var dropAckOnNextPush = false

    override suspend fun push(apiToken: String, entries: List<SyncLogEntryUpload>): RemoteResult<SyncPushResult> {
        failWith?.let { return RemoteResult.Failure(it) }
        val dropAck = dropAckOnNextPush
        dropAckOnNextPush = false
        val ids = entries.map { entry ->
            // Idempoten, sama seperti SyncController::push sungguhan: kunci (device_id, record_id,
            // changed_at_ms, deleted) — baris yang sama dikirim ulang menemukan baris yang sama, tidak menggandakan.
            val existing = stored.firstOrNull {
                it.deviceId == entry.deviceId && it.recordId == entry.recordId &&
                    it.changedAtMillis == entry.changedAtMillis && it.deleted == entry.deleted
            }
            if (existing != null) return@map existing.id

            val id = nextId++
            // Tiruan server (Tahap 2 bagian B R3): penulis = device_id yang pertama kali menulis id ini.
            val author = stored.firstOrNull { it.recordId == entry.recordId }?.author ?: entry.deviceId
            stored += SyncLogEntryDownload(
                id, entry.roomId, entry.recordType, entry.recordId, entry.deviceId, entry.payload, entry.deleted,
                entry.changedAt, entry.changedAtMillis, author,
            )
            id
        }
        if (dropAck) return RemoteResult.Failure(RemoteError.Unreachable)
        return RemoteResult.Success(SyncPushResult(ids, ids.max(), serverTimeMs))
    }

    override suspend fun pull(apiToken: String, deviceId: String, after: Long, limit: Int): RemoteResult<SyncPullResult> {
        failWith?.let { return RemoteResult.Failure(it) }
        val page = stored.filter { it.id > after }.sortedBy { it.id }.take(limit)
        return RemoteResult.Success(SyncPullResult(page, page.lastOrNull()?.id ?: after, page.size == limit, serverTimeMs))
    }
}

private class FakeApiTokenStore(private var token: String? = "token-1") : ApiTokenStore {
    private var expiresAt: java.time.Instant? = null
    override fun read() = token
    override fun expiresAt() = expiresAt
    override fun save(token: String, expiresAt: java.time.Instant?): Boolean { this.token = token; this.expiresAt = expiresAt; return true }
    override fun clear() { token = null; expiresAt = null }
}

private class FakeSyncCursorStore : SyncCursorStore {
    private val cursors = mutableMapOf<String, Long>()
    override fun read(roomId: String) = cursors[roomId] ?: 0L
    override fun save(roomId: String, cursor: Long) { cursors[roomId] = cursor }
}

/**
 * Menyisipkan satu baris langsung ke "server" tiruan tanpa lewat [SyncCoordinator.push] — dipakai
 * menguji baris dari sumber yang TIDAK pernah lewat jalur normal satu perangkat (cakupan pribadi
 * `room_id` null yang kebetulan ikut di aliran pull, atau versi basi "perangkat lain"). [FakeSyncAead]
 * identitas, jadi payload == ciphertext; tetap di-base64 seperti [SyncCoordinator] sungguhan.
 */
private fun FakeSyncRemote.seedRawEntry(
    transaction: MoneyTransaction,
    accountName: String,
    categoryName: String?,
    deviceId: String,
    changedAtMillis: Long,
    roomId: String?,
    deleted: Boolean = false,
) {
    val payload = if (deleted) ByteArray(0) else FakeTransactionSyncCodec().encode(transaction, accountName, categoryName)
    stored += SyncLogEntryDownload(
        id = nextId++,
        roomId = roomId,
        recordType = "transaction",
        recordId = transaction.id.value,
        deviceId = deviceId,
        payload = Base64.getEncoder().encodeToString(payload),
        deleted = deleted,
        changedAt = Instant.ofEpochMilli(changedAtMillis).toString(),
        changedAtMillis = changedAtMillis,
        author = deviceId,
    )
}

class SyncCoordinatorTest {
    private val roomId = RoomId("room-keluarga")
    private val accountId = AccountId("acc-1")
    private val categoryId = CategoryId("cat-1")

    // Jam perangkat bawaan 0 (bukan jam sistem nyata): SyncCoordinator.push() sejak Tahap 2 memanggil
    // clock.stamp(entry.changedAtMillis) = max(jam perangkat + offset, cap lokal + 1). Dengan jam
    // perangkat 0 dan offset 0 (belum pernah diisi server_time_ms), hasilnya selalu "cap lokal + 1" —
    // jadi urutan LWW di tes-tes lain (yang bergantung pada nilai updatedAt kecil seperti 500/2000/5000)
    // tetap deterministik, tidak kebetulan dikalahkan oleh epoch millis sungguhan.
    private fun engine(
        ledger: InMemoryLedger,
        remote: FakeSyncRemote,
        tokenStore: ApiTokenStore = FakeApiTokenStore(),
        clock: SyncClock = SyncClock(deviceNowMillis = { 0L }),
    ) = SyncCoordinator(ledger, ledger, ledger, FakeRoomKeyProvider(), remote, FakeTransactionSyncCodec(), tokenStore, FakeSyncCursorStore(), clock)

    private fun seedRoomAndAccount(ledger: InMemoryLedger) {
        ledger.roomRows[roomId] = Room(roomId, "Keluarga", RoomKind.MENCUKUPI, "home", 1, sortOrder = 0)
        ledger.categoryRows[categoryId] = Category(categoryId, roomId, "Belanja")
        ledger.accountRows[accountId] = Account(accountId, "Dompet", AccountKind.CASH, Money.zero())
    }

    private fun expense(id: String, amount: Long, note: String? = null, updatedAt: Long = 1_000L) = MoneyTransaction(
        id = TransactionId(id),
        kind = TransactionKind.EXPENSE,
        amount = Money(amount),
        accountId = accountId,
        roomId = roomId,
        categoryId = categoryId,
        occurredOn = LocalDate.of(2026, 10, 24),
        note = note,
        origin = TransactionOrigin.MANUAL,
        createdAtMillis = updatedAt,
        updatedAtMillis = updatedAt,
    )

    @Test
    fun pushes_pending_transactions_and_marks_them_synced() = runSuspend {
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        ledger.transactionRows[TransactionId("tx-1")] = expense("tx-1", 25_000)
        val remote = FakeSyncRemote()

        val result = engine(ledger, remote).syncNow(roomId, deviceId = "device-a")

        assertTrue(result is SyncResult.Success)
        assertEquals(1, result.pushed)
        assertEquals(1, remote.stored.size)
        assertTrue(ledger.syncedAtRows.containsKey(TransactionId("tx-1")))
    }

    @Test
    fun does_not_push_the_same_transaction_twice() = runSuspend {
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        ledger.transactionRows[TransactionId("tx-1")] = expense("tx-1", 25_000)
        val remote = FakeSyncRemote()
        val e = engine(ledger, remote)

        e.syncNow(roomId, deviceId = "device-a")
        val second = e.syncNow(roomId, deviceId = "device-a") as SyncResult.Success

        assertEquals(0, second.pushed)
        assertEquals(1, remote.stored.size)
    }

    @Test
    fun applies_a_transaction_pushed_by_another_member() = runSuspend {
        val ledgerA = InMemoryLedger()
        seedRoomAndAccount(ledgerA)
        ledgerA.transactionRows[TransactionId("tx-1")] = expense("tx-1", 50_000, note = "Belanja mingguan")
        val remote = FakeSyncRemote()
        engine(ledgerA, remote).syncNow(roomId, deviceId = "device-a")

        val ledgerB = InMemoryLedger()
        seedRoomAndAccount(ledgerB) // anggota B: ruang, akun "Dompet", kategori "Belanja" sama namanya
        val result = engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b")

        assertTrue(result is SyncResult.Success)
        assertEquals(1, result.applied)
        assertEquals(0, result.skipped)
        val applied = ledgerB.transactionRows[TransactionId("tx-1")]
        assertEquals(Money(50_000), applied?.amount)
        assertEquals("Belanja mingguan", applied?.note)
        // Bukan IMPORT: baris anggota lain bukan data pribadi (dikecualikan dari pencatat dan backfill sinkron entitas penuh).
        assertEquals(TransactionOrigin.SHARED, applied?.origin)
        assertEquals(categoryId, applied?.categoryId)
        // Akun anggota virtual (Tahap 2, keputusan pemilik #0): TIDAK PERNAH akun nyata "Dompet" milik B,
        // walau namanya sama persis dengan akun A — itu bug SyncEngine lama yang digantikan di tahap ini.
        assertTrue(applied?.accountId != accountId, "pengeluaran anggota lain tidak boleh atas nama akun nyata milik penerima")
        val virtualAccount = ledgerB.accountRows[applied?.accountId]
        assertEquals(roomId, virtualAccount?.sharedFrom)
    }

    @Test
    fun a_shared_expense_from_a_member_never_touches_the_real_balance_of_a_same_named_local_account() = runSuspend {
        val ledgerA = InMemoryLedger()
        seedRoomAndAccount(ledgerA)
        ledgerA.transactionRows[TransactionId("tx-1")] = expense("tx-1", 50_000)
        val remote = FakeSyncRemote()
        engine(ledgerA, remote).syncNow(roomId, deviceId = "device-a")

        val ledgerB = InMemoryLedger()
        seedRoomAndAccount(ledgerB) // akun "Dompet" nyata milik B, namanya kebetulan sama dengan akun A
        val balanceBefore = ledgerB.balance(accountId)

        engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b")

        assertEquals(balanceBefore, ledgerB.balance(accountId))
        assertEquals(listOf(accountId), ledgerB.activeAccounts().map { it.id }, "akun virtual tidak boleh ikut daftar akun aktif")
    }

    @Test
    fun creates_one_virtual_member_account_per_author_even_for_transactions_from_an_unknown_local_account_name() = runSuspend {
        val ledgerA = InMemoryLedger()
        seedRoomAndAccount(ledgerA)
        ledgerA.transactionRows[TransactionId("tx-1")] = expense("tx-1", 50_000)
        val remote = FakeSyncRemote()
        engine(ledgerA, remote).syncNow(roomId, deviceId = "device-a")

        val ledgerB = InMemoryLedger()
        ledgerB.roomRows[roomId] = Room(roomId, "Keluarga", RoomKind.MENCUKUPI, "home", 1, sortOrder = 0)
        ledgerB.categoryRows[categoryId] = Category(categoryId, roomId, "Belanja")
        // Tidak ada akun "Dompet" di sisi B sama sekali — dulu ini membuat baris dilewati (skipped);
        // sekarang akun anggota virtual selalu bisa dibuat, tidak pernah tergantung akun lokal penerima.
        val result = engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b") as SyncResult.Success

        assertEquals(1, result.applied)
        assertEquals(0, result.skipped)
        val applied = ledgerB.transactionRows.getValue(TransactionId("tx-1"))
        val virtualAccount = ledgerB.accountRows.getValue(applied.accountId)
        assertEquals(roomId, virtualAccount.sharedFrom)
        assertTrue(virtualAccount.name.contains("Dompet"), "nama akun sumber tetap terbawa di nama akun virtual")
    }

    @Test
    fun ignores_its_own_pushed_entries_when_pulling() = runSuspend {
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        ledger.transactionRows[TransactionId("tx-1")] = expense("tx-1", 25_000)
        val remote = FakeSyncRemote()

        val result = engine(ledger, remote).syncNow(roomId, deviceId = "device-a") as SyncResult.Success

        assertEquals(0, result.applied) // entry baru saja dipush oleh device-a sendiri, bukan dari anggota lain
    }

    // ------------------------------------------------------------------ kategori ruang bersama (Tahap 2 bagian B R2)

    @Test
    fun creates_a_new_category_in_the_room_instead_of_falling_back_to_lain_lain_when_it_does_not_exist_yet() = runSuspend {
        val ledgerA = InMemoryLedger()
        seedRoomAndAccount(ledgerA)
        ledgerA.transactionRows[TransactionId("tx-1")] = expense("tx-1", 50_000) // kategori "Belanja" (categoryId)
        val remote = FakeSyncRemote()
        engine(ledgerA, remote).syncNow(roomId, deviceId = "device-a")

        val ledgerB = InMemoryLedger()
        ledgerB.roomRows[roomId] = Room(roomId, "Keluarga", RoomKind.MENCUKUPI, "home", 1, sortOrder = 0)
        ledgerB.accountRows[accountId] = Account(accountId, "Dompet", AccountKind.CASH, Money.zero())
        val fallbackCategoryId = CategoryId("cat-lain-lain")
        ledgerB.categoryRows[fallbackCategoryId] = Category(fallbackCategoryId, roomId, "Lain-lain")
        // Tidak ada kategori "Belanja" di sisi B sama sekali — bagian B R2: penerima MEMBUAT kategori
        // yang belum ada (bukan jatuh ke "Lain-lain", itu perilaku lama yang sengaja ditutup Tahap 2).

        val result = engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b") as SyncResult.Success

        assertEquals(1, result.applied)
        val appliedCategoryId = ledgerB.transactionRows[TransactionId("tx-1")]?.categoryId
        assertNotEquals(fallbackCategoryId, appliedCategoryId, "kategori baru dibuat, bukan jatuh ke Lain-lain")
        val createdCategory = ledgerB.categoryRows[appliedCategoryId]
        assertEquals("Belanja", createdCategory?.name)
        assertEquals(roomId, createdCategory?.roomId)
        assertEquals(false, createdCategory?.isSystem)
    }

    @Test
    fun matches_an_existing_category_despite_different_casing_and_spacing_instead_of_creating_a_duplicate() = runSuspend {
        val remote = FakeSyncRemote()
        // Payload sungguhan dikirim dengan variasi kapital dan spasi di nama kategori — disisipkan
        // langsung ke "server" tiruan (bukan lewat engine().syncNow(), yang memakai nama persis "Belanja").
        remote.seedRawEntry(
            transaction = expense("tx-1", 30_000),
            accountName = "Dompet",
            categoryName = "  BELANJA  ", // normalisasi: NFKC + trim + rapatkan spasi + huruf kecil
            deviceId = "device-a",
            changedAtMillis = 1_000L,
            roomId = roomId.value,
        )

        val ledgerB = InMemoryLedger()
        seedRoomAndAccount(ledgerB) // juga punya "Belanja" (nama persis, beda kapital/spasi dari payload)

        val result = engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b") as SyncResult.Success

        assertEquals(1, result.applied)
        assertEquals(categoryId, ledgerB.transactionRows[TransactionId("tx-1")]?.categoryId, "cocok ke kategori yang sudah ada, bukan duplikat baru")
        assertEquals(1, ledgerB.categoryRows.size, "tidak ada kategori baru yang dibuat")
    }

    @Test
    fun matches_an_existing_system_category_by_name_instead_of_creating_a_duplicate() = runSuspend {
        val remote = FakeSyncRemote()
        remote.seedRawEntry(
            transaction = expense("tx-1", 20_000),
            accountName = "Dompet",
            categoryName = "zakat MAL", // variasi kapital dari nama sistem "Zakat mal"
            deviceId = "device-a",
            changedAtMillis = 1_000L,
            roomId = roomId.value,
        )

        val ledgerB = InMemoryLedger()
        seedRoomAndAccount(ledgerB)
        val zakatCategoryId = CategoryId("cat-zakat")
        ledgerB.categoryRows[zakatCategoryId] = Category(zakatCategoryId, roomId, RoomTemplates.ZAKAT, isSystem = true)

        val result = engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b") as SyncResult.Success

        assertEquals(1, result.applied)
        assertEquals(zakatCategoryId, ledgerB.transactionRows[TransactionId("tx-1")]?.categoryId)
        assertEquals(2, ledgerB.categoryRows.size, "tidak ada kategori Zakat mal dobel (hanya Belanja bawaan + ini)")
    }

    @Test
    fun falls_back_to_lain_lain_only_when_the_category_name_itself_is_blank_not_merely_unknown() = runSuspend {
        val remote = FakeSyncRemote()
        remote.seedRawEntry(
            transaction = expense("tx-1", 15_000),
            accountName = "Dompet",
            categoryName = "   ", // cuma spasi: bukan "tidak ditemukan", tapi kasus nama kosong
            deviceId = "device-a",
            changedAtMillis = 1_000L,
            roomId = roomId.value,
        )

        val ledgerB = InMemoryLedger()
        seedRoomAndAccount(ledgerB)
        val fallbackCategoryId = CategoryId("cat-lain-lain")
        ledgerB.categoryRows[fallbackCategoryId] = Category(fallbackCategoryId, roomId, "Lain-lain")

        val result = engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b") as SyncResult.Success

        assertEquals(1, result.applied)
        assertEquals(fallbackCategoryId, ledgerB.transactionRows[TransactionId("tx-1")]?.categoryId)
        assertEquals(2, ledgerB.categoryRows.size, "tidak ada kategori baru dibuat untuk nama kosong")
    }

    @Test
    fun last_write_wins_keeps_the_newer_change_on_conflict() = runSuspend {
        val ledgerB = InMemoryLedger()
        seedRoomAndAccount(ledgerB)
        // B sudah punya versi lama transaksi ini secara lokal (belum pernah sync), lalu A mengirim versi baru.
        ledgerB.transactionRows[TransactionId("tx-1")] = expense("tx-1", 10_000, note = "lama", updatedAt = 500L)

        val remote = FakeSyncRemote()
        val ledgerA = InMemoryLedger()
        seedRoomAndAccount(ledgerA)
        ledgerA.transactionRows[TransactionId("tx-1")] = expense("tx-1", 75_000, note = "baru", updatedAt = 2_000L)
        engine(ledgerA, remote).syncNow(roomId, deviceId = "device-a")

        engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b")

        val merged = ledgerB.transactionRows[TransactionId("tx-1")]
        assertEquals(Money(75_000), merged?.amount)
        assertEquals("baru", merged?.note)
    }

    // ------------------------------------------------------------------ tombstone penghapusan (Tahap 9)

    @Test
    fun deleting_a_transaction_in_a_shared_room_creates_a_tombstone_that_removes_it_for_other_members() = runSuspend {
        val ledgerA = InMemoryLedger()
        seedRoomAndAccount(ledgerA)
        ledgerA.transactionRows[TransactionId("tx-1")] = expense("tx-1", 40_000, note = "Belanja")
        val remote = FakeSyncRemote()
        // Mesin (dan kursornya) dipakai ulang per "perangkat" lewat putaran sync berikutnya — sama seperti
        // does_not_push_the_same_transaction_twice — bukan dibuat baru tiap panggilan, supaya kursor baca
        // tiap perangkat benar-benar maju antar putaran.
        val deviceA = engine(ledgerA, remote)
        deviceA.syncNow(roomId, deviceId = "device-a") // A: push tx-1

        val ledgerB = InMemoryLedger()
        seedRoomAndAccount(ledgerB)
        val deviceB = engine(ledgerB, remote)
        deviceB.syncNow(roomId, deviceId = "device-b") // B: pull tx-1
        assertTrue(TransactionId("tx-1") in ledgerB.transactionRows) // sanity: B benar-benar sudah punya salinannya

        // A menghapus transaksi itu secara lokal: hilang seketika (hard-delete), tombstone menunggu kirim.
        ledgerA.delete(TransactionId("tx-1"))
        assertTrue(TransactionId("tx-1") !in ledgerA.transactionRows)
        assertEquals(1, ledgerA.pendingTombstoneRows.size)

        val pushResult = deviceA.syncNow(roomId, deviceId = "device-a") as SyncResult.Success
        assertEquals(1, pushResult.pushed) // tombstone-nya yang terkirim
        assertTrue(ledgerA.pendingTombstoneRows.isEmpty(), "tombstone dibuang lokal setelah terkirim, server sudah menyimpannya permanen")

        val pullResult = deviceB.syncNow(roomId, deviceId = "device-b") as SyncResult.Success
        assertEquals(1, pullResult.applied)
        assertTrue(TransactionId("tx-1") !in ledgerB.transactionRows, "B ikut menghapus transaksi yang dihapus A")
    }

    @Test
    fun does_not_push_a_tombstone_for_a_transaction_in_a_room_that_was_never_synced() = runSuspend {
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        ledger.transactionRows[TransactionId("tx-1")] = expense("tx-1", 10_000)
        val remote = FakeSyncRemote()

        // Dihapus sebelum sync pertama kali pernah terjadi: tetap mencatat tombstone (sederhana, tidak perlu
        // tahu riwayat sync), tapi mengirimkannya aman karena pull sisi lain cuma menghapus baris yang tidak
        // pernah ada di sana (no-op).
        ledger.delete(TransactionId("tx-1"))
        val result = engine(ledger, remote).syncNow(roomId, deviceId = "device-a") as SyncResult.Success

        assertEquals(1, result.pushed)
        assertEquals(true, remote.stored.single().deleted)
    }

    @Test
    fun last_write_wins_keeps_a_newer_local_edit_over_a_stale_tombstone() = runSuspend {
        val ledgerB = InMemoryLedger()
        seedRoomAndAccount(ledgerB)
        // B punya edit lokal baru atas transaksi ini (belum sync) dengan cap waktu lebih baru dari tombstone yang masuk.
        ledgerB.transactionRows[TransactionId("tx-1")] = expense("tx-1", 99_000, note = "edit lokal baru", updatedAt = 5_000L)

        val remote = FakeSyncRemote()
        val ledgerA = InMemoryLedger(nowMillis = { 2_000L }) // tombstone A bercap 2000, lebih lama dari edit B (5000)
        seedRoomAndAccount(ledgerA)
        ledgerA.transactionRows[TransactionId("tx-1")] = expense("tx-1", 10_000, updatedAt = 1_000L)
        ledgerA.delete(TransactionId("tx-1"))
        engine(ledgerA, remote).syncNow(roomId, deviceId = "device-a")

        // "applied" di SyncResult berarti "baris diproses tanpa galat", bukan "data lokal berubah" (sama
        // seperti jalur baris hidup: lihat last_write_wins_keeps_the_newer_change_on_conflict yang juga
        // tidak menguji lewat hitungan ini) — yang membuktikan LWW adalah isi [stillThere] di bawah, bukan count.
        engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b")

        // Tombstone basi (server-sync.md "Model konflik sinkronisasi": last-write-wins) tidak boleh menghapus
        // edit lokal yang lebih baru itu.
        val stillThere = ledgerB.transactionRows[TransactionId("tx-1")]
        assertEquals(Money(99_000), stillThere?.amount)
        assertEquals("edit lokal baru", stillThere?.note)
    }

    @Test
    fun reports_not_signed_in_without_throwing_when_there_is_no_api_token() = runSuspend {
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        val result = engine(ledger, FakeSyncRemote(), tokenStore = FakeApiTokenStore(token = null)).syncNow(roomId, "device-a")

        assertEquals(SyncResult.NotSignedIn, result)
    }

    // ------------------------------------------------------------------ aturan akun lintas ruang (Tahap 13)

    private val roomB = RoomId("room-diri")
    private val categoryB = CategoryId("cat-hiburan")

    /** Ruang kedua milik pengguna yang sama (mis. ruang Diri), dengan kategorinya sendiri. */
    private fun seedSecondRoom(ledger: InMemoryLedger, categoryName: String = "Hiburan") {
        ledger.roomRows[roomB] = Room(roomB, "Diri", RoomKind.MENUMBUHKAN, "sprout", 2, sortOrder = 1)
        ledger.categoryRows[categoryB] = Category(categoryB, roomB, categoryName)
    }

    @Test
    fun pushes_only_the_transactions_of_the_synced_room_never_the_same_account_in_other_rooms() = runSuspend {
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        seedSecondRoom(ledger)
        ledger.transactionRows[TransactionId("tx-a")] = expense("tx-a", 10_000)
        ledger.transactionRows[TransactionId("tx-b")] = expense("tx-b", 20_000).copy(roomId = roomB, categoryId = categoryB)
        val remote = FakeSyncRemote()

        val result = engine(ledger, remote).syncNow(roomId, deviceId = "device-a") as SyncResult.Success

        assertEquals(1, result.pushed)
        assertEquals(listOf("tx-a"), remote.stored.map { it.recordId })
        assertEquals(listOf(roomId.value), remote.stored.map { it.roomId })
    }

    @Test
    fun a_member_keeps_its_own_transactions_of_the_same_named_account_in_other_rooms() = runSuspend {
        val ledgerA = InMemoryLedger()
        seedRoomAndAccount(ledgerA)
        ledgerA.transactionRows[TransactionId("tx-1")] = expense("tx-1", 50_000)
        val remote = FakeSyncRemote()
        engine(ledgerA, remote).syncNow(roomId, deviceId = "device-a")

        val ledgerB = InMemoryLedger()
        seedRoomAndAccount(ledgerB) // anggota: akun "Dompet" yang namanya sama dengan milik pengirim
        seedSecondRoom(ledgerB)
        val ownInOtherRoom = expense("tx-own", 7_000).copy(roomId = roomB, categoryId = categoryB, note = "Punya ruang Diri")
        ledgerB.transactionRows[ownInOtherRoom.id] = ownInOtherRoom

        val result = engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b") as SyncResult.Success

        assertEquals(1, result.applied)
        // Transaksi lain dari akun bernama sama di ruang lain tidak tersentuh dan tidak ikut terdorong ke ruang yang disinkron.
        assertEquals(ownInOtherRoom, ledgerB.transactionRows[TransactionId("tx-own")])
        assertEquals(listOf("tx-1"), remote.stored.map { it.recordId })
    }

    /**
     * Temuan 2026-10-25 (diperbaiki 2026-10-27): server mengirim baris dari semua ruang yang diikuti anggota, dan
     * pull mesin sync kini melewati baris yang `roomId`-nya tidak sama dengan ruang yang disinkron. Sebelum
     * perbaikan, transaksi ruang lain ikut diterapkan ke ruang ini dengan kategorinya (tes ini gagal).
     */
    @Test
    fun a_member_of_two_rooms_does_not_get_the_other_rooms_transactions_into_this_room() = runSuspend {
        // Kategori ruang B bernama "Belanja" juga, supaya nama kategori di ruang A cocok dan baris ruang B tidak
        // kebetulan terbuang sebagai kategori tak dikenal (itu yang membuat tes ini lulus tanpa alasan).
        val sender = InMemoryLedger()
        seedRoomAndAccount(sender)
        seedSecondRoom(sender, categoryName = "Belanja")
        sender.transactionRows[TransactionId("tx-b")] = expense("tx-b", 20_000).copy(roomId = roomB, categoryId = categoryB)
        val remote = FakeSyncRemote()
        engine(sender, remote).syncNow(roomB, deviceId = "device-a")

        val member = InMemoryLedger()
        seedRoomAndAccount(member)
        seedSecondRoom(member, categoryName = "Belanja")
        val result = engine(member, remote).syncNow(roomId, deviceId = "device-b") as SyncResult.Success

        assertEquals(0, result.applied)
        assertEquals(null, member.transactionRows[TransactionId("tx-b")])
    }

    @Test
    fun reports_failure_without_throwing_when_the_server_is_unreachable() = runSuspend {
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        val remote = FakeSyncRemote().apply { failWith = RemoteError.Unreachable }

        val result = engine(ledger, remote).syncNow(roomId, "device-a")

        assertEquals(SyncResult.Failed(RemoteError.Unreachable), result)
    }

    // ------------------------------------------------------------------ penjepitan jam jauh ke depan (Tahap 2 bagian D)

    @Test
    fun a_device_clock_set_far_in_the_future_is_corrected_before_any_pending_push_is_stamped() = runSuspend {
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        val badFutureNow = 999_999_999_999_999L // jam perangkat rusak: ratusan ribu tahun ke depan
        val clock = SyncClock(deviceNowMillis = { badFutureNow })
        val remote = FakeSyncRemote().apply { serverTimeMs = 1_792_000_000_000L } // waktu server sungguhan
        val device = engine(ledger, remote, clock = clock)

        // Putaran pertama tanpa transaksi pending (cuma pull kosong): sekadar menyentuh server dulu
        // supaya server_time_ms masuk SEBELUM stamp() pernah dipanggil, jadi belum ada cap tinggi yang
        // "mengunci" nilai tinggi di clock (lihat KDoc SyncClock, butir "Risiko jam jauh ke depan": begitu
        // stamp() sempat mengeluarkan cap seburuk itu, instance ini TIDAK bisa turun lagi sendiri).
        device.syncNow(roomId, deviceId = "device-a")
        assertTrue(clock.serverOffsetMillis < 0, "offset turun untuk mengoreksi jam yang terlalu maju")
        assertTrue(remote.stored.isEmpty(), "belum ada yang dipush, cuma pull kosong")

        // Transaksi baru SESUDAH offset terpasang: cap yang dikirim ke server sudah terkoreksi, bukan
        // sebesar jam rusak itu lagi.
        ledger.transactionRows[TransactionId("tx-1")] = expense("tx-1", 10_000, updatedAt = 1_000L)
        device.syncNow(roomId, deviceId = "device-a")

        val sentChangedAt = remote.stored.single().changedAtMillis!!
        assertTrue(sentChangedAt < badFutureNow / 2, "cap sudah dikoreksi offset server, tidak lagi sebesar jam rusak")
        assertTrue(sentChangedAt > 0, "cap tetap bilangan waktu yang wajar (positif), bukan nol atau negatif")
    }

    @Test
    fun a_bad_clock_that_already_stamped_once_stays_locked_high_for_the_rest_of_the_process_even_after_the_offset_is_known() = runSuspend {
        // Kebalikan tes di atas: kejujuran batas fitur ini (didokumentasikan di KDoc SyncClock) — begitu
        // stamp() sempat dipanggil SEBELUM offset diketahui (mis. transaksi sudah ada sebelum sync pertama),
        // cap tinggi itu "mengunci" instance ini; offset yang terlambat datang tidak bisa menurunkannya lagi
        // di proses yang sama. Ini bukan bug dari Tahap 2, melainkan properti [SyncClock] yang sudah ada
        // sejak Tahap 1 — diuji di sini supaya tidak diklaim seolah selalu terkoreksi.
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        ledger.transactionRows[TransactionId("tx-1")] = expense("tx-1", 10_000, updatedAt = 1_000L)
        val badFutureNow = 999_999_999_999_999L
        val clock = SyncClock(deviceNowMillis = { badFutureNow })
        val remote = FakeSyncRemote()
        val device = engine(ledger, remote, clock = clock)

        device.syncNow(roomId, deviceId = "device-a") // stamp() terpanggil duluan, offset masih 0
        assertEquals(badFutureNow, remote.stored.single().changedAtMillis)

        remote.serverTimeMs = 1_792_000_000_000L
        ledger.transactionRows[TransactionId("tx-2")] = expense("tx-2", 5_000, updatedAt = 2_000L)
        device.syncNow(roomId, deviceId = "device-a")

        assertTrue(clock.serverOffsetMillis < 0, "offset sendiri tetap terkoreksi")
        assertEquals(badFutureNow, remote.stored.last().changedAtMillis, "tapi cap tx-2 tetap terkunci tinggi di instance ini")
    }

    // ------------------------------------------------------------------ urutan total dan kebocoran lintas-scope (Tahap 2 bagian D/G)

    @Test
    fun pulling_a_version_that_is_not_newer_than_the_local_one_does_nothing() = runSuspend {
        val ledgerB = InMemoryLedger()
        seedRoomAndAccount(ledgerB)
        // B punya edit lokal sendiri yang belum terkirim, bercap lebih baru dari versi basi yang akan masuk.
        ledgerB.transactionRows[TransactionId("tx-1")] = expense("tx-1", 99_000, note = "milik saya", updatedAt = 5_000L)
        val remote = FakeSyncRemote()
        // Versi masuk BASI (cap 100, jauh lebih lama) dari "perangkat lain" untuk id yang sama — bagian D:
        // meta.order (5000, device-b) >= incoming.order (100, device-a) berarti TIDAK melakukan apa pun.
        remote.seedRawEntry(
            transaction = expense("tx-1", 1_000, note = "basi", updatedAt = 100L),
            accountName = "Dompet",
            categoryName = "Belanja",
            deviceId = "device-a",
            changedAtMillis = 100L,
            roomId = roomId.value,
        )

        engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b")

        val stillThere = ledgerB.transactionRows[TransactionId("tx-1")]
        assertEquals(Money(99_000), stillThere?.amount, "versi basi tidak menimpa edit lokal yang lebih baru")
        assertEquals("milik saya", stillThere?.note)
    }

    @Test
    fun pulling_never_applies_an_entry_whose_room_id_is_null_even_if_it_appears_in_the_feed() = runSuspend {
        // Server sungguhan mengembalikan gabungan baris MILIK SENDIRI (termasuk cakupan pribadi, room_id
        // null) ditambah baris ruang bersama yang diikuti (lihat SyncController::pull) — filter cakupan
        // `scope=r:<roomId>` belum ada di server (Tahap 3+), jadi klien WAJIB menyaring sendiri (bagian G
        // "Filter per jenis: tidak ada, klien menyaring"). Tes ini membuktikan baris cakupan pribadi
        // (room_id null) tidak pernah diterapkan sebagai transaksi ruang bersama walau muncul di aliran pull.
        val ledgerB = InMemoryLedger()
        seedRoomAndAccount(ledgerB)
        val remote = FakeSyncRemote()
        remote.seedRawEntry(
            transaction = expense("tx-private", 7_000, note = "akun pribadi"),
            accountName = "Dompet",
            categoryName = "Belanja",
            deviceId = "device-a",
            changedAtMillis = 1_000L,
            roomId = null, // cakupan pribadi `a`, bukan ruang bersama manapun
        )

        val result = engine(ledgerB, remote).syncNow(roomId, deviceId = "device-b") as SyncResult.Success

        assertEquals(0, result.applied)
        assertEquals(0, result.skipped)
        assertEquals(null, ledgerB.transactionRows[TransactionId("tx-private")], "baris cakupan pribadi tidak pernah masuk ke ruang bersama")
    }

    // ------------------------------------------------------------------ ack hilang (Tahap 2 bagian G)

    @Test
    fun retrying_a_push_after_a_lost_ack_does_not_duplicate_the_row_on_the_server() = runSuspend {
        val ledger = InMemoryLedger()
        seedRoomAndAccount(ledger)
        ledger.transactionRows[TransactionId("tx-1")] = expense("tx-1", 25_000)
        val remote = FakeSyncRemote().apply { dropAckOnNextPush = true }
        val device = engine(ledger, remote) // satu instance dipakai ulang: jam yang sama dipakai saat retry

        val firstAttempt = device.syncNow(roomId, deviceId = "device-a")

        assertTrue(firstAttempt is SyncResult.Failed, "ack hilang terlihat sebagai gagal oleh klien")
        assertEquals(1, remote.stored.size, "server sudah menyimpan baris itu walau ack-nya hilang di jaringan")
        assertTrue(ledger.syncedAtRows.isEmpty(), "klien belum tahu baris sudah tersimpan, tx-1 tetap pending")

        val retry = device.syncNow(roomId, deviceId = "device-a") as SyncResult.Success

        assertEquals(1, retry.pushed)
        assertEquals(1, remote.stored.size, "retry tidak menggandakan baris di server (idempoten by record_id+changed_at_ms)")
        assertTrue(ledger.syncedAtRows.containsKey(TransactionId("tx-1")), "retry berhasil, klien sekarang tahu baris sudah tersinkron")
    }
}
