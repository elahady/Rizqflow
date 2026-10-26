<?php

/*
|--------------------------------------------------------------------------
| Sinkronisasi: kuota, retensi, dan perangkat aktif (Tahap 9, sync-desain.md bagian J #6 dan #12)
|--------------------------------------------------------------------------
|
| Angka di sini disetujui pemilik 2026-10-26. Ubah hanya lewat keputusan baru di sync-desain.md.
|
*/

return [

    'quota' => [
        // Batas per akun (pemilik sebagai penulis baris): jumlah baris log dan total byte payload
        // terenkripsi. 150 MB = 150 * 1024 * 1024 byte. Melewati batas: push ditolak dengan 422
        // `quota_exceeded` (pesan lembut), tidak ada data yang dihapus otomatis.
        'max_rows' => 250000,
        'max_bytes' => 150 * 1024 * 1024,
    ],

    'retention' => [
        // Versi yang kalah (ada versi lebih baru untuk rekaman yang sama) boleh dihapus fisik bila
        // umur barisnya (created_at, jam server) >= sekian hari.
        'version_days' => 90,

        // Tombstone (baris hapus) boleh dihapus fisik bila umurnya >= sekian hari DAN sudah dilewati
        // (kursor > id-nya) oleh semua perangkat aktif, dan tidak ada baris lain untuk rekaman itu.
        'tombstone_days' => 400,

        // Perangkat "aktif" = pernah pull dalam sekian hari terakhir (sync_devices.last_seen_at).
        // Perangkat yang lebih lama tidak lagi menahan kompaksi; pull berikutnya dari kursor yang
        // sudah terlewati kompaksi dijawab 410 cursor_expired, dan perangkat itu pull ulang dari 0.
        'active_device_days' => 180,
    ],

];
