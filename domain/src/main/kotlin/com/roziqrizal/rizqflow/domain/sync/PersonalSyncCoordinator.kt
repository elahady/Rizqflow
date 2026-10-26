package com.roziqrizal.rizqflow.domain.sync

import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Sync push/pull cakupan PRIBADI (Tahap 3 sync-desain.md): mesin GENERIK pertama yang memakai
 * [SyncEnvelope]/[SyncRecordHandler]/[SyncStore] dari Tahap 1 secara utuh (berbeda dari
 * [SyncCoordinator] ruang bersama, yang masih memakai codec ad hoc [TransactionSyncCodec] — lihat
 * KDoc kelas itu). Satu handler per [SyncRecordType]; tahap berikutnya menambah entitas cukup
 * menambah handler ke [handlers], tanpa mengubah kelas ini.
 *
 * - **Identitas R1** (bagian B): `record_id` DI DALAM amplop = id lokal apa adanya; `record_id` DI
 *   LUAR amplop (ke server) = [SyncEnvelope.wireId] (hash, keputusan pemilik #7 "samarkan record_id").
 * - **Urutan penerapan per halaman** (bagian B): satu halaman pull diurutkan lewat [SyncApplyOrder]
 *   (peringkat tipe, live sebelum tombstone, `changed_at`, id server) SEBELUM diterapkan — BUKAN
 *   urutan kedatangan jaringan/JSON.
 * - **Referensi belum tiba**: [SyncRecordHandler.missingReferences] diperiksa SEBELUM
 *   [SyncStore.applyIncoming] dipanggil untuk satu versi; bila tidak kosong, versi itu TIDAK
 *   diterapkan (meta tidak maju) dan dicatat `sync_issue(DEFERRED_MISSING_REF)` dengan [DeferredPullRow]
 *   di [SyncIssue.rawRow] supaya bisa dicoba lagi TANPA pull ulang (kursor sudah lewat baris itu) —
 *   sekali di akhir [pull] ini ([retryDeferred]) dan lagi di awal sync berikutnya (dipanggil juga dari
 *   [pull]). Setelah 30 hari atau 20 percobaan, baris tetap tersimpan di `sync_issue` (bukan dihapus)
 *   — UI "Riwayat sinkron" yang menandainya gagal permanen dari `attempts`/`created_at` bukan cakupan ini.
 * - **Gerbang kode pemulihan** (bagian F, keputusan pemilik #14): [push] TIDAK mengirim satu baris
 *   pun sampai [recovery] melaporkan [RecoveryStatus.Configured]. [pull] TIDAK digerbang (perangkat
 *   baru harus bisa menerima data sebelum sempat mengatur kode pemulihan).
 * - **Tanpa penjepitan jam ulang saat push** (beda dari [SyncCoordinator] ruang bersama, sengaja):
 *   `changed_at` yang dikirim SAMA PERSIS dengan yang tersimpan di `sync_record` (sudah distempel
 *   [SyncClock] saat [SyncRecorder] menandainya kotor). Menjepit ulang di titik push akan membuat
 *   nilai yang dikirim berbeda dari yang dibandingkan [SyncOrdering] secara lokal untuk rekaman yang
 *   SAMA (beda dari transaksi ruang bersama yang dibandingkan terhadap kolom lain, bukan `sync_record`
 *   diri sendiri) — bisa memutus deteksi echo. Lihat juga [SyncClock] "Risiko jam jauh ke depan".
 *
 * "Tenang bukan panik": kegagalan jaringan/server selalu [PersonalSyncResult.Failed], tidak pernah melempar.
 */
class PersonalSyncCoordinator(
    handlers: List<SyncRecordHandler>,
    private val store: SyncStore,
    private val accountKeys: AccountKeyProvider,
    private val syncRemote: SyncRemote,
    private val apiTokenStore: ApiTokenStore,
    private val recovery: RecoveryKeyService,
    private val clock: SyncClock = SyncClock(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val handlersByType: Map<SyncRecordType, SyncRecordHandler> = handlers.associateBy { it.type }

    suspend fun syncNow(deviceId: String): PersonalSyncResult {
        val apiToken = apiTokenStore.read() ?: return PersonalSyncResult.NotSignedIn
        val aead = accountKeys.getOrCreateAccountKey()

        val pulled = when (val result = pull(deviceId, apiToken, aead)) {
            is RemoteResult.Failure -> return PersonalSyncResult.Failed(result.error)
            is RemoteResult.Success -> result.value
        }

        if (recovery.status() !is RecoveryStatus.Configured) {
            return PersonalSyncResult.Success(pushed = 0, applied = pulled.applied, skipped = pulled.skipped, pushGated = true)
        }

        return when (val pushed = push(deviceId, apiToken, aead)) {
            is RemoteResult.Failure -> PersonalSyncResult.Failed(pushed.error)
            is RemoteResult.Success -> PersonalSyncResult.Success(pushed.value, pulled.applied, pulled.skipped, pushGated = false)
        }
    }

    // ---- Push (digerbang) ----

    private suspend fun push(deviceId: String, apiToken: String, aead: SyncAead): RemoteResult<Int> {
        val outgoing = handlersByType.values.flatMap { handler -> handler.collectDirty(SyncScope.Personal).map { handler.type to it } }
        if (outgoing.isEmpty()) return RemoteResult.Success(0)

        val uploads = outgoing.map { (type, record) ->
            val sealed = SyncEnvelope.seal(
                aead = aead,
                scope = SyncScope.Personal,
                type = type.wire,
                id = record.recordId,
                version = record.version,
                data = record.data,
                changedAtMillis = record.changedAtMillis,
                deleted = record.deleted,
                deviceId = deviceId,
            )
            record to SyncLogEntryUpload(
                roomId = null,
                recordType = type.wire,
                recordId = sealed.wireId,
                deviceId = deviceId,
                payload = Base64.getEncoder().encodeToString(sealed.ciphertext),
                deleted = record.deleted,
                changedAt = java.time.Instant.ofEpochMilli(record.changedAtMillis).toString(),
                changedAtMillis = record.changedAtMillis,
            )
        }

        return when (val result = syncRemote.push(apiToken, uploads.map { it.second })) {
            is RemoteResult.Failure -> result
            is RemoteResult.Success -> {
                result.value.serverTimeMs?.let { clock.setServerOffset(it - clock.rawDeviceNowMillis()) }
                val sent = uploads.map { (record, upload) ->
                    val type = checkNotNull(SyncRecordType.fromWire(upload.recordType))
                    SentVersion(type, record.recordId, record.changedAtMillis)
                }
                store.markPushed(SyncScope.Personal, sent)
                RemoteResult.Success(uploads.size)
            }
        }
    }

    // ---- Pull (tidak digerbang) ----

    private suspend fun pull(deviceId: String, apiToken: String, aead: SyncAead): RemoteResult<PullOutcome> {
        var cursor = store.cursor(SyncScope.Personal)
        var applied = 0
        var skipped = 0
        var deferred = 0

        while (true) {
            val page = when (val result = syncRemote.pull(apiToken, deviceId, cursor)) {
                is RemoteResult.Failure -> return result
                is RemoteResult.Success -> result.value
            }
            page.serverTimeMs?.let { clock.setServerOffset(it - clock.rawDeviceNowMillis()) }

            val outcome = applyPage(page.entries, deviceId, aead)
            applied += outcome.applied
            skipped += outcome.skipped
            deferred += outcome.deferred

            cursor = page.nextAfter
            store.setCursor(SyncScope.Personal, cursor)
            if (!page.hasMore) break
        }

        val retried = retryDeferred(aead)
        applied += retried.applied

        return RemoteResult.Success(PullOutcome(applied, skipped + (deferred - retried.applied)))
    }

    /** Satu halaman: urutkan lewat [SyncApplyOrder], lalu terapkan berurutan (bagian B "Referensi belum tiba"). */
    private suspend fun applyPage(entries: List<SyncLogEntryDownload>, deviceId: String, aead: SyncAead): PageOutcome {
        data class Prepared(val ref: PulledRef, val row: SyncLogEntryDownload, val decoded: DecodedRecord, val type: SyncRecordType)

        var skipped = 0
        val prepared = mutableListOf<Prepared>()
        for (row in entries) {
            // Hanya cakupan pribadi (room_id null) dan tipe yang punya handler di sini; baris ruang bersama atau
            // tipe tahap berikutnya (mis. debt, Tahap 4) dilewati diam-diam, bukan masalah — bukan cakupan ini.
            if (row.roomId != null) continue
            val type = SyncRecordType.fromWire(row.recordType) ?: continue
            val handler = handlersByType[type] ?: continue
            // Echo baris sendiri: optimisasi (SyncOrdering sendiri sudah aman terhadap echo walau ini dilewati).
            if (row.deviceId == deviceId) continue

            val changedAtMillis = row.changedAtMillis ?: java.time.Instant.parse(row.changedAt).toEpochMilli()
            val decoded = try {
                SyncEnvelope.open(aead, SyncScope.Personal, row.recordId, changedAtMillis, row.deleted, row.deviceId, Base64.getDecoder().decode(row.payload))
            } catch (e: SyncEnvelopeException) {
                store.recordIssue(
                    SyncIssue(SyncScope.Personal, row.recordType, row.recordId, SyncIssueKind.TAMPERED_OR_CORRUPT, row.id, e.message, null, 0, nowMillis()),
                )
                skipped++
                continue
            }
            prepared += Prepared(PulledRef(type, changedAtMillis, row.deleted, row.id), row, decoded, type)
        }

        prepared.sortWith(compareBy(SyncApplyOrder) { it.ref })

        var applied = 0
        var deferred = 0
        for (p in prepared) {
            val handler = handlersByType.getValue(p.type)
            val missing = handler.missingReferences(SyncScope.Personal, p.decoded)
            if (missing.isNotEmpty()) {
                store.recordIssue(deferredIssue(p.row, p.type, p.decoded, missing.joinToString(",")))
                deferred++
                continue
            }
            if (applyOne(p.row, p.type, p.decoded, deviceId, handler)) applied++ else skipped++
        }
        return PageOutcome(applied, skipped, deferred)
    }

    /** Dicoba lagi di akhir [pull] ini DAN akan dicoba lagi di awal sync berikutnya (dipanggil lagi lewat [pull]). */
    private suspend fun retryDeferred(aead: SyncAead): PullOutcome {
        val candidates = store.issues(SyncScope.Personal).filter { it.kind == SyncIssueKind.DEFERRED_MISSING_REF && it.rawRow != null }
        var applied = 0
        for (issue in candidates) {
            val type = SyncRecordType.fromWire(issue.recordType) ?: continue
            val handler = handlersByType[type] ?: continue
            val raw = DeferredPullRow.decode(issue.rawRow!!) ?: continue
            val decoded = try {
                SyncEnvelope.open(aead, SyncScope.Personal, raw.wireId, raw.changedAtMillis, raw.deleted, raw.deviceId, Base64.getDecoder().decode(raw.ciphertextBase64))
            } catch (_: SyncEnvelopeException) {
                continue
            }
            val missing = handler.missingReferences(SyncScope.Personal, decoded)
            if (missing.isNotEmpty()) {
                store.incrementIssueAttempts(issue.id)
                continue
            }
            val row = SyncLogEntryDownload(raw.serverId ?: 0L, null, type.wire, raw.wireId, raw.deviceId, raw.ciphertextBase64, raw.deleted, java.time.Instant.ofEpochMilli(raw.changedAtMillis).toString(), raw.changedAtMillis, raw.author)
            if (applyOne(row, type, decoded, raw.deviceId, handler)) {
                applied++
                store.resolveIssue(issue.id)
            } else {
                store.incrementIssueAttempts(issue.id)
            }
        }
        return PullOutcome(applied, 0)
    }

    /** @return true bila diterapkan (atau sah diabaikan sebagai echo/duplikat), false bila gagal (dicatat sebagai masalah untuk dicoba lagi). */
    private suspend fun applyOne(row: SyncLogEntryDownload, type: SyncRecordType, decoded: DecodedRecord, localDeviceId: String, handler: SyncRecordHandler): Boolean {
        val changedAtMillis = row.changedAtMillis ?: java.time.Instant.parse(row.changedAt).toEpochMilli()
        val incoming = IncomingVersion(changedAtMillis, row.deleted, row.deviceId, row.author, row.id)
        return try {
            store.applyIncoming(SyncScope.Personal, type, decoded.id, incoming, localDeviceId) { handler.apply(SyncScope.Personal, decoded) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tulis gagal (mis. FK RESTRICT lokal seperti zakat_payment.transaction_id, Tahap 4) atau pembatalan
            // transaksi diam-diam (SyncApplyAbortedException) — meta TIDAK maju (withTransaction batal bersih),
            // jadi kursor yang sudah lewat baris ini akan kehilangannya kalau tidak dicatat di sini: simpan
            // rawRow supaya [retryDeferred] bisa mencoba lagi tanpa pull ulang. DEFERRED_FK_RESTRICT dipakai
            // sebagai label umum "gagal tulis, coba lagi nanti" (tidak ada SyncIssueKind khusus "gagal tak terduga").
            store.recordIssue(
                SyncIssue(SyncScope.Personal, type.wire, decoded.id, SyncIssueKind.DEFERRED_FK_RESTRICT, row.id, e.message, rawRowOf(row), 0, nowMillis()),
            )
            false
        }
    }

    private fun deferredIssue(row: SyncLogEntryDownload, type: SyncRecordType, decoded: DecodedRecord, detail: String): SyncIssue {
        val changedAtMillis = row.changedAtMillis ?: java.time.Instant.parse(row.changedAt).toEpochMilli()
        return SyncIssue(SyncScope.Personal, type.wire, decoded.id, SyncIssueKind.DEFERRED_MISSING_REF, row.id, detail, rawRowOf(row), 0, nowMillis())
    }

    private fun rawRowOf(row: SyncLogEntryDownload): String {
        val changedAtMillis = row.changedAtMillis ?: java.time.Instant.parse(row.changedAt).toEpochMilli()
        return DeferredPullRow(row.recordId, changedAtMillis, row.deleted, row.deviceId, row.author, row.id, row.payload).encode().toString()
    }

    private data class PullOutcome(val applied: Int, val skipped: Int)
    private data class PageOutcome(val applied: Int, val skipped: Int, val deferred: Int)
}

/**
 * Isi [SyncIssue.rawRow] untuk `DEFERRED_MISSING_REF`/`DEFERRED_FK_RESTRICT` (Tahap 3): cukup untuk
 * mengulang [SyncEnvelope.open] tanpa pull ulang (kursor server sudah lewat baris ini). JSON dibangun
 * manual (bukan `@Serializable`): `:domain` sengaja hanya memuat RUNTIME `kotlinx-serialization-json`
 * tanpa plugin kompiler (lihat `domain/build.gradle.kts`, keputusan arsitektur Tahap 1), sama seperti
 * [SyncEnvelope] sendiri membangun/membaca `JsonObject`-nya manual.
 */
internal data class DeferredPullRow(
    val wireId: String,
    val changedAtMillis: Long,
    val deleted: Boolean,
    val deviceId: String,
    val author: String?,
    val serverId: Long?,
    val ciphertextBase64: String,
) {
    fun encode(): JsonObject = buildJsonObject {
        put("wire_id", wireId)
        put("changed_at_millis", changedAtMillis)
        put("deleted", deleted)
        put("device_id", deviceId)
        author?.let { put("author", it) }
        serverId?.let { put("server_id", it) }
        put("ciphertext_base64", ciphertextBase64)
    }

    companion object {
        /** Null bila JSON rusak (mis. ditulis versi aplikasi lain yang tidak kompatibel) — pemanggil melewatinya dengan aman. */
        fun decode(json: String): DeferredPullRow? = try {
            val obj = Json.parseToJsonElement(json) as JsonObject
            DeferredPullRow(
                wireId = (obj["wire_id"] as JsonPrimitive).content,
                changedAtMillis = (obj["changed_at_millis"] as JsonPrimitive).long,
                deleted = (obj["deleted"] as JsonPrimitive).boolean,
                deviceId = (obj["device_id"] as JsonPrimitive).content,
                author = (obj["author"] as? JsonPrimitive)?.contentOrNull,
                serverId = (obj["server_id"] as? JsonPrimitive)?.longOrNull,
                ciphertextBase64 = (obj["ciphertext_base64"] as JsonPrimitive).content,
            )
        } catch (_: Exception) {
            null
        }
    }
}

private val JsonPrimitive.boolean: Boolean get() = booleanOrNull ?: error("Bukan boolean: $this")
private val JsonPrimitive.long: Long get() = longOrNull ?: error("Bukan angka: $this")

sealed interface PersonalSyncResult {
    /** [pushGated] true bila push dilewati karena kode pemulihan belum [RecoveryStatus.Configured] (bagian F #14). */
    data class Success(val pushed: Int, val applied: Int, val skipped: Int, val pushGated: Boolean) : PersonalSyncResult

    data class Failed(val error: RemoteError) : PersonalSyncResult

    data object NotSignedIn : PersonalSyncResult
}
