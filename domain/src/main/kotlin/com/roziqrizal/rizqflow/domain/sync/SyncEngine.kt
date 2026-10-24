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
 * Sync push/pull untuk satu ruang yang dibagi (Tahap 9, roadmap.md "Ruang keluarga bersama").
 * Dipanggil hanya dari tombol "Sinkronkan sekarang" — tidak ada trigger otomatis di latar belakang
 * untuk putaran ini. Hanya transaksi EXPENSE yang disync (lihat [TransactionSyncCodec]); transfer,
 * pemasukan, utang-piutang, tagihan, dan transaksi berulang menyusul terpisah. "Tenang, bukan
 * panik": kegagalan jaringan/server selalu [SyncResult.Failed], tidak pernah melempar exception.
 */
class SyncEngine(
    private val transactions: TransactionRepository,
    private val rooms: RoomRepository,
    private val accounts: AccountRepository,
    private val roomKeys: RoomKeyProvider,
    private val syncRemote: SyncRemote,
    private val codec: TransactionSyncCodec,
    private val apiTokenStore: ApiTokenStore,
    private val cursorStore: SyncCursorStore,
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
        if (pending.isEmpty()) return RemoteResult.Success(0)

        val accountNames = accounts.allAccounts().associate { it.id to it.name }
        val categoryNames = rooms.allCategories().filter { it.roomId == roomId }.associate { it.id to it.name }

        val uploads = pending.map { entry ->
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
                deleted = false, // penghapusan belum ikut tersinkron di putaran ini, lihat pendingSync
                changedAt = Instant.ofEpochMilli(entry.changedAtMillis).toString(),
            )
        }

        return when (val result = syncRemote.push(apiToken, uploads)) {
            is RemoteResult.Failure -> result
            is RemoteResult.Success -> {
                transactions.markSynced(pending.map { it.transaction.id }, System.currentTimeMillis())
                RemoteResult.Success(uploads.size)
            }
        }
    }

    private suspend fun pull(roomId: RoomId, deviceId: String, apiToken: String, aead: SyncAead): RemoteResult<PullOutcome> {
        var cursor = cursorStore.read(roomId.value)
        var applied = 0
        var skipped = 0

        while (true) {
            val page = when (val result = syncRemote.pull(apiToken, cursor)) {
                is RemoteResult.Failure -> return result
                is RemoteResult.Success -> result.value
            }

            for (downloaded in page.entries) {
                // Echo baris sendiri (sudah punya lokal) dan entitas lain (menyusul terpisah) dilewati.
                if (downloaded.deviceId == deviceId || downloaded.recordType != RECORD_TYPE_TRANSACTION) continue

                val changedAtMillis = Instant.parse(downloaded.changedAt).toEpochMilli()
                val id = TransactionId(downloaded.recordId)

                if (downloaded.deleted) {
                    transactions.applyFromSync(id, null, downloaded.deviceId, changedAtMillis, deleted = true)
                    applied++
                    continue
                }

                val applyResult = applyIncoming(id, roomId, downloaded.payload, downloaded.deviceId, changedAtMillis, aead)
                if (applyResult) applied++ else skipped++
            }

            cursor = page.nextAfter
            cursorStore.save(roomId.value, cursor)
            if (!page.hasMore) break
        }

        return RemoteResult.Success(PullOutcome(applied, skipped))
    }

    /** @return true bila diterapkan, false bila dilewati (akun tak dikenal atau payload rusak). */
    private suspend fun applyIncoming(
        id: TransactionId,
        roomId: RoomId,
        payloadBase64: String,
        deviceId: String,
        changedAtMillis: Long,
        aead: SyncAead,
    ): Boolean {
        val decoded = try {
            codec.decode(aead.decrypt(Base64.getDecoder().decode(payloadBase64), associatedData = id.value.toByteArray()))
        } catch (e: Exception) {
            return false
        }

        val accountId = accounts.allAccounts()
            .firstOrNull { it.name.equals(decoded.accountName, ignoreCase = true) }
            ?.id ?: return false

        val categoriesInRoom = rooms.allCategories().filter { it.roomId == roomId }
        val categoryId = decoded.categoryName
            ?.let { name -> categoriesInRoom.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            ?: categoriesInRoom.firstOrNull { it.name.equals(FALLBACK_CATEGORY_NAME, ignoreCase = true) }
        if (categoryId == null) return false

        val transaction = MoneyTransaction(
            id = id,
            kind = TransactionKind.EXPENSE,
            amount = decoded.amount,
            accountId = accountId,
            roomId = roomId,
            categoryId = categoryId.id,
            occurredOn = decoded.occurredOn,
            note = decoded.note,
            origin = TransactionOrigin.IMPORT,
            createdAtMillis = changedAtMillis,
            updatedAtMillis = changedAtMillis,
        )
        transactions.applyFromSync(id, transaction, deviceId, changedAtMillis, deleted = false)
        return true
    }

    private data class PullOutcome(val applied: Int, val skipped: Int)
}

sealed interface SyncResult {
    /** [skipped] = baris masuk yang dilewati karena akun/kategori tak dikenal atau payload rusak. */
    data class Success(val pushed: Int, val applied: Int, val skipped: Int) : SyncResult

    data class Failed(val error: RemoteError) : SyncResult

    /** Belum pernah masuk dengan Google / token API belum ada — tidak pernah terjadi diam-diam. */
    data object NotSignedIn : SyncResult
}
