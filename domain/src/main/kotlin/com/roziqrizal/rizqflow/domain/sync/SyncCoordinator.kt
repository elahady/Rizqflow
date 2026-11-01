package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.ledger.AccountRepository
import com.roziqrizal.rizqflow.domain.ledger.Category
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.PendingSyncTransaction
import com.roziqrizal.rizqflow.domain.ledger.RoomRepository
import com.roziqrizal.rizqflow.domain.ledger.RoomTemplates
import com.roziqrizal.rizqflow.domain.ledger.TransactionRepository
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import kotlinx.serialization.json.JsonObject
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Kolom luar `record_type` untuk SEMUA baris amplop umum, cakupan pribadi maupun ruang bersama (sync-desain.md bagian C,
 * keputusan #7): konstan, jadi server tidak bisa menghitung jumlah rekaman per jenis. Tipe sebenarnya ada di dalam amplop
 * terenkripsi dan dikunci ke `record_id` lewat [SyncEnvelope.wireId].
 */
internal const val ENVELOPE_RECORD_TYPE = "r"

/** Nama lama untuk [ENVELOPE_RECORD_TYPE] di jalur ruang bersama. */
internal const val SHARED_RECORD_TYPE = ENVELOPE_RECORD_TYPE

/**
 * Jenis masalah sinkron untuk amplop yang ditolak: tipe di dalam amplop yang tidak dikenal (biasanya dari versi
 * aplikasi yang lebih baru, "perbarui aplikasi") berbeda dari data yang rusak atau dipalsukan (dekripsi gagal, bingkai
 * rusak, wireId tidak cocok).
 */
internal fun SyncEnvelopeException.issueKind(): SyncIssueKind =
    if (reason == EnvelopeError.UNKNOWN_TYPE) SyncIssueKind.UNSUPPORTED_VERSION else SyncIssueKind.TAMPERED_OR_CORRUPT

/** Kolom luar `record_type` format lama (amplop ad hoc sebelum 2026-11-01); hanya dibaca, tidak ditulis lagi. */
internal const val LEGACY_RECORD_TYPE_TRANSACTION = "transaction"
private const val FALLBACK_CATEGORY_NAME = "Lain-lain"

/**
 * Sync push/pull per cakupan (Tahap 2 sync-desain.md, menggantikan `SyncEngine` lama). Untuk putaran
 * ini hanya cakupan **ruang bersama** yang berjalan — [SyncScope.SharedRoom] via [syncNow] — karena
 * cakupan pribadi menunggu handler per tipe (Tahap 3 dan seterusnya, lihat `SyncRecordHandler`).
 * Hanya transaksi EXPENSE yang disync; transfer, pemasukan, utang-piutang, tagihan, dan transaksi
 * berulang tidak pernah masuk cakupan bersama (sync-desain.md bagian A).
 *
 * **Format kabel (sejak 2026-11-01): amplop umum [SyncEnvelope]** (sync-desain.md bagian C), sama dengan
 * [PersonalSyncCoordinator]: tipe [SharedRecordType.SHARED_EXPENSE], isi `d` dari [SharedExpensePayload],
 * kolom luar `record_type` konstan [SHARED_RECORD_TYPE] dan `record_id` = wireId (keputusan pemilik #7),
 * AAD mengikat `r:<roomId>`, wireId, cap milidetik, `deleted`, dan perangkat. Tombstone kini ikut terenkripsi
 * dan membawa id transaksi di dalam amplop. Baris format lama (`record_type` = `transaction`, id terbaca,
 * payload [TransactionSyncCodec] dengan AAD = id) tetap DIBACA lewat [codec] supaya riwayat yang sudah terkirim
 * tidak hilang, tetapi tidak pernah ditulis lagi.
 *
 * Mesin ini sengaja belum memakai [SyncRecordHandler]/[SyncStore] untuk status kotor dan LWW: kontrak itu menaruh
 * LWW, penulis, dan kotor di `sync_record`, sedangkan status kotor ruang bersama masih tinggal di
 * `money_transaction.synced_at` dan `pending_room_tombstone`. Memindahkannya butuh migrasi data lokal tersendiri
 * (rancangan di sync-desain.md bagian I Tahap 2 butir (d)); amplop di kabel sudah sama. [SyncStore] hanya dipakai
 * untuk buku catatan masalah ([issueStore]).
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
 * - **Penjepitan jam jauh ke depan saat push** (bagian D "Jam salah"): cap yang DIKIRIM ke server
 *   (`changedAtMillis` di [SyncLogEntryUpload]) selalu lewat [SyncClock.stamp], bukan cap lokal mentah
 *   (`entry.changedAtMillis`/`tombstone.deletedAtMillis`, yang bisa jauh ke depan bila jam perangkat
 *   salah setel). `stamp()` mengoreksinya dengan [SyncClock.serverOffsetMillis] begitu perangkat ini
 *   pernah sekali tersambung ke server (offset diisi dari `server_time_ms` di atas); sebelum koneksi
 *   pertama itu belum ada apa pun untuk dikoreksi terhadap (lihat KDoc [SyncClock], butir "Risiko jam
 *   jauh ke depan"). Dilakukan HANYA di titik ini (sebelum push), tidak menyentuh jalur tulis
 *   transaksi lokal biasa (`updatedAtMillis` lokal tetap apa adanya) — supaya LWW lokal (membandingkan
 *   edit lokal yang belum terkirim dengan versi masuk) tidak ikut berubah oleh penjepitan ini.
 *
 * - **Tombstone pindah-keluar** (bagian A): pengeluaran yang dipindah dari ruang bersama A ke ruang lain menerbitkan
 *   tombstone di A lewat [TransactionRepository.pendingTombstones] (dicatat repositori saat `update`), jadi anggota A
 *   ikut menghapusnya; versi hidupnya di ruang bersama tujuan tetap terkirim lewat `pendingSync` ruang itu, dan
 *   pengeluaran di ruang non-bersama tidak pernah keluar. Sisi terima menerapkan tombstone hanya pada baris yang masih
 *   berada di ruang tombstone itu (`fromRoom`), karena versi hidup di ruang tujuan bisa tiba lebih dulu.
 * - **Masalah sinkron** (bagian E): baris yang gagal dibuka atau diterapkan dicatat di `sync_issue` lewat [issueStore]
 *   (cakupan `r:<roomId>`, tanpa isi apa pun: hanya jenis dan keterangan), selain dihitung `skipped`; baris server yang
 *   sama tidak menggandakan catatan (`SyncStore.recordIssue` menghapus ganda berdasarkan id baris server). Baris yang
 *   ditunda karena ruang belum punya kategori "Lain-lain" disimpan beserta baris mentahnya dan dicoba lagi di akhir
 *   tiap pull. Tanpa [issueStore] (tes lama) perilakunya sama seperti dulu: hanya dihitung `skipped`.
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
    private val issueStore: SyncStore? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
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
        // Tombstone yang masih menunggu untuk transaksi yang kini punya versi hidup baru di ruang ini (urungkan hapus,
        // kembali ke ruang ini setelah dipindah keluar) sudah gugur: dua-duanya dikirim bersamaan, tombstone yang capnya
        // lebih besar menang dan versi hidup hilang. Repositori sudah menggugurkannya saat menulis; ini jaring pengaman.
        val pendingIds = pending.mapTo(HashSet()) { it.transaction.id }
        val (superseded, pendingDeletes) = transactions.pendingTombstones(roomId).partition { it.transactionId in pendingIds }
        if (superseded.isNotEmpty()) transactions.clearPendingTombstones(roomId, superseded.map { it.transactionId })
        if (pending.isEmpty() && pendingDeletes.isEmpty()) return RemoteResult.Success(0)

        val scope = SyncScope.SharedRoom(roomId)
        val accountNames = accounts.allAccounts().associate { it.id to it.name }
        val categoryNames = rooms.allCategories().filter { it.roomId == roomId }.associate { it.id to it.name }

        // Rekaman yang gagal dibungkus (praktis hanya payload melebihi 64 KB) tetap pending dan tidak ikut dikirim:
        // syncNow tidak pernah melempar, dan satu rekaman rusak tidak menahan rekaman lain.
        val sentLive = mutableListOf<PendingSyncTransaction>()
        val liveUploads = pending.mapNotNull { entry ->
            val transaction = entry.transaction
            // Penjepitan jam (Tahap 2, sync-desain.md bagian D "Jam salah"): cap yang DIKIRIM dipakai
            // lewat SyncClock.stamp(), bukan cap lokal mentah (entry.changedAtMillis bisa jauh ke depan
            // bila jam perangkat salah). stamp() = max(jam perangkat + offset server, cap lokal + 1) —
            // offset dikoreksi dari server_time_ms setiap putaran berhasil (lihat bawah), jadi perangkat
            // yang jamnya salah mengirim cap yang sudah terkoreksi begitu pernah sekali tersambung ke
            // server; sebelum itu (belum pernah tersambung) belum bisa dikoreksi — lihat KDoc [SyncClock].
            // Cap yang sama masuk AAD amplop, jadi server tidak bisa menggantinya tanpa menggagalkan dekripsi.
            val changedAtMillis = clock.stamp(entry.changedAtMillis)
            val data = SharedExpensePayload.encode(
                transaction = transaction,
                accountName = accountNames[transaction.accountId] ?: "",
                categoryName = transaction.categoryId?.let { categoryNames[it] },
            )
            seal(scope, aead, transaction.id, data, changedAtMillis, deleted = false, deviceId)
                ?.also { sentLive += entry }
        }

        // Tombstone (Tahap 9, roadmap.md "Sinkron penghapusan transaksi di ruang yang dibagi"): baris asli sudah
        // hilang secara lokal, jadi `d` kosong — tetapi id transaksi ikut terenkripsi di bidang `id` amplop
        // (kolom luar hanya wireId), dan `deleted` ikut AAD sehingga server tidak bisa memalsukan tombstone.
        // Cap tombstone ikut dijepit lewat SyncClock.stamp() sama seperti baris hidup di atas.
        val sentDeletes = mutableListOf<TransactionId>()
        val tombstoneUploads = pendingDeletes.mapNotNull { tombstone ->
            val changedAtMillis = clock.stamp(tombstone.deletedAtMillis)
            seal(scope, aead, tombstone.transactionId, JsonObject(emptyMap()), changedAtMillis, deleted = true, deviceId)
                ?.also { sentDeletes += tombstone.transactionId }
        }

        val uploads = liveUploads + tombstoneUploads
        if (uploads.isEmpty()) return RemoteResult.Success(0)

        return when (val result = syncRemote.push(apiToken, uploads)) {
            is RemoteResult.Failure -> result
            is RemoteResult.Success -> {
                result.value.serverTimeMs?.let { clock.setServerOffset(it - clock.rawDeviceNowMillis()) }
                // Bersyarat: hanya versi yang BENAR-BENAR terkirim yang ditandai. Suntingan yang masuk selama push berjalan
                // (updated_at berubah dan synced_at dikosongkan) tetap pending dan terkirim di putaran berikutnya.
                transactions.markSyncedIfUnchanged(sentLive, System.currentTimeMillis())
                transactions.clearPendingTombstones(roomId, sentDeletes)
                RemoteResult.Success(uploads.size)
            }
        }
    }

    /**
     * Amplop umum (sync-desain.md bagian C) untuk satu versi pengeluaran ruang bersama. Kolom luar: `record_type`
     * konstan [SHARED_RECORD_TYPE] dan `record_id` = [SyncEnvelope.wireId] (keputusan pemilik #7: tipe dan id
     * transaksi tidak terbaca server). Null bila amplop tidak bisa dibuat (mis. melebihi batas 64 KB).
     */
    private fun seal(
        scope: SyncScope.SharedRoom,
        aead: SyncAead,
        id: TransactionId,
        data: JsonObject,
        changedAtMillis: Long,
        deleted: Boolean,
        deviceId: String,
    ): SyncLogEntryUpload? {
        val sealed = try {
            SyncEnvelope.seal(
                aead = aead,
                scope = scope,
                type = SharedRecordType.SHARED_EXPENSE.wire,
                id = id.value,
                version = SharedExpensePayload.VERSION,
                data = data,
                changedAtMillis = changedAtMillis,
                deleted = deleted,
                deviceId = deviceId,
            )
        } catch (e: SyncEnvelopeException) {
            return null
        }
        return SyncLogEntryUpload(
            roomId = scope.roomId.value,
            recordType = SHARED_RECORD_TYPE,
            recordId = sealed.wireId,
            deviceId = deviceId,
            payload = Base64.getEncoder().encodeToString(sealed.ciphertext),
            deleted = deleted,
            changedAt = Instant.ofEpochMilli(changedAtMillis).toString(),
            changedAtMillis = changedAtMillis,
        )
    }

    private suspend fun pull(roomId: RoomId, deviceId: String, apiToken: String, aead: SyncAead): RemoteResult<PullOutcome> {
        val scope = SyncScope.SharedRoom(roomId)
        var cursor = cursorStore.read(roomId.value)
        var applied = 0
        var skipped = 0

        while (true) {
            val page = when (val result = syncRemote.pull(apiToken, deviceId, cursor)) {
                is RemoteResult.Failure -> return result
                is RemoteResult.Success -> result.value
            }
            page.serverTimeMs?.let { clock.setServerOffset(it - clock.rawDeviceNowMillis()) }

            for (downloaded in page.entries) {
                // Echo baris sendiri (sudah punya lokal) dilewati.
                if (downloaded.deviceId == deviceId) continue
                // Server mengirim baris dari semua ruang yang diikuti anggota ini; hanya baris ruang yang sedang
                // disinkron yang diterapkan, supaya transaksi ruang lain tidak masuk ke ruang ini (roadmap.md, Tahap 9).
                if (downloaded.roomId != roomId.value) continue

                val outcome = when (downloaded.recordType) {
                    SHARED_RECORD_TYPE -> applyEnvelope(scope, downloaded, aead, record = true)
                    LEGACY_RECORD_TYPE_TRANSACTION -> applyLegacy(roomId, downloaded, aead, record = true)
                    // Tipe kabel lain (mis. dari versi aplikasi lebih baru) bukan urusan mesin ini: dilewati tanpa dihitung.
                    else -> continue
                }
                if (outcome == RowOutcome.APPLIED) applied++ else skipped++
            }

            cursor = page.nextAfter
            cursorStore.save(roomId.value, cursor)
            if (!page.hasMore) break
        }

        // Baris yang ditunda (kursor sudah lewat) dicoba lagi di sini; yang berhasil tidak lagi terhitung dilewati.
        val retried = retryDeferred(scope, aead)
        return RemoteResult.Success(PullOutcome(applied + retried, maxOf(0, skipped - retried)))
    }

    private enum class RowOutcome {
        APPLIED,

        /** Tidak diterapkan dan tidak akan dicoba lagi (rusak, versi tak dikenal, payload tidak sah). */
        SKIPPED,

        /** Tidak diterapkan SEKARANG tetapi disimpan di `sync_issue` untuk dicoba lagi ([retryDeferred]). */
        DEFERRED,
    }

    /**
     * Mencatat satu masalah sinkron ruang bersama. Hanya metadata (jenis, keterangan tanpa isi, id baris server):
     * `raw_row` hanya untuk baris yang akan dicoba lagi. Kegagalan mencatat tidak boleh menggagalkan sync.
     */
    private suspend fun recordIssue(scope: SyncScope.SharedRoom, row: SyncLogEntryDownload, kind: SyncIssueKind, detail: String?, rawRow: String? = null) {
        val store = issueStore ?: return
        try {
            store.recordIssue(SyncIssue(scope, row.recordType, row.recordId, kind, row.id, detail, rawRow, 0, nowMillis()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Buku catatan masalah adalah bantuan diagnosis; sync tetap berjalan tanpanya.
        }
    }

    private suspend fun reject(scope: SyncScope.SharedRoom, row: SyncLogEntryDownload, kind: SyncIssueKind, detail: String?, record: Boolean): RowOutcome {
        if (record) recordIssue(scope, row, kind, detail)
        return RowOutcome.SKIPPED
    }

    private fun issueKindOf(error: SyncEnvelopeException): SyncIssueKind = error.issueKind()

    private fun rawRowOf(row: SyncLogEntryDownload, changedAtMillis: Long): String =
        DeferredPullRow(row.recordId, changedAtMillis, row.deleted, row.deviceId, row.author, row.id, row.payload).encode().toString()

    /** Mencoba lagi baris yang ditunda dari pull sebelumnya; mengembalikan berapa yang akhirnya berhasil diterapkan. */
    private suspend fun retryDeferred(scope: SyncScope.SharedRoom, aead: SyncAead): Int {
        val store = issueStore ?: return 0
        val candidates = store.issues(scope).filter { it.kind == SyncIssueKind.DEFERRED_MISSING_REF && it.rawRow != null }
        var applied = 0
        for (issue in candidates) {
            val raw = DeferredPullRow.decode(issue.rawRow!!) ?: continue
            val row = SyncLogEntryDownload(
                id = raw.serverId ?: 0L,
                roomId = scope.roomId.value,
                recordType = issue.recordType,
                recordId = raw.wireId,
                deviceId = raw.deviceId,
                payload = raw.ciphertextBase64,
                deleted = raw.deleted,
                changedAt = Instant.ofEpochMilli(raw.changedAtMillis).toString(),
                changedAtMillis = raw.changedAtMillis,
                author = raw.author,
            )
            val outcome = when (issue.recordType) {
                SHARED_RECORD_TYPE -> applyEnvelope(scope, row, aead, record = false)
                LEGACY_RECORD_TYPE_TRANSACTION -> applyLegacy(scope.roomId, row, aead, record = false)
                else -> RowOutcome.SKIPPED
            }
            if (outcome == RowOutcome.APPLIED) {
                applied++
                store.resolveIssue(issue.id)
            } else {
                store.incrementIssueAttempts(issue.id)
            }
        }
        return applied
    }

    /**
     * Baris format amplop umum ([SHARED_RECORD_TYPE]). Semua kolom luar (scope ruang ini, wireId, cap milidetik,
     * `deleted`, perangkat) masuk AAD, jadi baris yang dipindah ke ruang lain, diputar ulang dengan cap baru, atau
     * tombstone palsu gagal dibuka dan dilewati. Versi `d` lebih tinggi dari yang dikenal tidak diterapkan separuh.
     */
    private suspend fun applyEnvelope(scope: SyncScope.SharedRoom, row: SyncLogEntryDownload, aead: SyncAead, record: Boolean): RowOutcome {
        val changedAtMillis = try {
            row.changedAtMillis ?: Instant.parse(row.changedAt).toEpochMilli()
        } catch (e: DateTimeException) {
            return reject(scope, row, SyncIssueKind.TAMPERED_OR_CORRUPT, "cap waktu baris tidak sah", record)
        }
        val opened = try {
            SyncEnvelope.open(
                aead = aead,
                scope = scope,
                wireId = row.recordId,
                changedAtMillis = changedAtMillis,
                deleted = row.deleted,
                deviceId = row.deviceId,
                ciphertext = Base64.getDecoder().decode(row.payload),
                isKnownType = { SharedRecordType.fromWire(it) != null },
            )
        } catch (e: SyncEnvelopeException) {
            return reject(scope, row, issueKindOf(e), e.message, record)
        } catch (e: IllegalArgumentException) {
            // payload bukan base64 yang sah
            return reject(scope, row, SyncIssueKind.TAMPERED_OR_CORRUPT, "payload bukan base64 yang sah", record)
        }
        if (SharedRecordType.fromWire(opened.type) != SharedRecordType.SHARED_EXPENSE) {
            return reject(scope, row, SyncIssueKind.UNSUPPORTED_VERSION, "tipe rekaman tidak dikenal", record)
        }
        if (opened.version > SharedExpensePayload.VERSION) {
            return reject(scope, row, SyncIssueKind.UNSUPPORTED_VERSION, "versi payload ${opened.version} lebih baru dari yang dikenal", record)
        }

        val id = TransactionId(opened.id)
        if (row.deleted) {
            transactions.applyFromSync(id, null, changedAtMillis, deleted = true, fromRoom = scope.roomId)
            return RowOutcome.APPLIED
        }
        val decoded = try {
            SharedExpensePayload.decode(opened.id, opened.data)
        } catch (e: TransactionSyncPayloadCorrupt) {
            return reject(scope, row, SyncIssueKind.TAMPERED_OR_CORRUPT, "isi pengeluaran tidak sah", record)
        }
        return applyDecoded(scope, row, id, decoded, changedAtMillis, record)
    }

    /**
     * Baris format lama (sebelum amplop umum, 2026-11-01): `record_type` = `transaction`, `record_id` = id transaksi
     * terbaca, payload [TransactionSyncCodec] dengan AAD = id saja, tombstone berpayload kosong. Tetap dibaca supaya
     * riwayat yang sudah terkirim tidak hilang; tidak pernah ditulis lagi. Aturan penerapannya sama dengan jalur baru.
     */
    private suspend fun applyLegacy(roomId: RoomId, row: SyncLogEntryDownload, aead: SyncAead, record: Boolean): RowOutcome {
        val scope = SyncScope.SharedRoom(roomId)
        val changedAtMillis = try {
            row.changedAtMillis ?: Instant.parse(row.changedAt).toEpochMilli()
        } catch (e: DateTimeException) {
            return reject(scope, row, SyncIssueKind.TAMPERED_OR_CORRUPT, "cap waktu baris tidak sah", record)
        }
        val id = TransactionId(row.recordId)
        if (row.deleted) {
            transactions.applyFromSync(id, null, changedAtMillis, deleted = true, fromRoom = roomId)
            return RowOutcome.APPLIED
        }
        val decoded = try {
            codec.decode(aead.decrypt(Base64.getDecoder().decode(row.payload), associatedData = id.value.toByteArray()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return reject(scope, row, SyncIssueKind.TAMPERED_OR_CORRUPT, "baris format lama tidak bisa dibuka (${e.javaClass.simpleName})", record)
        }
        return applyDecoded(scope, row, id, decoded, changedAtMillis, record)
    }

    // Penulis buram (bagian B R3): server menentukan; perangkat lama tanpa field ini jatuh
    // ke device_id supaya akun virtual tetap terbentuk, hanya kurang stabil lintas perangkat penulis.
    private fun authorOf(row: SyncLogEntryDownload): String = row.author ?: row.deviceId

    /**
     * @return [RowOutcome.APPLIED], atau [RowOutcome.DEFERRED] bila (sangat jarang) ruang ini bahkan tidak punya
     *   kategori "Lain-lain" sama sekali: baris disimpan di `sync_issue` beserta baris mentahnya dan dicoba lagi
     *   di akhir pull berikutnya ([retryDeferred]). Akun tak dikenal tidak lagi mungkin (akun virtual selalu dibuat);
     *   kategori tak dikenal pun tidak lagi jatuh ke "Lain-lain" — lihat [findOrCreateSharedCategory].
     */
    private suspend fun applyDecoded(
        scope: SyncScope.SharedRoom,
        row: SyncLogEntryDownload,
        id: TransactionId,
        decoded: DecodedSyncTransaction,
        changedAtMillis: Long,
        record: Boolean,
    ): RowOutcome {
        val roomId = scope.roomId
        // Akun anggota virtual (Tahap 2, keputusan pemilik #0): TIDAK PERNAH mencocokkan ke akun nyata
        // penerima. Satu akun virtual per (ruang, penulis, nama akun), idempoten lewat id deterministik.
        val account = accounts.findOrCreateVirtualMember(roomId, authorOf(row), decoded.accountName)

        val categoriesInRoom = rooms.allCategories().filter { it.roomId == roomId }
        val categoryId = findOrCreateSharedCategory(roomId, categoriesInRoom, decoded.categoryName)
        if (categoryId == null) {
            if (record) recordIssue(scope, row, SyncIssueKind.DEFERRED_MISSING_REF, "ruang belum punya kategori Lain-lain", rawRowOf(row, changedAtMillis))
            return RowOutcome.DEFERRED
        }

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
        return RowOutcome.APPLIED
    }

    /**
     * Kategori ruang bersama dikenali lewat nama ternormalisasi (bagian B R2, keputusan pemilik):
     * dicari dulu di [categoriesInRoom]; kalau tidak ketemu, DIBUAT baru di [roomId] (bukan jatuh ke
     * "Lain-lain" — itu perilaku lama Tahap 2 yang sengaja ditutup di sini). Normalisasi NFKC + trim +
     * rapatkan spasi + huruf kecil (bagian B R2, [SharedRoomNameNormalization]) menyamakan variasi
     * kapital/spasi, jadi kategori sistem `Zakat mal`/`Tak terlacak` yang sudah ada di ruang ini
     * dicocokkan lewat nama seperti kategori lain dan TIDAK PERNAH dibuat dobel.
     *
     * [rawName] kosong atau cuma spasi (null maupun string) adalah kasus BERBEDA dari "tidak
     * ditemukan": selalu jatuh ke "Lain-lain", tidak pernah membuat kategori baru bernama kosong.
     *
     * @return null hanya bila ruang ini bahkan tidak punya kategori "Lain-lain" (seharusnya mustahil:
     *   setiap ruang dibuat dengan kategori itu, lihat `RoomTemplates`/`WorkspaceSetup`).
     */
    private suspend fun findOrCreateSharedCategory(roomId: RoomId, categoriesInRoom: List<Category>, rawName: String?): Category? {
        val trimmed = rawName?.trim()
        if (trimmed.isNullOrEmpty()) {
            val fallbackNormalized = SharedRoomNameNormalization.normalize(FALLBACK_CATEGORY_NAME)
            return categoriesInRoom.firstOrNull { SharedRoomNameNormalization.normalize(it.name) == fallbackNormalized }
        }

        val normalized = SharedRoomNameNormalization.normalize(trimmed)
        categoriesInRoom.firstOrNull { SharedRoomNameNormalization.normalize(it.name) == normalized }?.let { return it }

        // Tidak ketemu: buat baru. Nama tampilan tetap nama asli dari pengirim (hanya dipotong bila
        // melebihi batas panjang kategori), normalisasi di atas hanya dipakai untuk PENCARIAN.
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

    private data class PullOutcome(val applied: Int, val skipped: Int)
}

sealed interface SyncResult {
    /** [skipped] = baris masuk yang dilewati karena payload rusak atau kategori tak dikenal. */
    data class Success(val pushed: Int, val applied: Int, val skipped: Int) : SyncResult

    data class Failed(val error: RemoteError) : SyncResult

    /** Belum pernah masuk dengan Google / token API belum ada — tidak pernah terjadi diam-diam. */
    data object NotSignedIn : SyncResult
}
