package com.roziqrizal.rizqflow.domain.sync

import kotlinx.serialization.json.JsonObject

/** Satu rekaman kotor yang siap dibungkus amplop dan dikirim. */
data class OutgoingRecord(
    val recordId: String,
    /** Versi format `d` (bidang `v` di amplop). */
    val version: Int,
    /** `changed_at` yang dibaca saat mengumpulkan; dipakai lagi di [SentVersion] untuk pembersihan bersyarat. */
    val changedAtMillis: Long,
    val deleted: Boolean,
    /** Isi tipe-spesifik; kosong untuk tombstone. */
    val data: JsonObject,
)

/**
 * Penangan satu [SyncRecordType]: tahap berikutnya menambah satu penangan per tipe tanpa mengubah
 * mesin. Tahap 1 hanya menetapkan kontraknya (belum ada codec per tipe).
 */
interface SyncRecordHandler {
    val type: SyncRecordType

    /** Peringkat urutan penerapan hasil pull; sama dengan [SyncRecordType.applyRank]. */
    val applyRank: Int get() = type.applyRank

    /** Rekaman tipe ini yang kotor di [scope]. */
    suspend fun collectDirty(scope: SyncScope): List<OutgoingRecord>

    /**
     * Rujukan (FK) yang disebut [decoded] tetapi belum ada secara lokal di [scope] — daftar kosong
     * berarti aman diterapkan (Tahap 3 sync-desain.md bagian B "Referensi belum tiba"). Mesin sinkron
     * memanggil ini SEBELUM [apply], DI LUAR transaksi [apply]: bila tidak kosong, [apply] TIDAK
     * dipanggil sama sekali untuk versi ini (meta rekaman tidak ikut maju — tetap seperti semula) dan
     * mesin mencatat `sync_issue(DEFERRED_MISSING_REF)` lalu mencoba lagi di akhir sync dan sync
     * berikutnya. Implementasi yang tidak punya rujukan ke tipe lain (mis. [SyncRecordType.ROOM])
     * selalu mengembalikan daftar kosong.
     */
    suspend fun missingReferences(scope: SyncScope, decoded: DecodedRecord): List<String>

    /**
     * Menerapkan satu rekaman yang sudah lolos dekripsi; dipanggil di konteks [SyncApplying] dari dalam
     * [SyncStore.applyIncoming] (satu transaksi dengan pembaruan meta).
     *
     * ATURAN: **jangan menelan galat repositori.** Repositori menulis lewat `withTransaction` bersarang, dan galat di
     * dalamnya menandai transaksi LUAR gagal walau galatnya ditangkap (`try { repo.save(x) } catch (_: Exception) {}`):
     * seluruh transaksi (data dan meta) dibatalkan diam-diam padahal `applyIncoming` tampak berhasil. Pilih salah satu:
     * 1. biarkan galat merambat (transaksi batal bersih, pemanggil melaporkannya); atau
     * 2. PRA-CEK sebelum menulis apa pun (rujukan akun, kategori, ruang ada; FK terpenuhi; versi dikenal), lalu bila
     *    tidak terpenuhi kembali tanpa menulis dan catat `SyncIssue` DEFERRED_MISSING_REF atau DEFERRED_FK_RESTRICT.
     *
     * Room tidak punya SAVEPOINT bersarang di jalur ini, jadi "coba lalu pulihkan" bukan pilihan. Implementasi Room
     * mendeteksi pembatalan diam-diam sebagai [SyncApplyAbortedException] bila dipanggil di luar transaksi lain,
     * tetapi jangan mengandalkannya.
     */
    suspend fun apply(scope: SyncScope, decoded: DecodedRecord)
}
