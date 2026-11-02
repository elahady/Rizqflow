package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.ledger.EnvelopeEntry
import com.roziqrizal.rizqflow.domain.ledger.EnvelopeRepository
import com.roziqrizal.rizqflow.domain.model.RoomId

/**
 * Menulis amplop dari UI (sheet "Atur amplop", peran-anggota.md F; hanya Pemilik — ditegakkan UI lewat
 * [RoomPermissions.canManageEnvelope], bukan di sini) dan menandainya kotor lewat [SharedRecordStore] supaya
 * [EnvelopeSyncHandler.collectDirty] mengirimnya (D.2, H langkah 8). Id rekaman deterministik
 * ([EnvelopeRecordId.format]) membuat dua tulisan bulan yang sama konvergen lewat LWW, bukan menumpuk rekaman baru.
 */
class EnvelopeService(
    private val envelopes: EnvelopeRepository,
    private val store: SharedRecordStore,
    private val clock: SyncClock = SyncClock(),
) {
    /** [amount] `0` menghapus amplop anggota ini mulai [fromMonth] (D.2), bukan menghapus rekamannya. */
    suspend fun setEnvelope(roomId: RoomId, memberId: String, fromMonth: Int, amount: Long) {
        envelopes.upsert(roomId, EnvelopeEntry(memberId, fromMonth, amount))
        val recordId = EnvelopeRecordId.format(roomId, memberId, fromMonth)
        store.markDirty(roomId, SharedRecordType.ENVELOPE, recordId, deleted = false, clock = clock)
    }

    /** Semua rekaman amplop ruang ini, dasar [com.roziqrizal.rizqflow.domain.ledger.EnvelopeRules]. */
    suspend fun entries(roomId: RoomId): List<EnvelopeEntry> = envelopes.entries(roomId)
}
