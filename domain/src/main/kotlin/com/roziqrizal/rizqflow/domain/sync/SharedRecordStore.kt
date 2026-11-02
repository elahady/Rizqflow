package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId

/**
 * Versi tersimpan satu rekaman [SharedRecordType] di cakupan ruang bersama, lewat tabel `sync_record` yang SAMA
 * dengan cakupan pribadi ([SyncRecordMeta]/[SyncStore]) — beda hanya karena [SharedRecordType] SENGAJA terpisah dari
 * [SyncRecordType] (lihat KDoc-nya), bukan karena penyimpanannya berbeda. Tidak membawa [recordId]/`author` sebagai
 * bidang `type` terketik seperti [SyncRecordMeta]: pemanggil sudah tahu tipenya (satu [SharedRecordStore] per
 * [SharedRoomSyncHandler]), jadi tidak perlu dibaca balik.
 */
data class SharedRecordMeta(
    val recordId: String,
    override val changedAtMillis: Long,
    override val deleted: Boolean,
    override val dirty: Boolean,
    val author: String?,
    override val deviceId: String?,
) : SyncVersionMeta

data class SharedSentVersion(val recordId: String, val changedAtMillis: Long)

/**
 * Status kotor dan LWW untuk rekaman [SharedRecordType] di cakupan ruang bersama (peran-anggota.md E.2, H langkah
 * 8): "versi dan `author` tetap di `sync_record` seperti rekaman lain" — BUKAN jalur ad hoc baru seperti
 * `TransactionRepository.pendingSync`/`pendingTombstones` yang dipakai [SharedExpenseSyncHandler] (warisan sebelum
 * H langkah 7, lihat KDoc kelas itu).
 *
 * TIDAK memakai [SyncStore] langsung: metode-metodenya terkunci ke [SyncRecordType] (enum cakupan pribadi), dan
 * [SharedRecordType] sengaja tidak memakai enum itu (nama tipe menentukan `wireId` — mencampur dua enum berarti dua
 * tipe dengan nama sama di cakupan berbeda bisa menghasilkan `wireId` yang sama persis, lihat KDoc [SharedRecordType]).
 * Implementasinya ([RoomSharedRecordStore] di `:data`) memakai `SyncDao`/`SyncRecordEntity` yang SAMA dengan
 * [SyncStore] (kolomnya sudah String biasa di lapisan Room, bukan enum) — hanya lapisan Kotlin di domain ini yang
 * terketik beda; penyimpanan fisiknya satu tabel yang sama, seperti yang diminta peran-anggota.md E.2.
 *
 * Dipakai [EnvelopeSyncHandler]; `key_grant` (Tahap 5 roadmap.md, belum dikerjakan) kandidat pemakai berikutnya.
 */
interface SharedRecordStore {
    suspend fun meta(roomId: RoomId, type: SharedRecordType, recordId: String): SharedRecordMeta?

    /** Menandai rekaman kotor dengan cap dari [clock]; mengembalikan cap yang dipakai. */
    suspend fun markDirty(roomId: RoomId, type: SharedRecordType, recordId: String, deleted: Boolean, clock: SyncClock): Long

    /** Rekaman kotor satu ruang DAN satu tipe, tertua dulu. */
    suspend fun dirtyRecords(roomId: RoomId, type: SharedRecordType, limit: Int = 200): List<SharedRecordMeta>

    /** Sama kontrak dengan [SyncStore.markPushed]: bersyarat, hanya versi yang `changed_at`-nya belum berubah sejak dikumpulkan. */
    suspend fun markPushed(roomId: RoomId, type: SharedRecordType, sent: List<SharedSentVersion>): Int

    /**
     * Menerapkan satu versi masuk secara atomik dengan [SyncOrdering] (sama aturan dengan [SyncStore.applyIncoming]).
     * Disederhanakan dari itu: TANPA konteks [SyncApplying]/[SyncApplyAbortedException], karena [applyBlock] di sini
     * hanya menulis satu baris proyeksi sederhana (`shared_envelope`), bukan memanggil repositori lain yang punya
     * jalur transaksi bersarangnya sendiri yang bisa menelan galat (lihat KDoc [SyncStore.applyIncoming] soal risiko
     * itu) — risiko yang dijaga di sana tidak berlaku di sini.
     */
    suspend fun applyIncoming(
        roomId: RoomId,
        type: SharedRecordType,
        recordId: String,
        incoming: IncomingVersion,
        localDeviceId: String,
        applyBlock: suspend () -> Unit,
    ): ApplyDecision
}
