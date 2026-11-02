package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.ledger.AccountRepository
import com.roziqrizal.rizqflow.domain.ledger.Category
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.RoomRepository
import com.roziqrizal.rizqflow.domain.ledger.RoomTemplates
import com.roziqrizal.rizqflow.domain.ledger.TransactionRepository
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import kotlinx.serialization.json.JsonObject
import java.util.UUID

private const val FALLBACK_CATEGORY_NAME = "Lain-lain"

/**
 * Penangan [SharedRecordType.SHARED_EXPENSE] (pengeluaran EXPENSE di ruang bersama) lewat [SharedRoomSyncHandler],
 * dipakai [SyncCoordinator]. Memindahkan logika yang sebelumnya ada langsung di kelas itu (lihat riwayat git),
 * tanpa mengubah perilaku: akun anggota virtual (keputusan pemilik #0), kategori dibuat otomatis lewat nama
 * ternormalisasi (sync-desain.md bagian B R2), dan tombstone hanya menghapus baris yang masih di ruang ini.
 *
 * [applyDecodedTransaction] dan [hasFallbackCategory] juga dipakai [SyncCoordinator] langsung untuk baris format
 * lama (sebelum amplop umum, `record_type = transaction`) yang decode-nya lewat [TransactionSyncCodec] bukan
 * [SharedExpensePayload] — format itu mendahului [SharedRoomSyncHandler] dan hanya pernah membawa pengeluaran.
 */
