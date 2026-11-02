package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.ledger.AccountRepository
import com.roziqrizal.rizqflow.domain.ledger.RoomRepository
import com.roziqrizal.rizqflow.domain.ledger.TransactionRepository
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.TransactionId
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * Kolom luar `record_type` untuk SEMUA baris amplop umum, cakupan pribadi maupun ruang bersama (sync-desain.md bagian C,
 * keputusan #7): konstan, jadi server tidak bisa menghitung jumlah rekaman per jenis. Tipe sebenarnya ada di dalam amplop
 * terenkripsi dan dikunci ke `record_id` lewat [SyncEnvelope.wireId].
 */
internal const val ENVELOPE_RECORD_TYPE = "r"

/** Nama lama untuk [ENVELOPE_RECORD_TYPE] di jalur ruang bersama. */
internal const val SHARED_RECORD_TYPE = ENVELOPE_RECORD_TYPE

/** Nilai param `scope` untuk pull ber-cakupan RUANG BERSAMA (docs/server-sync.md); BEDA dari [SyncScope.SharedRoom.wire] lokal (`"r:<roomId>"`). */
internal const val SCOPE_WIRE_ROOM = "r"

/**
 * Jenis masalah sinkron untuk amplop yang ditolak: tipe di dalam amplop yang tidak dikenal (biasanya dari versi
 * aplikasi yang lebih baru, "perbarui aplikasi") berbeda dari data yang rusak atau dipalsukan (dekripsi gagal, bingkai
 * rusak, wireId tidak cocok).
 */
internal fun SyncEnvelopeException.issueKind(): SyncIssueKind =
    if (reason == EnvelopeError.UNKNOWN_TYPE) SyncIssueKind.UNSUPPORTED_VERSION else SyncIssueKind.TAMPERED_OR_CORRUPT

/** Kolom luar `record_type` format lama (amplop ad hoc sebelum 2026-11-01); hanya dibaca, tidak ditulis lagi. */
internal const val LEGACY_RECORD_TYPE_TRANSACTION = "transaction"

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
 * **Mesin generik per tipe (2026-11-02, Tahap 2 lanjutan "cakupan ruang bersama ke mesin generik"):** dispatch
 * pull/push kini lewat [SharedRoomSyncHandler] ([handlersByType]), bukan hardcode ke transaksi — menambah tipe baru
 * (amplop, `key_grant`) berarti menambah satu penangan ke [extraHandlers], tanpa mengubah kelas ini. Status kotor
 * dan LWW tetap TIDAK lewat [SyncStore.applyIncoming]/[SyncRecordType] (itu khusus cakupan pribadi — lihat KDoc
 * [SharedRecordType] soal kenapa dua enum terpisah): [SharedExpenseSyncHandler] membaca/menulis status kotor lewat
 * `TransactionRepository`, yang di baliknya SUDAH memakai `sync_record` sejak skema v11 (lihat
 * `LocalTransactionRepository`), dan LWW tetap di `TransactionRepository.applyFromSync`. [SyncStore] di sini
 * ([issueStore]) hanya dipakai untuk buku catatan masalah.
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
    /**
     * Kursor per cakupan di tabel `sync_cursor` (Tahap 9 lanjutan, docs/server-sync.md kontrak klien butir 1-2),
     * MENGGANTIKAN [cursorStore] begitu tersedia — null (bawaan) berarti perilaku lama tidak berubah sama sekali.
     * Baca: nilai di [scopedCursors] dipakai hanya bila > 0; nilai 0 (tabel belum pernah diisi, termasuk sesudah
     * [FullResync] mengatur ulang ke 0) jatuh ke [cursorStore] — migrasi satu kali yang aman TANPA kode migrasi
     * terpisah, karena [cursorStore] (SharedPreferences) sudah memegang posisi pull ruang ini sejak sebelum tabel ini
     * ada (kontrak butir 7: ruang memang sudah mulai dari 0 di sini, tidak pernah berbagi kursor global dengan
     * cakupan pribadi). Tulis: SELALU ke KEDUANYA, supaya [cursorStore] tetap benar untuk `LocalSyncHistoryReader`
     * dan supaya pembacaan di atas tidak pernah melihat dua sumber yang berbeda setelah tulisan pertama.
     */
    private val scopedCursors: SyncStore? = null,
    /**
     * Dipanggil begitu server menjawab `not_room_member` (403) untuk ruang ini (kontrak klien butir 5): BUKAN
     * paywall, dan bukan galat jaringan biasa — pemanggil (produksi: `SharedRoomActivation.markRemoved`) menandai
     * ruang lokal berhenti disinkron. Bawaan no-op supaya tes lama yang tidak peduli hal ini tidak perlu diubah.
     */
    private val onNotRoomMember: suspend (RoomId) -> Unit = {},
    /**
     * Penangan TAMBAHAN selain [SharedExpenseSyncHandler] (bawaan, selalu ada) — Tahap 2 lanjutan "mesin generik":
     * amplop dan `key_grant` menambah elemen di sini, tanpa mengubah kelas ini.
     */
    extraHandlers: List<SharedRoomSyncHandler> = emptyList(),
    /**
     * `member_secret` milik perangkat ini sebagai anggota (key_grant langkah 5): dipakai membuka rekaman `key_grant` yang
     * ditujukan kepadanya dan menandai baris yang gagal dibuka sebagai menunggu kunci ([SyncIssueKind.DEFERRED_KEY_MISSING]),
     * bukan rusak. Null (bawaan, dan anggota yang bergabung dengan QR lama tanpa rahasia) berarti perilaku lama.
     */
    private val memberSecrets: MemberSecretStore? = null,
) {
    private val expenseHandler = SharedExpenseSyncHandler(transactions, rooms, accounts)
    private val handlersByType: Map<SharedRecordType, SharedRoomSyncHandler> = (listOf(expenseHandler) + extraHandlers).associateBy { it.type }

    suspend fun syncNow(roomId: RoomId, deviceId: String): SyncResult {
        val apiToken = apiTokenStore.read() ?: return SyncResult.NotSignedIn
        val aead = roomKeys.getOrCreateRoomKey(roomId)

        when (val pushed = push(roomId, deviceId, apiToken, aead)) {
            is RemoteResult.Failure -> {
                if (pushed.error.rejectionCode() == ServerErrorCodes.NOT_ROOM_MEMBER) {
                    onNotRoomMember(roomId)
                    return SyncResult.RemovedFromRoom
                }
                return SyncResult.Failed(pushed.error)
            }
            is RemoteResult.Success -> {
                return when (val pulled = pull(roomId, deviceId, apiToken, aead)) {
                    is RemoteResult.Failure -> {
                        if (pulled.error.rejectionCode() == ServerErrorCodes.NOT_ROOM_MEMBER) {
                            onNotRoomMember(roomId)
                            SyncResult.RemovedFromRoom
                        } else {
                            SyncResult.Failed(pulled.error)
                        }
                    }
                    is RemoteResult.Success -> SyncResult.Success(
                        pushed = pushed.value,
                        applied = pulled.value.applied,
                        skipped = pulled.value.skipped,
                    )
                }
            }
        }
    }

    /**
     * Mengumpulkan rekaman kotor dari SEMUA [handlersByType] (Tahap 2 lanjutan "mesin generik"), membungkusnya jadi
     * amplop, dan mengirim satu batch. Rekaman yang gagal dibungkus (praktis hanya payload melebihi 64 KB) tetap
     * kotor dan tidak ikut dikirim: syncNow tidak pernah melempar, satu rekaman rusak tidak menahan rekaman lain.
     */
    private suspend fun push(roomId: RoomId, deviceId: String, apiToken: String, aead: SyncAead): RemoteResult<Int> {
        val scope = SyncScope.SharedRoom(roomId)
        val outgoing = handlersByType.values.flatMap { handler -> handler.collectDirty(roomId).map { handler.type to it } }
        if (outgoing.isEmpty()) return RemoteResult.Success(0)

        // Per penangan: rekaman yang BENAR-BENAR terkirim (bungkus sukses), cap waktu ASLI (sebelum SyncClock.stamp)
        // — markPushed membandingkan ini dengan apa yang [collectDirty] baca, bukan apa yang dikirim ke kabel.
        val sentByType = mutableMapOf<SharedRecordType, MutableList<OutgoingRecord>>()
        val uploads = outgoing.mapNotNull { (type, record) ->
            // Penjepitan jam (Tahap 2, sync-desain.md bagian D "Jam salah"): cap yang DIKIRIM dipakai lewat
            // SyncClock.stamp(), bukan cap lokal mentah (bisa jauh ke depan bila jam perangkat salah). stamp() =
            // max(jam perangkat + offset server, cap lokal + 1) — offset dikoreksi dari server_time_ms setiap
            // putaran berhasil (lihat bawah), jadi perangkat yang jamnya salah mengirim cap yang sudah terkoreksi
            // begitu pernah sekali tersambung ke server; sebelum itu belum bisa dikoreksi — lihat KDoc [SyncClock].
            // Cap yang sama masuk AAD amplop, jadi server tidak bisa menggantinya tanpa menggagalkan dekripsi.
            val stampedForWire = record.copy(changedAtMillis = clock.stamp(record.changedAtMillis))
            seal(scope, aead, type, stampedForWire, deviceId)?.also { sentByType.getOrPut(type) { mutableListOf() }.add(record) }
        }
        if (uploads.isEmpty()) return RemoteResult.Success(0)

        return when (val result = syncRemote.push(apiToken, uploads)) {
            is RemoteResult.Failure -> result
            is RemoteResult.Success -> {
                result.value.serverTimeMs?.let { clock.setServerOffset(it - clock.rawDeviceNowMillis()) }
                // Bersyarat per penangan: hanya versi yang BENAR-BENAR tidak berubah sejak dikumpulkan yang ditandai
                // terkirim. Suntingan yang masuk selama push berjalan tetap kotor dan terkirim di putaran berikutnya.
                for ((type, sent) in sentByType) handlersByType.getValue(type).markPushed(roomId, sent)
                RemoteResult.Success(uploads.size)
            }
        }
    }

    /**
     * Amplop umum (sync-desain.md bagian C) untuk satu rekaman [SharedRoomSyncHandler]. Kolom luar: `record_type`
     * konstan [SHARED_RECORD_TYPE] dan `record_id` = [SyncEnvelope.wireId] (keputusan pemilik #7: tipe dan id
     * transaksi tidak terbaca server). Null bila amplop tidak bisa dibuat (mis. melebihi batas 64 KB).
     */
    private fun seal(scope: SyncScope.SharedRoom, aead: SyncAead, type: SharedRecordType, record: OutgoingRecord, deviceId: String): SyncLogEntryUpload? {
        val sealed = try {
            SyncEnvelope.seal(
                aead = aead,
                scope = scope,
                type = type.wire,
                id = record.recordId,
                version = record.version,
                data = record.data,
                changedAtMillis = record.changedAtMillis,
                deleted = record.deleted,
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
            deleted = record.deleted,
            changedAt = Instant.ofEpochMilli(record.changedAtMillis).toString(),
            changedAtMillis = record.changedAtMillis,
        )
    }

    /** [scopedCursors] bila ada, jatuh ke [cursorStore] bila tabel belum pernah diisi (> 0) untuk cakupan ini. */
    private suspend fun readCursor(roomId: RoomId, scope: SyncScope.SharedRoom): Long {
        val fromTable = scopedCursors?.cursor(scope) ?: 0L
        return if (fromTable > 0L) fromTable else cursorStore.read(roomId.value)
    }

    /** Menulis ke KEDUA penyimpanan (lihat KDoc [scopedCursors]), supaya tidak ada sumber yang tertinggal. */
    private suspend fun writeCursor(roomId: RoomId, scope: SyncScope.SharedRoom, value: Long) {
        cursorStore.save(roomId.value, value)
        scopedCursors?.setCursor(scope, value)
    }

    private suspend fun pull(roomId: RoomId, deviceId: String, apiToken: String, initialAead: SyncAead): RemoteResult<PullOutcome> {
        val scope = SyncScope.SharedRoom(roomId)
        // Berubah bila sebuah key_grant diimpor di tengah pull: baris sesudahnya dibuka dengan kunci yang baru.
        var aead = initialAead
        var grantImported = false
        var cursor = readCursor(roomId, scope)
        var applied = 0
        var skipped = 0
        // Pagar sekali, sama alasannya dengan PersonalSyncCoordinator: 410 cursor_expired mengatur ulang kursor
        // RUANG INI SAJA ke 0 (kontrak klien butir 5), tidak pernah cakupan lain, dan hanya dicoba sekali per panggilan.
        var cursorResetUsed = false

        while (true) {
            // Cakupan ruang bersama (kontrak klien butir 1-3): scope="r", roomId=roomId.value. Server lama mengabaikan
            // param ini dan menjawab aliran gabungan tanpa gema; baris ruang LAIN di dalamnya tetap disaring di bawah
            // (downloaded.roomId != roomId.value), jadi fallback itu sudah aman seperti sebelum pull per cakupan ada.
            val page = when (val result = syncRemote.pullScoped(apiToken, deviceId, SCOPE_WIRE_ROOM, roomId.value, cursor)) {
                is RemoteResult.Failure -> {
                    if (!cursorResetUsed && result.error.rejectionCode() == ServerErrorCodes.CURSOR_EXPIRED) {
                        cursorResetUsed = true
                        cursor = 0L
                        writeCursor(roomId, scope, 0L)
                        continue
                    }
                    return result
                }
                is RemoteResult.Success -> result.value
            }
            page.serverTimeMs?.let { clock.setServerOffset(it - clock.rawDeviceNowMillis()) }

            for (downloaded in page.entries) {
                // Echo baris sendiri (sudah punya lokal) dilewati.
                if (downloaded.deviceId == deviceId) continue
                // Server mengirim baris dari semua ruang yang diikuti anggota ini; hanya baris ruang yang sedang
                // disinkron yang diterapkan, supaya transaksi ruang lain tidak masuk ke ruang ini (roadmap.md, Tahap 9).
                if (downloaded.roomId != roomId.value) continue

                // key_grant: bukan amplop umum (tidak disegel kunci ruang), jadi ditangani sebelum dispatch di bawah. Yang bukan
                // untuk perangkat ini dilewati diam-diam; yang untuknya diimpor lalu kunci pull diganti.
                if (downloaded.recordType == KeyGrant.RECORD_TYPE) {
                    if (applyGrant(scope, downloaded)) {
                        aead = roomKeys.getOrCreateRoomKey(roomId)
                        grantImported = true
                    }
                    continue
                }

                val outcome = when (downloaded.recordType) {
                    SHARED_RECORD_TYPE -> applyEnvelope(scope, downloaded, aead, record = true)
                    LEGACY_RECORD_TYPE_TRANSACTION -> applyLegacy(roomId, downloaded, aead, record = true)
                    // Tipe kabel lain (mis. dari versi aplikasi lebih baru) bukan urusan mesin ini: dilewati tanpa dihitung.
                    else -> continue
                }
                if (outcome == RowOutcome.APPLIED) applied++ else skipped++
            }

            cursor = page.nextAfter
            writeCursor(roomId, scope, cursor)
            if (!page.hasMore) break
        }

        // Baris yang ditunda (kursor sudah lewat) dicoba lagi di sini; yang berhasil tidak lagi terhitung dilewati.
        val retried = retryDeferred(scope, aead, retryKeyMissing = grantImported)
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
    private suspend fun retryDeferred(scope: SyncScope.SharedRoom, aead: SyncAead, retryKeyMissing: Boolean): Int {
        val store = issueStore ?: return 0
        val candidates = store.issues(scope).filter {
            (it.kind == SyncIssueKind.DEFERRED_MISSING_REF || (retryKeyMissing && it.kind == SyncIssueKind.DEFERRED_KEY_MISSING)) && it.rawRow != null
        }
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
     * tombstone palsu gagal dibuka dan dilewati. Dispatch ke [handlersByType] menurut tipe DI DALAM amplop (Tahap 2
     * lanjutan "mesin generik"): tipe tak dikenal atau tanpa penangan terdaftar, dan versi `d` lebih tinggi dari
     * yang dikenal penangan itu, ditolak sebagai `UNSUPPORTED_VERSION` tanpa diterapkan separuh.
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
            // Dekripsi gagal pada anggota yang bisa menerima key_grant (punya rahasia sendiri): paling mungkin kunci ruang
            // baru belum tiba, bukan data rusak. Disimpan untuk dicoba lagi begitu sebuah grant berhasil diimpor.
            if (e.reason == EnvelopeError.DECRYPT_FAILED && issueStore != null && memberSecrets?.readOwnSecret(scope.roomId) != null) {
                if (record) recordIssue(scope, row, SyncIssueKind.DEFERRED_KEY_MISSING, "kunci ruang yang baru belum diterima", rawRowOf(row, changedAtMillis))
                return RowOutcome.DEFERRED
            }
            return reject(scope, row, issueKindOf(e), e.message, record)
        } catch (e: IllegalArgumentException) {
            // payload bukan base64 yang sah
            return reject(scope, row, SyncIssueKind.TAMPERED_OR_CORRUPT, "payload bukan base64 yang sah", record)
        }
        val handler = SharedRecordType.fromWire(opened.type)?.let { handlersByType[it] }
            ?: return reject(scope, row, SyncIssueKind.UNSUPPORTED_VERSION, "tipe rekaman tidak dikenal", record)
        if (opened.version > handler.knownVersion) {
            return reject(scope, row, SyncIssueKind.UNSUPPORTED_VERSION, "versi payload ${opened.version} lebih baru dari yang dikenal", record)
        }

        val decoded = DecodedRecord(opened.type, opened.id, opened.version, opened.data)
        val missing = handler.missingReferences(scope.roomId, decoded)
        if (missing.isNotEmpty()) {
            if (record) recordIssue(scope, row, SyncIssueKind.DEFERRED_MISSING_REF, missing.joinToString(","), rawRowOf(row, changedAtMillis))
            return RowOutcome.DEFERRED
        }
        val applied = try {
            handler.apply(scope.roomId, decoded, changedAtMillis, authorOf(row))
            true
        } catch (e: TransactionSyncPayloadCorrupt) {
            false
        }
        if (!applied) return reject(scope, row, SyncIssueKind.TAMPERED_OR_CORRUPT, "isi pengeluaran tidak sah", record)
        return RowOutcome.APPLIED
    }

    /**
     * Baris format lama (sebelum amplop umum, 2026-11-01): `record_type` = `transaction`, `record_id` = id transaksi
     * terbaca, payload [TransactionSyncCodec] dengan AAD = id saja, tombstone berpayload kosong. Tetap dibaca supaya
     * riwayat yang sudah terkirim tidak hilang; tidak pernah ditulis lagi. Format ini hanya pernah membawa
     * pengeluaran (mendahului [SharedRoomSyncHandler]), jadi langsung ke [expenseHandler], bukan dispatch generik.
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
        // "Lain-lain" hanya dibutuhkan bila nama kategori kosong/null, sama seperti applyEnvelope (lihat
        // SharedExpenseSyncHandler.missingReferences).
        if (decoded.categoryName.isNullOrBlank() && !expenseHandler.hasFallbackCategory(roomId)) {
            if (record) recordIssue(scope, row, SyncIssueKind.DEFERRED_MISSING_REF, "ruang belum punya kategori Lain-lain", rawRowOf(row, changedAtMillis))
            return RowOutcome.DEFERRED
        }
        expenseHandler.applyDecodedTransaction(roomId, id, decoded, changedAtMillis, authorOf(row))
        return RowOutcome.APPLIED
    }

    // Penulis buram (bagian B R3): server menentukan; perangkat lama tanpa field ini jatuh
    // ke device_id supaya akun virtual tetap terbentuk, hanya kurang stabil lintas perangkat penulis.
    private fun authorOf(row: SyncLogEntryDownload): String = row.author ?: row.deviceId

    /**
     * Pemilik menerbitkan [KeyGrant] untuk SETIAP rahasia di [memberSecrets] (key_grant langkah 4): keyset kunci ruang
     * yang SEKARANG (biasanya baru dirotasi lewat [RoomMemberKeyRedistribution]) disegel per anggota dan dikirim dalam
     * satu push khusus, sebelum baris lain apa pun yang memakai kunci baru. Server hanya menerima push "g" dari pemilik
     * (403 `not_room_owner`). Idempoten: keyset yang sama menghasilkan `record_id` yang sama, jadi memanggil ulang
     * (mis. setelah jaringan putus) aman. Mengembalikan jumlah grant terkirim; tidak melempar.
     */
    suspend fun publishKeyGrants(roomId: RoomId, deviceId: String, memberSecrets: List<ByteArray>): RemoteResult<Int> {
        val apiToken = apiTokenStore.read() ?: return RemoteResult.Failure(RemoteError.Unauthorized)
        if (memberSecrets.isEmpty()) return RemoteResult.Success(0)
        val keyset = roomKeys.exportRoomKey(roomId)
        val stamped = clock.stamp(nowMillis())
        val uploads = memberSecrets.map { secret ->
            val sealed = KeyGrant.seal(secret, roomId, keyset)
            SyncLogEntryUpload(
                roomId = roomId.value,
                recordType = KeyGrant.RECORD_TYPE,
                recordId = sealed.recordId,
                deviceId = deviceId,
                payload = Base64.getEncoder().encodeToString(sealed.frame),
                deleted = false,
                changedAt = Instant.ofEpochMilli(stamped).toString(),
                changedAtMillis = stamped,
            )
        }
        return when (val result = syncRemote.push(apiToken, uploads)) {
            is RemoteResult.Failure -> result
            is RemoteResult.Success -> {
                result.value.serverTimeMs?.let { clock.setServerOffset(it - clock.rawDeviceNowMillis()) }
                RemoteResult.Success(uploads.size)
            }
        }
    }

    /**
     * Menerima satu baris `key_grant` (key_grant langkah 5). Mengembalikan true hanya bila grant itu untuk perangkat ini
     * dan keyset-nya berhasil digabung ke kunci ruang ([RoomKeyProvider.importRoomKey]). Grant untuk anggota lain, atau
     * perangkat tanpa rahasia sendiri, dilewati DIAM-DIAM (bukan masalah). Grant yang untuk perangkat ini tetapi tidak bisa
     * dibuka atau diimpor dicatat sebagai masalah sinkron (`TAMPERED_OR_CORRUPT`) tanpa isi apa pun.
     */
    private suspend fun applyGrant(scope: SyncScope.SharedRoom, row: SyncLogEntryDownload): Boolean {
        val secret = memberSecrets?.readOwnSecret(scope.roomId) ?: return false
        if (!KeyGrant.belongsTo(secret, scope.roomId, row.recordId)) return false
        return try {
            val keyset = KeyGrant.open(secret, scope.roomId, row.recordId, Base64.getDecoder().decode(row.payload))
            roomKeys.importRoomKey(scope.roomId, keyset)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordIssue(scope, row, SyncIssueKind.TAMPERED_OR_CORRUPT, "key_grant untuk perangkat ini tidak bisa dibuka (${e.javaClass.simpleName})")
            false
        }
    }

    private data class PullOutcome(val applied: Int, val skipped: Int)
}

sealed interface SyncResult {
    /** [skipped] = baris masuk yang dilewati karena payload rusak atau kategori tak dikenal. */
    data class Success(val pushed: Int, val applied: Int, val skipped: Int) : SyncResult

    data class Failed(val error: RemoteError) : SyncResult

    /** Belum pernah masuk dengan Google / token API belum ada — tidak pernah terjadi diam-diam. */
    data object NotSignedIn : SyncResult

    /**
     * Server menjawab `not_room_member` (403) selama push atau pull ruang ini (Tahap 9 lanjutan, kontrak klien butir
     * 5): perangkat ini sudah bukan anggota — tidak pernah bergabung, sudah keluar, atau sudah dikeluarkan pemilik
     * (server tidak membedakan ketiganya). BUKAN paywall dan bukan galat jaringan: [SyncCoordinator] sudah memanggil
     * `onNotRoomMember` sebelum mengembalikan ini, jadi pemanggil tidak perlu menandai apa pun lagi, hanya berhenti
     * menyinkronkan ruang ini (daftar ruang ACTIVE pada panggilan berikutnya sudah tidak memuatnya).
     */
    data object RemovedFromRoom : SyncResult
}
