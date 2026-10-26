package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.ledger.AccountRepository
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.RoomRepository
import com.roziqrizal.rizqflow.domain.ledger.TransactionRepository
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import java.time.Instant
import java.util.Base64

private const val RECORD_TYPE_TRANSACTION = "transaction"
private const val FALLBACK_CATEGORY_NAME = "Lain-lain"

/**
 * Sync push/pull per cakupan (Tahap 2 sync-desain.md, menggantikan `SyncEngine` lama). Untuk putaran
 * ini hanya cakupan **ruang bersama** yang berjalan — [SyncScope.SharedRoom] via [syncNow] — karena
 * cakupan pribadi menunggu handler per tipe (Tahap 3 dan seterusnya, lihat `SyncRecordHandler`).
 * Hanya transaksi EXPENSE yang disync (lihat [TransactionSyncCodec]); transfer, pemasukan,
 * utang-piutang, tagihan, dan transaksi berulang menyusul terpisah.
 *
 * Perbaikan Tahap 2 atas `SyncEngine` lama (sync-desain.md bagian 0, keputusan pemilik #0):
 * - **Akun anggota virtual** (bagian B/E): pengeluaran anggota lain tidak lagi dicocokkan ke akun
 *   lokal penerima lewat nama (bug lama: menggeser saldo akun nyata bila namanya sama persis, atau
 *   melewati baris itu bila tidak ada akun bernama sama). Sekarang selalu dibuat/dipakai satu akun
 *   anggota virtual per (ruang, penulis, nama akun) lewat [AccountRepository.findOrCreateVirtualMember]
 *   — tidak pernah menyentuh akun nyata, dan tidak pernah dilewati karena "akun tak dikenal".
 * - **Penulis tunggal** (bagian B R3): server menentukan dan mengirim [SyncLogEntryDownload.author]
 *   (penulis PERTAMA baris ini); baris dari penulis lain untuk id yang sama ditolak server saat push
 *   (lihat `SyncController::push`), bukan di sini — klien hanya memakai [author] untuk label akun
 *   virtual, tidak memutuskan siapa menang.
 * - **`server_time_ms`**: dipakai menjepit [clock] lewat [SyncClock.setServerOffset] setiap putaran
 *   berhasil, supaya jam perangkat yang salah tidak membuat cap waktu lokal makin jauh melenceng.
 *
 * "Tenang, bukan panik": kegagalan jaringan/server selalu [SyncResult.Failed], tidak pernah melempar.
 */