class SharedExpenseSyncHandler(
    private val transactions: TransactionRepository,
    private val rooms: RoomRepository,
    private val accounts: AccountRepository,
) : SharedRoomSyncHandler {
    override val type: SharedRecordType = SharedRecordType.SHARED_EXPENSE
    override val knownVersion: Int = SharedExpensePayload.VERSION

    override suspend fun collectDirty(roomId: RoomId): List<OutgoingRecord> {
        val pending = transactions.pendingSync(roomId)
        // Tombstone yang masih menunggu untuk transaksi yang kini punya versi hidup baru di ruang ini (urungkan
        // hapus, kembali ke ruang ini setelah dipindah keluar) sudah gugur: dua-duanya dikirim bersamaan, tombstone
        // yang capnya lebih besar menang dan versi hidup hilang. Repositori sudah menggugurkannya saat menulis;
        // ini jaring pengaman.
        val pendingIds = pending.mapTo(HashSet()) { it.transaction.id }
        val (superseded, pendingDeletes) = transactions.pendingTombstones(roomId).partition { it.transactionId in pendingIds }
        if (superseded.isNotEmpty()) transactions.clearPendingTombstones(roomId, superseded.map { it.transactionId })
        // Jalan pintas (sama seperti sebelum Tahap 2 lanjutan): ruang yang sudah bersih tidak perlu membaca semua
        // akun dan kategori hanya untuk melabeli payload yang tidak akan dibuat — auto-sync memanggil ini tiap
        // beberapa menit untuk setiap ruang aktif.
        if (pending.isEmpty() && pendingDeletes.isEmpty()) return emptyList()

        val accountNames = accounts.allAccounts().associate { it.id to it.name }
        val categoryNames = rooms.allCategories().filter { it.roomId == roomId }.associate { it.id to it.name }

        val live = pending.map { entry ->
            val tx = entry.transaction
            val data = SharedExpensePayload.encode(tx, accountNames[tx.accountId] ?: "", tx.categoryId?.let { categoryNames[it] })
            OutgoingRecord(tx.id.value, knownVersion, entry.changedAtMillis, deleted = false, data)
        }
        val deletes = pendingDeletes.map { tombstone ->
            OutgoingRecord(tombstone.transactionId.value, knownVersion, tombstone.deletedAtMillis, deleted = true, JsonObject(emptyMap()))
        }
        return live + deletes
    }

    override suspend fun missingReferences(roomId: RoomId, decoded: DecodedRecord): List<String> {
        if (decoded.data.isEmpty()) return emptyList() // tombstone: hapus tidak butuh rujukan ada.
        // "Lain-lain" hanya dibutuhkan bila nama kategori kosong/null (findOrCreateSharedCategory jatuh ke situ);
        // nama tidak kosong selalu dicocokkan atau dibuat baru, tidak pernah butuh "Lain-lain" ada lebih dulu.
        val categoryName = try {
            SharedExpensePayload.decode(decoded.id, decoded.data).categoryName
        } catch (e: TransactionSyncPayloadCorrupt) {
            return emptyList() // payload rusak: biarkan apply() yang menolaknya sebagai TAMPERED_OR_CORRUPT
        }
        if (!categoryName.isNullOrBlank()) return emptyList()
        return if (hasFallbackCategory(roomId)) emptyList() else listOf("category:$FALLBACK_CATEGORY_NAME")
    }

    override suspend fun apply(roomId: RoomId, decoded: DecodedRecord, changedAtMillis: Long, author: String) {
        val id = TransactionId(decoded.id)
        if (decoded.data.isEmpty()) {
            transactions.applyFromSync(id, null, changedAtMillis, deleted = true, fromRoom = roomId)
            return
        }
        applyDecodedTransaction(roomId, id, SharedExpensePayload.decode(decoded.id, decoded.data), changedAtMillis, author)
    }

    override suspend fun markPushed(roomId: RoomId, sent: List<OutgoingRecord>) {
        val sentChangedAtById = sent.filter { !it.deleted }.associate { it.recordId to it.changedAtMillis }
        if (sentChangedAtById.isNotEmpty()) {
            // Hanya versi yang BENAR-BENAR terkirim (changed_at masih sama dengan yang dikirim) yang ditandai;
            // markSyncedIfUnchanged sendiri yang menegakkan ini di lapisan data, cek di sini hanya memilih baris yang
            // relevan dari pendingSync saat ini (bisa sudah berubah sejak collectDirty membacanya).
            val matching = transactions.pendingSync(roomId).filter { sentChangedAtById[it.transaction.id.value] == it.changedAtMillis }
            if (matching.isNotEmpty()) transactions.markSyncedIfUnchanged(matching, System.currentTimeMillis())
        }
        val sentDeleteIds = sent.filter { it.deleted }.map { TransactionId(it.recordId) }
        if (sentDeleteIds.isNotEmpty()) transactions.clearPendingTombstones(roomId, sentDeleteIds)
    }

    /**
     * @return [RowOutcome.APPLIED] selalu (dipanggil setelah [missingReferences]/[hasFallbackCategory] memastikan
     *   "Lain-lain" ada). Akun anggota virtual (keputusan pemilik #0): TIDAK PERNAH mencocokkan ke akun nyata
     *   penerima. Satu akun virtual per (ruang, penulis, nama akun), idempoten lewat id deterministik.
     */
    suspend fun applyDecodedTransaction(roomId: RoomId, id: TransactionId, decoded: DecodedSyncTransaction, changedAtMillis: Long, author: String) {
        val account = accounts.findOrCreateVirtualMember(roomId, author, decoded.accountName)
        val categoriesInRoom = rooms.allCategories().filter { it.roomId == roomId }
        val category = findOrCreateSharedCategory(roomId, categoriesInRoom, decoded.categoryName)
            ?: error("Ruang $roomId tidak punya kategori Lain-lain walau hasFallbackCategory sudah memastikannya")

        val transaction = MoneyTransaction(
            id = id,
            kind = TransactionKind.EXPENSE,
            amount = decoded.amount,
            accountId = account.id,
            roomId = roomId,
            categoryId = category.id,
            occurredOn = decoded.occurredOn,
            note = decoded.note,
            // SHARED (bukan IMPORT): pengeluaran anggota lain di ruang bersama bukan data pribadi pengguna ini, jadi
            // pencatat dan backfill sinkron entitas penuh mengecualikannya dari cakupan pribadi. Tidak ada kode yang
            // membaca origin untuk saldo, jatah, riwayat, laporan, atau ekspor; hanya tulis.
            origin = TransactionOrigin.SHARED,
            createdAtMillis = changedAtMillis,
            updatedAtMillis = changedAtMillis,
        )
        transactions.applyFromSync(id, transaction, changedAtMillis, deleted = false)
    }

    /** Dipakai [missingReferences] dan [SyncCoordinator] (baris format lama) — sama kriteria, satu sumber kebenaran. */
    suspend fun hasFallbackCategory(roomId: RoomId): Boolean = findFallbackCategory(roomId, rooms.allCategories().filter { it.roomId == roomId }) != null

    private fun findFallbackCategory(roomId: RoomId, categoriesInRoom: List<Category>): Category? {
        val fallbackNormalized = SharedRoomNameNormalization.normalize(FALLBACK_CATEGORY_NAME)
        return categoriesInRoom.firstOrNull { SharedRoomNameNormalization.normalize(it.name) == fallbackNormalized }
    }

    /**
     * Kategori ruang bersama dikenali lewat nama ternormalisasi (sync-desain.md bagian B R2, keputusan pemilik):
     * dicari dulu di [categoriesInRoom]; kalau tidak ketemu, DIBUAT baru di [roomId] (bukan jatuh ke "Lain-lain").
     * Normalisasi NFKC + trim + rapatkan spasi + huruf kecil ([SharedRoomNameNormalization]) menyamakan variasi
     * kapital/spasi, jadi kategori sistem `Zakat mal`/`Tak terlacak` yang sudah ada di ruang ini dicocokkan lewat
     * nama seperti kategori lain dan TIDAK PERNAH dibuat dobel.
     *
     * [rawName] kosong atau cuma spasi (null maupun string) adalah kasus BERBEDA dari "tidak ditemukan": selalu
     * jatuh ke "Lain-lain", tidak pernah membuat kategori baru bernama kosong.
     *
     * @return null hanya bila ruang ini bahkan tidak punya kategori "Lain-lain" (seharusnya mustahil: setiap ruang
     *   dibuat dengan kategori itu, lihat `RoomTemplates`/`WorkspaceSetup`).
     */
    private suspend fun findOrCreateSharedCategory(roomId: RoomId, categoriesInRoom: List<Category>, rawName: String?): Category? {
        val trimmed = rawName?.trim()
        if (trimmed.isNullOrEmpty()) return findFallbackCategory(roomId, categoriesInRoom)

        val normalized = SharedRoomNameNormalization.normalize(trimmed)
        categoriesInRoom.firstOrNull { SharedRoomNameNormalization.normalize(it.name) == normalized }?.let { return it }

        // Tidak ketemu: buat baru. Nama tampilan tetap nama asli dari pengirim (hanya dipotong bila melebihi batas
        // panjang kategori), normalisasi di atas hanya dipakai untuk PENCARIAN.
        val isSystem = normalized == SharedRoomNameNormalization.normalize(RoomTemplates.ZAKAT) ||
            normalized == SharedRoomNameNormalization.normalize(RoomTemplates.UNTRACKED)
        val created = Category(
            id = CategoryId(UUID.randomUUID().toString()),
            roomId = roomId,
            name = trimmed.take(Category.NAME_MAX),
            isSystem = isSystem,
            sortOrder = (categoriesInRoom.maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        rooms.saveCategory(created)
        return created
    }
}
