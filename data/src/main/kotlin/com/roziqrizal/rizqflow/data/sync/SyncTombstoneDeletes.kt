package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus

/**
 * Tombstone masuk tidak bisa diterapkan karena masih ada data lokal yang merujuk rekaman itu (kunci asing RESTRICT) atau
 * ruangnya sedang dibagi. Dilempar dari penangan `apply`: transaksi penerapan batal bersih (meta tidak maju) dan mesin
 * sinkron mencatatnya sebagai `sync_issue(DEFERRED_FK_RESTRICT)` lalu mencoba lagi di akhir pull dan sync berikutnya,
 * sehingga tombstone akhirnya berlaku begitu rujukannya hilang (mis. tombstone transaksi yang menyusul di peringkat
 * penerapan lebih tinggi). Pesannya hanya jenis dan alasan, tanpa nama atau isi.
 */
class SyncDeleteBlockedException(message: String) : IllegalStateException(message)

/**
 * Aturan hapus sisi terima untuk `room`, `category`, dan `account` (sync-desain.md bagian D; keputusan pemilik #10, LWW murni).
 * Urutan menang-kalah sudah diputuskan [com.roziqrizal.rizqflow.domain.sync.SyncOrdering] sebelum fungsi ini dipanggil: tombstone yang
 * sampai di sini sudah menang atas versi lokal, jadi TIDAK ada syarat "sudah diarsipkan" seperti API hapus lokal (arsip
 * konkuren yang lebih lama sudah kalah). Yang tetap dijaga adalah kunci asing: perangkat ini tidak pernah menghapus data
 * yang masih dirujuk transaksi, tagihan, utang, aturan berulang, atau rencana DCA. Menulis lewat DAO langsung (konteks
 * applying), jadi tidak menerbitkan tombstone baru; tombstone anak (favorit, DCA, profil trader, kategori) datang dari
 * perangkat penulis sendiri dan menjadi no-op bila barisnya sudah hilang karena cascade di sini.
 *
 * Baris yang sudah tidak ada (tombstone ganda, atau sudah dihapus lewat cascade) selesai tanpa galat.
 */
internal object SyncTombstoneDeletes {
    suspend fun room(db: RizqflowDatabase, id: String) {
        db.rooms().find(id) ?: return
        if (db.sync().sharedRoom(id)?.status == SharedRoomStatus.ACTIVE.name) {
            throw SyncDeleteBlockedException("ruang masih dibagi dengan anggota keluarga")
        }
        val categoryIds = db.rooms().categoryIdsOf(id)
        if (db.rooms().roomInUse(id) ||
            categoryIds.any { db.rooms().categoryHistoryInUse(it) } ||
            db.rooms().roomCategoriesUsedByOtherDca(id)
        ) {
            throw SyncDeleteBlockedException("ruang masih dirujuk riwayat")
        }
        // Sama dengan LocalRoomRepository.deleteRoom: rencana DCA merujuk kategori dengan RESTRICT, jadi harus hilang lebih dulu.
        if (db.roles().dca(id) != null) db.roles().deleteDca(id)
        if (db.roles().trader(id) != null) db.roles().deleteTrader(id)
        val favoriteIds = db.favorites().idsByRoom(id)
        if (favoriteIds.isNotEmpty()) db.favorites().deleteAll(favoriteIds)
        db.rooms().deleteCategoriesOf(id)
        db.rooms().deleteRoom(id)
    }

    suspend fun category(db: RizqflowDatabase, id: String) {
        db.rooms().findCategory(id) ?: return
        if (db.rooms().categoryHistoryInUse(id) || db.rooms().categoryUsedByDca(id)) {
            throw SyncDeleteBlockedException("kategori masih dirujuk riwayat")
        }
        val favoriteIds = db.favorites().idsByCategory(id)
        if (favoriteIds.isNotEmpty()) db.favorites().deleteAll(favoriteIds)
        db.rooms().deleteCategory(id)
    }

    suspend fun account(db: RizqflowDatabase, id: String) {
        val entity = db.accounts().find(id) ?: return
        // Akun anggota virtual dikelola sinkron ruang bersama, bukan cakupan pribadi: tombstone pribadi tidak menyentuhnya.
        if (entity.sharedFrom != null) return
        if (db.accounts().inUse(id)) throw SyncDeleteBlockedException("akun masih dirujuk riwayat")
        val favoriteIds = db.favorites().idsByAccount(id)
        if (favoriteIds.isNotEmpty()) db.favorites().deleteAll(favoriteIds)
        db.accounts().delete(id)
    }
}
