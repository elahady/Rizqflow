package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.SharedRoomInfo
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncStore

/**
 * Menandai ruang sebagai ruang bersama ACTIVE (Tahap 9, roadmap "Sync berjalan otomatis"). Dipanggil setelah
 * keanggotaan di server berhasil didaftarkan, di dua tempat: penerima undangan (`joinSharedRoom`, peran MEMBER) dan
 * pemilik yang membuka layar Ruang Keluarga (peran OWNER, bila belum ada baris `shared_room` untuk ruang itu).
 *
 * Tanpa penandaan ini, [SharedRoomAutoSync] tidak pernah menganggap ruang sebagai ruang bersama dan kedua pemicu
 * otomatis tidak berjalan di aplikasi nyata.
 *
 * Aman dipanggil berulang (idempoten): peran dan waktu bergabung yang sudah tersimpan dipertahankan, hanya status
 * yang diset ke ACTIVE. Ini juga memulihkan ruang yang sebelumnya berstatus REMOVED/LEFT ketika server kembali
 * menerima pendaftaran anggota.
 */
class SharedRoomActivation(private val syncStore: SyncStore) {

    suspend fun markActive(roomId: RoomId, role: SharedRoomRole, nowMillis: Long = System.currentTimeMillis()) {
        val existing = syncStore.sharedRoom(roomId)
        syncStore.saveSharedRoom(
            SharedRoomInfo(
                roomId = roomId,
                role = existing?.role ?: role,
                status = SharedRoomStatus.ACTIVE,
                joinedAtMillis = existing?.joinedAtMillis ?: nowMillis,
            ),
        )
        // Riwayat pengeluaran sebelum ruang dibagi ikut terkirim (keputusan #4): ditandai kotor di cakupan ruang. Jalur
        // ini (anggota baru lewat undangan) tidak punya pilihan "mulai hari ini" dengan sengaja: ruangnya baru dibuat
        // di perangkat ini saat bergabung, jadi tidak ada riwayat lokal untuk ruang itu sama sekali — dialognya hanya
        // relevan di jalur pemilik, lihat [applyServerRole]. Selalu dijalankan, bukan hanya saat peralihan status:
        // idempoten, dan menutup celah bila aplikasi mati di antara penyimpanan status dan penandaan.
        syncStore.markSharedRoomHistoryDirty(roomId)
    }

    /** Status ruang bersama di perangkat ini; null bila ruang belum pernah ditandai (bukan ruang bersama). */
    suspend fun statusOf(roomId: RoomId): SharedRoomStatus? = syncStore.sharedRoom(roomId)?.status

    /**
     * Menandai ruang LEFT setelah server mencabut keanggotaan perangkat ini (anggota keluar sendiri, Tahap 9). Hanya
     * mengubah status: peran dan waktu bergabung dipertahankan, dan TIDAK ada transaksi, kategori, atau baris sync
     * yang dihapus (data ruang tetap ada di perangkat ini). Yang berhenti: sinkron otomatis, sinkron ulang penuh, dan
     * riwayat sinkron hanya menyentuh ruang ACTIVE. Bergabung lagi lewat undangan memanggil [markActive] dan memulihkan
     * ACTIVE. Tanpa baris `shared_room` sebelumnya (tidak seharusnya terjadi) tidak ada yang dibuat. Idempoten.
     */
    suspend fun markLeft(roomId: RoomId) {
        val existing = syncStore.sharedRoom(roomId) ?: return
        if (existing.status == SharedRoomStatus.LEFT) return
        syncStore.saveSharedRoom(existing.copy(status = SharedRoomStatus.LEFT))
    }

    /**
     * Menandai ruang REMOVED setelah server menjawab `not_room_member` SELAMA SINKRON (Tahap 9 lanjutan, kontrak
     * klien server-sync.md butir 5) — bukan lewat tombol "Keluar" eksplisit ([markLeft]), tetapi ditemukan ketika
     * perangkat ini ternyata sudah bukan anggota (dikeluarkan pemilik di perangkat lain, atau tidak pernah bergabung
     * dengan sah). Berperilaku sama dengan [markLeft] (hanya ubah status, data lokal tidak disentuh, idempoten),
     * dipisah namanya saja supaya "Riwayat sinkron"/diagnostik bisa membedakan nanti kalau perlu.
     */
    suspend fun markRemoved(roomId: RoomId) {
        val existing = syncStore.sharedRoom(roomId) ?: return
        if (existing.status == SharedRoomStatus.REMOVED) return
        syncStore.saveSharedRoom(existing.copy(status = SharedRoomStatus.REMOVED))
    }

    /**
     * Menandai ruang ACTIVE dengan peran DARI SERVER ([role] dari bendera `owner` di `GET /rooms/{roomId}/members`):
     * berbeda dari [markActive], peran yang sudah tersimpan DITIMPA bila berbeda, karena server satu-satunya sumber
     * kebenaran siapa pemilik ruang (keputusan pemilik #1). Menutup celah lama ketika siapa pun yang membuka layar
     * Ruang Keluarga tercatat OWNER secara lokal. Waktu bergabung yang sudah tersimpan dipertahankan.
     *
     * [historyStartMillis] (keputusan pemilik #4, sync-desain.md bagian J): hanya relevan untuk jalur PEMILIK yang
     * mengaktifkan ruang pertama kali (pemilik satu-satunya yang mungkin punya riwayat lokal sebelum ruang dibagi —
     * jalur anggota baru lewat [markActive] tidak punya riwayat apa pun untuk ruang itu). Null (bawaan) membagikan
     * SELURUH riwayat ruang, sama seperti perilaku sebelum keputusan ini ada. Diisi tanggal ("mulai hari ini"): hanya
     * pengeluaran dengan tanggal transaksi >= nilai itu ikut ditandai kotor. UI (RuangKeluargaScreen) yang menampilkan
     * dialog konfirmasi dan menentukan nilainya; fungsi ini sendiri tidak pernah menolak atau menunda aktivasi.
     */
    suspend fun applyServerRole(roomId: RoomId, role: SharedRoomRole, historyStartMillis: Long? = null, nowMillis: Long = System.currentTimeMillis()) {
        val existing = syncStore.sharedRoom(roomId)
        if (existing != null && existing.role == role && existing.status == SharedRoomStatus.ACTIVE) return
        syncStore.saveSharedRoom(
            SharedRoomInfo(
                roomId = roomId,
                role = role,
                status = SharedRoomStatus.ACTIVE,
                joinedAtMillis = existing?.joinedAtMillis ?: nowMillis,
            ),
        )
        syncStore.markSharedRoomHistoryDirty(roomId, historyStartMillis)
    }
}
