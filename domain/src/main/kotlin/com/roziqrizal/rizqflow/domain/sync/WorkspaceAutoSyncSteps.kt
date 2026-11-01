package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId

/**
 * Langkah sync otomatis satu ruang kerja ([AutoSyncSteps]): pribadi lalu tiap ruang bersama aktif. Dirakit
 * `AccountWorkspace` dengan koordinator sungguhan; di sini hanya urutan dan satu aturan gerbang, supaya bisa diuji.
 *
 * **Gerbang kode pemulihan untuk sync otomatis (usulan Claude, belum dikonfirmasi pemilik):** cakupan pribadi baru
 * disentuh bila [recoveryConfigured] (status `Configured`: kode diatur di perangkat ini, ATAU kunci akun dipulihkan di
 * sini). Keputusan #14 hanya menahan push; `PersonalSyncCoordinator` sengaja membiarkan pull berjalan agar perangkat
 * baru bisa menerima data. Tetapi pull OTOMATIS di perangkat baru yang belum memulihkan kunci akan membuka baris
 * terenkripsi kunci perangkat lain dengan kunci acak lokal, gagal (`TAMPERED_OR_CORRUPT`), dan kursor tetap maju
 * melewatinya; alur "Gabung" di layar perangkat baru tidak mereset kursor, jadi barisnya hilang selamanya. Karena itu
 * sebelum `Configured` langkah pribadi tidak mengirim request apa pun dan melaporkan
 * [ScopeSyncOutcome.Done.pushWaitsForRecovery] supaya UI mengajak mengatur atau memulihkan kode. Tombol manual lewat
 * langkah yang sama; layar perangkat baru tetap memakai `PersonalSyncCoordinator.syncNow` langsung (jalur sengaja).
 * Ruang bersama tidak bergantung pada kunci akun (kunci ruang datang dari undangan QR), jadi tetap berjalan.
 */
class WorkspaceAutoSyncSteps(
    private val recoveryConfigured: () -> Boolean,
    private val personalSync: suspend () -> PersonalSyncResult,
    private val activeSharedRoomIds: suspend () -> List<RoomId>,
    /** Wajib lewat `SharedRoomAutoSync.withRoomLock` di produksi (satu Mutex per ruang bersama dengan tombol manual ruang itu). */
    private val sharedRoomSync: suspend (RoomId) -> SyncResult,
) : AutoSyncSteps {
    override suspend fun personal(): ScopeSyncOutcome {
        if (!recoveryConfigured()) return ScopeSyncOutcome.Done(pushed = 0, applied = 0, pushWaitsForRecovery = true)
        return personalSync().toScopeOutcome()
    }

    override suspend fun activeSharedRooms(): List<RoomId> = activeSharedRoomIds()

    override suspend fun sharedRoom(roomId: RoomId): ScopeSyncOutcome = sharedRoomSync(roomId).toScopeOutcome()
}