class SyncCoordinator(
    private val transactions: TransactionRepository,
    private val rooms: RoomRepository,
    private val accounts: AccountRepository,
    private val roomKeys: RoomKeyProvider,
    private val syncRemote: SyncRemote,
    private val codec: TransactionSyncCodec,
    private val apiTokenStore: ApiTokenStore,
    private val cursorStore: SyncCursorStore,
    private val clock: SyncClock = SyncClock(),
) {
    suspend fun syncNow(roomId: RoomId, deviceId: String): SyncResult {
        val apiToken = apiTokenStore.read() ?: return SyncResult.NotSignedIn
        val aead = roomKeys.getOrCreateRoomKey(roomId)

        when (val pushed = push(roomId, deviceId, apiToken, aead)) {
            is RemoteResult.Failure -> return SyncResult.Failed(pushed.error)
            is RemoteResult.Success -> {
                return when (val pulled = pull(roomId, deviceId, apiToken, aead)) {
                    is RemoteResult.Failure -> SyncResult.Failed(pulled.error)
                    is RemoteResult.Success -> SyncResult.Success(
                        pushed = pushed.value,
                        applied = pulled.value.applied,
                        skipped = pulled.value.skipped,
                    )
                }
            }
        }
    }

    private suspend fun push(roomId: RoomId, deviceId: String, apiToken: String, aead: SyncAead): RemoteResult<Int> {
        val pending = transactions.pendingSync(roomId)
        val pendingDeletes = transactions.pendingTombstones(roomId)
        if (pending.isEmpty() && pendingDeletes.isEmpty()) return RemoteResult.Success(0)

        val accountNames = accounts.allAccounts().associate { it.id to it.name }
        val categoryNames = rooms.allCategories().filter { it.roomId == roomId }.associate { it.id to it.name }

        val liveUploads = pending.map { entry ->
            val transaction = entry.transaction
            val plaintext = codec.encode(
                transaction = transaction,
                accountName = accountNames[transaction.accountId] ?: "",
                categoryName = transaction.categoryId?.let { categoryNames[it] },
            )
            val ciphertext = aead.encrypt(plaintext, associatedData = transaction.id.value.toByteArray())
            SyncLogEntryUpload(
                roomId = roomId.value,
                recordType = RECORD_TYPE_TRANSACTION,
                recordId = transaction.id.value,
                deviceId = deviceId,
                payload = Base64.getEncoder().encodeToString(ciphertext),
                deleted = false,
                changedAt = Instant.ofEpochMilli(entry.changedAtMillis).toString(),
                changedAtMillis = entry.changedAtMillis,
            )
        }

        // Tombstone (Tahap 9, roadmap.md "Sinkron penghapusan transaksi di ruang yang dibagi"): baris
        // asli sudah hilang secara lokal, jadi tidak ada isi untuk dienkripsi — payload tetap ciphertext
        // buram (bukan sentinel teks biasa) lewat AEAD ruang yang sama, supaya bentuknya seragam dengan
        // baris hidup di mata server. `deleted` sendiri sudah terbuka di luar enkripsi (kolom log lama).
        val tombstoneUploads = pendingDeletes.map { tombstone ->
            val ciphertext = aead.encrypt(ByteArray(0), associatedData = tombstone.transactionId.value.toByteArray())
            SyncLogEntryUpload(
                roomId = roomId.value,
                recordType = RECORD_TYPE_TRANSACTION,
                recordId = tombstone.transactionId.value,
                deviceId = deviceId,
                payload = Base64.getEncoder().encodeToString(ciphertext),
                deleted = true,
                changedAt = Instant.ofEpochMilli(tombstone.deletedAtMillis).toString(),
                changedAtMillis = tombstone.deletedAtMillis,
            )
        }

        val uploads = liveUploads + tombstoneUploads

        return when (val result = syncRemote.push(apiToken, uploads)) {
            is RemoteResult.Failure -> result
            is RemoteResult.Success -> {
                result.value.serverTimeMs?.let { clock.setServerOffset(it - System.currentTimeMillis()) }
                transactions.markSynced(pending.map { it.transaction.id }, System.currentTimeMillis())
                transactions.clearPendingTombstones(pendingDeletes.map { it.transactionId })
                RemoteResult.Success(uploads.size)
            }
        }
    }

    private suspend fun pull(roomId: RoomId, deviceId: String, apiToken: String, aead: SyncAead): RemoteResult<PullOutcome> {
        var cursor = cursorStore.read(roomId.value)
        var applied = 0
        var skipped = 0

        while (true) {
            val page = when (val result = syncRemote.pull(apiToken, deviceId, cursor)) {
                is RemoteResult.Failure -> return result
                is RemoteResult.Success -> result.value
            }
            page.serverTimeMs?.let { clock.setServerOffset(it - System.currentTimeMillis()) }

            for (downloaded in page.entries) {
                // Echo baris sendiri (sudah punya lokal) dan entitas lain (menyusul terpisah) dilewati.
                if (downloaded.deviceId == deviceId || downloaded.recordType != RECORD_TYPE_TRANSACTION) continue
                // Server mengirim baris dari semua ruang yang diikuti anggota ini; hanya baris ruang yang sedang
                // disinkron yang diterapkan, supaya transaksi ruang lain tidak masuk ke ruang ini (roadmap.md, Tahap 9).
                if (downloaded.roomId != roomId.value) continue

                val changedAtMillis = downloaded.changedAtMillis ?: Instant.parse(downloaded.changedAt).toEpochMilli()
                val id = TransactionId(downloaded.recordId)
                // Penulis buram (bagian B R3): server menentukan; perangkat lama tanpa field ini jatuh
                // ke device_id supaya akun virtual tetap terbentuk, hanya kurang stabil lintas perangkat penulis.
                val author = downloaded.author ?: downloaded.deviceId

                if (downloaded.deleted) {
                    transactions.applyFromSync(id, null, changedAtMillis, deleted = true)
                    applied++
                    continue
                }

                val applyResult = applyIncoming(id, roomId, downloaded.payload, changedAtMillis, aead, author)
                if (applyResult) applied++ else skipped++
            }

            cursor = page.nextAfter
            cursorStore.save(roomId.value, cursor)
            if (!page.hasMore) break
        }

        return RemoteResult.Success(PullOutcome(applied, skipped))
    }

    /** @return true bila diterapkan, false bila payload rusak (akun tak dikenal tidak lagi mungkin: akun virtual selalu dibuat). */
    private suspend fun applyIncoming(
        id: TransactionId,
        roomId: RoomId,
        payloadBase64: String,
        changedAtMillis: Long,
        aead: SyncAead,
        author: String,
    ): Boolean {
        val decoded = try {
            codec.decode(aead.decrypt(Base64.getDecoder().decode(payloadBase64), associatedData = id.value.toByteArray()))
        } catch (e: Exception) {
            return false
        }

        // Akun anggota virtual (Tahap 2, keputusan pemilik #0): TIDAK PERNAH mencocokkan ke akun nyata
        // penerima. Satu akun virtual per (ruang, penulis, nama akun), idempoten lewat id deterministik.
        val account = accounts.findOrCreateVirtualMember(roomId, author, decoded.accountName)

        val categoriesInRoom = rooms.allCategories().filter { it.roomId == roomId }
        val categoryId = decoded.categoryName
            ?.let { name -> categoriesInRoom.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            ?: categoriesInRoom.firstOrNull { it.name.equals(FALLBACK_CATEGORY_NAME, ignoreCase = true) }
        if (categoryId == null) return false

        val transaction = MoneyTransaction(
            id = id,
            kind = TransactionKind.EXPENSE,
            amount = decoded.amount,
            accountId = account.id,
            roomId = roomId,
            categoryId = categoryId.id,
            occurredOn = decoded.occurredOn,
            note = decoded.note,
            // SHARED (bukan IMPORT): pengeluaran anggota lain di ruang bersama bukan data pribadi pengguna ini, jadi
            // pencatat dan backfill sinkron entitas penuh (sync-desain.md, Tahap 1) mengecualikannya dari cakupan pribadi.
            // Tidak ada kode yang membaca origin untuk saldo, jatah, riwayat, laporan, atau ekspor; hanya tulis.
            origin = TransactionOrigin.SHARED,
            createdAtMillis = changedAtMillis,
            updatedAtMillis = changedAtMillis,
        )
        transactions.applyFromSync(id, transaction, changedAtMillis, deleted = false)
        return true
    }

    private data class PullOutcome(val applied: Int, val skipped: Int)
}

sealed interface SyncResult {
    /** [skipped] = baris masuk yang dilewati karena payload rusak atau kategori tak dikenal. */
    data class Success(val pushed: Int, val applied: Int, val skipped: Int) : SyncResult

    data class Failed(val error: RemoteError) : SyncResult

    /** Belum pernah masuk dengan Google / token API belum ada — tidak pernah terjadi diam-diam. */
    data object NotSignedIn : SyncResult
}
