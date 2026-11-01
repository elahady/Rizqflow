package com.roziqrizal.rizqflow.data.db

import com.roziqrizal.rizqflow.domain.sync.noteSyncApplyFailure
import androidx.room.withTransaction as roomWithTransaction

/**
 * `withTransaction` Room yang mencatat kegagalannya untuk penerapan sinkron (Tahap 2 sync-desain.md, utang
 * `SyncApplyAbortedException`). Semua repositori dan penyimpanan sinkron memakai ini, BUKAN `androidx.room.withTransaction`
 * langsung: nama dan bentuknya sama, jadi cukup mengganti import.
 *
 * Mengapa perlu: transaksi Room yang bersarang di dalam transaksi lain menandai transaksi LUAR gagal bila berakhir
 * tanpa sukses, walau galatnya ditangkap pemanggil. Kalau penangan `applyIncoming` menelan galat repositori, seluruh
 * penerapan (data dan meta) dibatalkan diam-diam sementara pemanggilnya merasa berhasil. Pemeriksaan pasca-komit di
 * `RoomSyncStore` hanya mungkin bila `applyIncoming` tidak dipanggil di dalam transaksi lain; dengan pencatatan di sini
 * (hanya berarti di konteks `applying`, di luar itu satu pemeriksaan konteks tanpa efek), galat yang ditelan terdeteksi
 * di kedua keadaan.
 *
 * Batas: hanya transaksi yang dibuka lewat fungsi ini. Pemanggilan `db.runInTransaction`, `openHelper.writableDatabase`,
 * atau `androidx.room.withTransaction` langsung tidak tercatat (pemeriksaan pasca-komit tetap berlaku untuk yang
 * terakhir bila tidak bersarang). Kegagalan satu pernyataan DAO (mis. pelanggaran kunci asing) yang ditelan tanpa
 * transaksi bersarang tidak menandai transaksi luar gagal di SQLite dan memang tidak dibatalkan.
 */
suspend fun <R> RizqflowDatabase.withTransaction(block: suspend () -> R): R =
    try {
        roomWithTransaction(block)
    } catch (e: Throwable) {
        noteSyncApplyFailure(e)
        throw e
    }
