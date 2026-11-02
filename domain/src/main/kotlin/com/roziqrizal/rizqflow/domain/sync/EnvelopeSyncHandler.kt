package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.ledger.EnvelopeEntry
import com.roziqrizal.rizqflow.domain.ledger.EnvelopeRepository
import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Penangan [SharedRecordType.ENVELOPE] lewat [SharedRoomSyncHandler] (peran-anggota.md H langkah 8): amplop
 * bulanan per anggota per ruang. BEDA sengaja dari [SharedExpenseSyncHandler]: status kotor dan LWW lewat
 * [SharedRecordStore] (tabel `sync_record` yang SAMA dengan cakupan pribadi — lihat KDoc [SharedRecordStore])
 * alih-alih `TransactionRepository.pendingSync` ad hoc. Ini pemakai PERTAMA [SharedRecordStore]/mesin generik
 * untuk sebuah [SharedRoomSyncHandler] baru, memvalidasi langsung alasan H langkah 7 ("cakupan ruang bersama ke
 * mesin generik") dikerjakan lebih dulu — lihat paragraf CLAUDE.md 2026-11-02 soal pilihan desain ini.
 *
 * Amplop TIDAK PERNAH ditombstone: `amount = 0` adalah rekaman HIDUP yang berarti "amplop dihapus mulai bulan
 * ini" (D.2), bukan `deleted = true`. [collectDirty]/[apply] karena itu tidak pernah menangani cabang tombstone
 * sungguhan untuk tipe ini (beda dari [SharedExpenseSyncHandler], yang pengeluarannya memang bisa dihapus).
 *
 * Otorisasi D.4 ("hanya Pemilik menulis amplop", server tidak bisa menegakkannya karena `record_type` disamarkan)
 * ditegakkan DI [apply], terhadap cache [SharedMemberRepository] — BUKAN di [missingReferences] (beda tujuan:
 * itu untuk rujukan yang belum tiba dan dicoba lagi lewat `DEFERRED_MISSING_REF`; ini penolakan PERMANEN yang
 * tidak pernah dicoba ulang, dicatat [SyncIssueKind.UNAUTHORIZED_AUTHOR]). Cache [SharedMemberRepository] yang
 * belum punya baris untuk [author] (anggota baru yang caches-nya belum lengkap) DITOLAK juga di sini (beda dari
 * `missingReferences`): tanpa bukti peran, amplop itu tidak bisa dibuktikan berasal dari Pemilik.
 *
 * Keterbatasan yang diketahui (sengaja, karena antarmuka [SharedRoomSyncHandler.apply] TIDAK membawa id perangkat
 * penulis — hanya [author]): [IncomingVersion.deviceId] dan `localDeviceId` di panggilan [SharedRecordStore.applyIncoming]
 * memakai [author] sebagai pendekatan, bukan id perangkat sungguhan. Hanya berpengaruh pada pemutus seri LWW saat
 * DUA tulisan punya `changed_at` MILIDETIK yang BENAR-BENAR sama dari device berbeda milik Pemilik yang sama —
 * kemungkinan yang sudah sangat kecil karena [SyncClock.stamp] menjepit cap selalu naik.
 */
class EnvelopeSyncHandler(
    private val store: SharedRecordStore,
    private val envelopes: EnvelopeRepository,
    private val members: SharedMemberRepository,
    /** Pencatat `sync_issue` untuk [SyncIssueKind.UNAUTHORIZED_AUTHOR]; null (tes lama) berarti ditolak diam-diam tanpa dicatat. */
    private val issues: SyncStore? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : SharedRoomSyncHandler {
    override val type: SharedRecordType = SharedRecordType.ENVELOPE
    override val knownVersion: Int = EnvelopePayload.VERSION

    override suspend fun collectDirty(roomId: RoomId): List<OutgoingRecord> {
        val dirty = store.dirtyRecords(roomId, type)
        if (dirty.isEmpty()) return emptyList()
        return dirty.mapNotNull { meta ->
            val ref = EnvelopeRecordId.parse(meta.recordId) ?: return@mapNotNull null
            val data = if (meta.deleted) {
                JsonObject(emptyMap())
            } else {
                val entry = envelopes.find(roomId, ref.memberId, ref.fromMonth) ?: return@mapNotNull null
                EnvelopePayload.encode(roomId, ref.memberId, ref.fromMonth, entry.amount)
            }
            OutgoingRecord(meta.recordId, knownVersion, meta.changedAtMillis, meta.deleted, data)
        }
    }

    /** Anggota tujuan amplop ([ref.memberId]) tidak wajib sudah tercache (D.4): lihat KDoc kelas ini. */
    override suspend fun missingReferences(roomId: RoomId, decoded: DecodedRecord): List<String> = emptyList()

    override suspend fun apply(roomId: RoomId, decoded: DecodedRecord, changedAtMillis: Long, author: String) {
        val member = members.find(roomId, author)
        val authorized = member != null && member.role == SharedRoomRole.OWNER && member.status == SharedRoomStatus.ACTIVE
        if (!authorized) {
            recordUnauthorized(roomId, decoded)
            return
        }
        if (decoded.data.isEmpty()) return // amplop tidak pernah tombstone (lihat KDoc kelas); lewati dengan tenang bila suatu saat ada.

        val payload = EnvelopePayload.decode(decoded.data)
        if (payload.roomId != roomId) throw TransactionSyncPayloadCorrupt()

        val incoming = IncomingVersion(changedAtMillis = changedAtMillis, deleted = false, deviceId = author, author = author)
        store.applyIncoming(roomId, type, decoded.id, incoming, localDeviceId = author) {
            envelopes.upsert(roomId, EnvelopeEntry(payload.memberId, payload.fromMonth, payload.amount))
        }
    }

    override suspend fun markPushed(roomId: RoomId, sent: List<OutgoingRecord>) {
        val versions = sent.map { SharedSentVersion(it.recordId, it.changedAtMillis) }
        if (versions.isNotEmpty()) store.markPushed(roomId, type, versions)
    }

    private suspend fun recordUnauthorized(roomId: RoomId, decoded: DecodedRecord) {
        val store = issues ?: return
        try {
            store.recordIssue(
                SyncIssue(
                    scope = SyncScope.SharedRoom(roomId),
                    recordType = type.wire,
                    recordId = decoded.id,
                    kind = SyncIssueKind.UNAUTHORIZED_AUTHOR,
                    detail = "Amplop dari penulis yang bukan Pemilik aktif ruang ini",
                    createdAtMillis = nowMillis(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Buku catatan masalah adalah bantuan diagnosis; sync tetap berjalan tanpanya.
        }
    }
}
