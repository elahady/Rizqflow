package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.db.SyncDao
import com.roziqrizal.rizqflow.data.db.SyncRecordEntity
import com.roziqrizal.rizqflow.domain.sync.SyncClock
import com.roziqrizal.rizqflow.domain.sync.SyncRecordType
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import com.roziqrizal.rizqflow.domain.sync.isSyncApplying

/**
 * Pencatat perubahan lokal untuk sinkron (Tahap 1 sync-desain.md bagian E, USULAN Claude). Dipanggil
 * dari `Local*Repository` DI DALAM transaksi Room yang sama dengan penulisan datanya (hook, bukan
 * trigger SQL): data dan penanda kotor tersimpan bersama atau batal bersama.
 *
 * - [changed] menandai rekaman kotor dengan cap baru `max(jam + offset, changed_at lama + 1)`.
 * - [deleted] menulis tombstone (rekaman dihapus tetapi versinya tetap dikirim supaya perangkat lain ikut menghapus).
 * - Di konteks `applying` (hasil pull sedang diterapkan) tidak melakukan apa-apa, supaya hasil pull
 *   tidak memicu push balik.
 *
 * Aktif sejak migrasi 8 untuk semua akun, tanpa menunggu sync diaktifkan: biayanya satu baris per
 * rekaman unik (bukan per penulisan) sehingga basis data tidak membengkak, dan tidak ada edit yang
 * hilang antara migrasi dan aktivasi sync. Data yang sudah ada sebelum migrasi dijangkau
 * [SyncBootstrap.markAllDirty] saat sync pertama diaktifkan.
 *
 * Hanya cakupan pribadi ([SyncScope.Personal]); proyeksi ruang bersama menyusul di Tahap 2.
 */
class SyncRecorder(private val db: RizqflowDatabase, private val clock: SyncClock = SyncClock()) {
    suspend fun changed(type: SyncRecordType, id: String) = record(type, id, deleted = false)

    suspend fun changed(type: SyncRecordType, ids: Iterable<String>) {
        for (id in ids) record(type, id, deleted = false)
    }

    suspend fun deleted(type: SyncRecordType, id: String) = record(type, id, deleted = true)

    suspend fun deleted(type: SyncRecordType, ids: Iterable<String>) {
        for (id in ids) record(type, id, deleted = true)
    }

    private suspend fun record(type: SyncRecordType, id: String, deleted: Boolean) {
        if (isSyncApplying()) return
        db.sync().markDirty(SyncScope.Personal, type, id, deleted, clock)
    }
}

/**
 * Menulis satu versi lokal: baca versi sekarang untuk lantai cap, lalu tulis ulang sebagai kotor.
 * `pushed_changed_at` dan `author` dipertahankan; `device_id` dikosongkan (null = perangkat ini).
 * Pemanggil harus sudah berada di dalam transaksi bila ingin atomik dengan penulisan data.
 */
internal suspend fun SyncDao.markDirty(
    scope: SyncScope,
    type: SyncRecordType,
    recordId: String,
    deleted: Boolean,
    clock: SyncClock,
): Long {
    val existing = find(scope.wire, type.wire, recordId)
    val stamp = clock.stamp(existing?.changedAt)
    upsert(
        SyncRecordEntity(
            scope = scope.wire,
            recordType = type.wire,
            recordId = recordId,
            changedAt = stamp,
            deleted = deleted,
            dirty = true,
            pushedChangedAt = existing?.pushedChangedAt,
            author = existing?.author,
            deviceId = null,
        ),
    )
    return stamp
}
