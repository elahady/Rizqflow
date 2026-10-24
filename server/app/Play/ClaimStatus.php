<?php

namespace App\Play;

/** Hasil klaim satu item pembelian, nilainya persis kontrak `POST /api/purchases/claim`. */
enum ClaimStatus: string
{
    /** Sah sekarang dan dikaitkan ke akun ini. */
    case Active = 'active';
    /** Pembayaran tertunda: tidak membuka paket apa pun. */
    case Pending = 'pending';
    /** Terverifikasi tetapi tidak berhak sekarang (kedaluwarsa, ditahan, dijeda, dikembalikan, produk tak cocok, token tak dikenal Play). */
    case Inactive = 'inactive';
    /** Token sudah diklaim akun lain. Tidak pernah membocorkan akun siapa. */
    case ClaimedByOther = 'claimed_by_other';
    case UnknownProduct = 'unknown_product';
    /** Play tak terjangkau atau gagal sementara untuk item ini: coba lagi nanti, tidak mengubah apa pun. */
    case Unverifiable = 'unverifiable';
}
