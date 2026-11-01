package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.SharedRecordType
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncClock
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.isSyncApplying

/**
 * Pencatat status kotor sinkron ruang bersama (sync-desain.md bagian A dan butir 2.2 tahap 2): satu baris `sync_record`
 * per (ruang, pengeluaran) dengan `scope = r:<roomId>`, `record_type = shared_expense`, `record_id` = id transaksi lokal.
 * Menggantikan `money_transaction.synced_at` dan `pending_room_tombstone` sebagai sumber "apa yang belum terkirim".
 *
 * Kunci `sync_record` sudah per (cakupan, tipe, id), jadi versi hidup dan tombstone untuk satu id di satu ruang adalah SATU
 * baris yang saling menggantikan, dan tiap ruang punya barisnya sendiri. Dipanggil dari `LocalTransactionRepository` DI
 * DALAM transaksi Room yang sama dengan penulisan datanya, sama seperti [SyncRecorder]. Di konteks `applying` tidak
 * melakukan apa pun. Hanya ruang yang pernah dibagi (punya baris `shared_room`) yang dicatat: pengeluaran di ruang pribadi
 * tidak pernah menerbitkan apa pun di `r:*` (riwayatnya dijangkau [SharedRoomActivation] begitu ruang mulai dibagi).
 */
class SharedRoomRecorder(private val db: RizqflowDatabase, private val clock: SyncClock = SyncClock()) {
    private fun scopeOf(roomId: String) = SyncScope.SharedRoom(RoomId(roomId)).wire

    /** Pengeluaran buatan sendiri kini hidup di [roomId]: tandai kotor (menggantikan tombstone di ruang itu bila ada). */
    suspend fun live(roomId: String, transactionId: String) {
        if (isSyncApplying()) return
        if (db.sync().sharedRoom(roomId) == null) return
        db.sync().markDirtyWire(scopeOf(roomId), SharedRecordType.SHARED_EXPENSE.wire, transactionId, deleted = false, clock = clock)
    }

    /**
     * Pengeluaran buatan sendiri tidak ada lagi di [roomId] (dihapus atau dipindah keluar): tandai tombstone kotor.
     * [onlyIfActive] true (pindah keluar) hanya mencatat untuk ruang bersama yang masih AKTIF; hapus mencatat untuk ruang
     * yang pernah dibagi dengan status apa pun, supaya anggota ikut menghapusnya begitu perangkat ini tersambung lagi.
     */
    suspend fun tombstone(roomId: String, transactionId: String, onlyIfActive: Boolean) {
        if (isSyncApplying()) return
        val shared = db.sync().sharedRoom(roomId) ?: return
        if (onlyIfActive && shared.status != SharedRoomStatus.ACTIVE.name) return
        db.sync().markDirtyWire(scopeOf(roomId), SharedRecordType.SHARED_EXPENSE.wire, transactionId, deleted = true, clock = clock)
    }
}
