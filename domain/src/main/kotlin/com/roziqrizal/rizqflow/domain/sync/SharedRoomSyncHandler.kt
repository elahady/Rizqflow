package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId

/**
 * Penangan satu [SharedRecordType] di ruang bersama (Tahap 2 sync-desain.md lanjutan, "cakupan ruang bersama ke
 * mesin generik", roadmap.md Tahap 9 langkah 7 peran-anggota.md): menambah tipe baru (amplop, `key_grant`) berarti
 * menambah satu penangan ke [SyncCoordinator], tanpa mengubah kelas itu. Paralel dengan [SyncRecordHandler] cakupan
 * pribadi, tapi dikunci ke [SharedRecordType] (bukan [SyncRecordType] — lihat KDoc [SharedRecordType] soal kenapa
 * keduanya terpisah) dan [RoomId] langsung (bukan [SyncScope] umum), karena satu ruang bersama hanya satu kunci AEAD
 * dan satu kursor; [SyncCoordinator] sendiri yang membentuk [SyncScope.SharedRoom] saat membungkus/membuka amplop.
 *
 * Beda dari [SyncRecordHandler]: tidak ada [SyncStore] di baliknya (status kotor ruang bersama sudah di `sync_record`
 * sejak skema v11, tapi lewat metode buram-tipe `TransactionRepository`/DAO, bukan [SyncStore] yang terikat
 * [SyncRecordType] — lihat `LocalTransactionRepository`); [apply] dan [markPushed] karena itu menerima [changedAtMillis]
 * dan [author] secara eksplisit (di mesin pribadi keduanya tersimpan di `sync_record` dan ditangani [SyncStore] sendiri).
 */
interface SharedRoomSyncHandler {
    val type: SharedRecordType

    /** Versi payload tertinggi yang dimengerti penangan ini; versi masuk yang lebih baru ditolak sebagai `UNSUPPORTED_VERSION`. */
    val knownVersion: Int

    /** Rekaman tipe ini yang kotor (belum terkirim) di [roomId]. */
    suspend fun collectDirty(roomId: RoomId): List<OutgoingRecord>

    /**
     * Rujukan yang disebut [decoded] tetapi belum ada secara lokal di [roomId] — sama kontraknya dengan
     * [SyncRecordHandler.missingReferences]. Daftar kosong berarti aman diterapkan.
     */
    suspend fun missingReferences(roomId: RoomId, decoded: DecodedRecord): List<String>

    /**
     * Menerapkan satu rekaman yang sudah lolos dekripsi dan lolos [missingReferences]. Beda dari
     * [SyncRecordHandler.apply]: [SyncCoordinator] belum memakai [SyncStore.applyIncoming] untuk cakupan ini (lihat
     * KDoc kelas ini), jadi urutan total (LWW) ditegakkan DI DALAM implementasi (lihat `TransactionRepository.applyFromSync`),
     * bukan oleh mesin sebelum memanggil ini — [changedAtMillis] dan [author] karena itu wajib diteruskan apa adanya.
     */
    suspend fun apply(roomId: RoomId, decoded: DecodedRecord, changedAtMillis: Long, author: String)

    /** Menandai [sent] (hasil [collectDirty] yang cap waktunya sudah dijepit [SyncClock.stamp] dan berhasil dikirim) sudah terkirim. */
    suspend fun markPushed(roomId: RoomId, sent: List<OutgoingRecord>)
}
