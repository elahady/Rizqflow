package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId

/**
 * Hasil "sinkron ulang penuh". [personal] selalu terisi; [sharedRooms] hanya terisi bila sinkron pribadi berhasil
 * (bila gagal, ruang bersama tidak dicoba lagi: jaringan atau sesi yang sama kemungkinan besar gagal juga).
 */
data class FullResyncOutcome(
    val personal: PersonalSyncResult,
    val sharedRooms: Map<RoomId, SyncResult>,
) {
    /** Berhasil bila sinkron pribadi sukses dan tidak ada ruang bersama yang gagal. */
    val complete: Boolean
        get() = personal is PersonalSyncResult.Success && sharedRooms.values.all { it is SyncResult.Success }

    /** Galat jaringan/server pertama (pribadi dulu), untuk pesan lembut; null bila berhasil atau bukan galat remote. */
    val firstError: RemoteError?
        get() = (personal as? PersonalSyncResult.Failed)?.error
            ?: sharedRooms.values.filterIsInstance<SyncResult.Failed>().firstOrNull()?.error

    /** Jumlah baris yang diterapkan dari server pada sinkron ulang ini (pribadi + ruang bersama). */
    val applied: Int
        get() = ((personal as? PersonalSyncResult.Success)?.applied ?: 0) +
            sharedRooms.values.filterIsInstance<SyncResult.Success>().sumOf { it.applied }
}

/**
 * Diagnostik "sinkron ulang penuh" (sync-desain.md bagian I Tahap 7): mereset kursor pull lokal ke 0 untuk cakupan
 * pribadi dan semua ruang bersama aktif, lalu menarik ulang semuanya dari awal. TIDAK menghapus data lokal apa pun,
 * dan tidak menyentuh penanda kotor, jadi edit yang belum terkirim tetap terkirim.
 *
 * Mengapa aman diulang kapan saja: penerapan hasil pull idempoten lewat urutan total [SyncOrdering] (versi yang
 * sama atau lebih lama dari yang tersimpan diabaikan), dan masalah tertunda yang sama tidak dicatat dua kali (lihat
 * [SyncStore.recordIssue]). Bila langkah tarik gagal di tengah, kursor sudah 0 dan sync berikutnya (otomatis atau
 * tombol) melanjutkan dari awal, jadi tidak ada keadaan setengah jadi yang perlu dibereskan.
 *
 * Yang dipakai: `SyncStore.setCursor` (cakupan pribadi) dan `SyncCursorStore.save` (ruang bersama), keduanya sudah
 * ada. Pull cakupan pribadi dipanggil dengan baris perangkat ini sendiri ikut diterapkan ulang ([personal] diberi
 * `reapplyOwnRows = true` oleh pemasang): berguna bila berkas database dipulihkan dari cadangan lama sehingga baris
 * yang pernah dikirim perangkat ini hilang dari lokal. Pull ruang bersama tetap melewati baris perangkat sendiri.
 */
class FullResync(
    private val store: SyncStore,
    private val cursors: SyncCursorStore,
    private val personal: suspend () -> PersonalSyncResult,
    private val shared: suspend (RoomId) -> SyncResult,
) {
    suspend fun run(): FullResyncOutcome {
        val activeRooms = store.sharedRooms().filter { it.status == SharedRoomStatus.ACTIVE }.map { it.roomId }
        store.setCursor(SyncScope.Personal, 0)
        for (roomId in activeRooms) {
            cursors.save(roomId.value, 0)
            // Tahap 9 lanjutan: SyncCoordinator boleh membaca kursor ruang dari tabel sync_cursor (SyncStore) bila
            // dirakit dengan itu (lihat KDoc SyncCoordinator.scopedCursors) — reset harus menyentuh KEDUA penyimpanan,
            // supaya sisa kursor lama di tabel tidak "menang" dan membatalkan sinkron ulang penuh ini.
            store.setCursor(SyncScope.SharedRoom(roomId), 0)
        }

        val personalResult = personal()
        if (personalResult !is PersonalSyncResult.Success) return FullResyncOutcome(personalResult, emptyMap())

        val sharedResults = LinkedHashMap<RoomId, SyncResult>()
        for (roomId in activeRooms) sharedResults[roomId] = shared(roomId)
        return FullResyncOutcome(personalResult, sharedResults)
    }
}
