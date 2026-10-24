<?php

namespace App\Play;

/**
 * Memverifikasi satu token pembelian ke Play Developer API. Dipisah jadi kontrak supaya tes tidak
 * butuh jaringan (pola sama dengan GoogleIdTokenVerifier). Server tidak pernah memberi paket dari
 * data yang tidak lewat kontrak ini.
 */
interface PlayPurchaseVerifier
{
    /**
     * Pastikan verifikasi bisa dilakukan sama sekali (kredensial ada dan terbaca). Dipanggil di
     * awal klaim supaya "belum dikonfigurasi" selalu 503, tanpa memandang isi permintaan.
     *
     * @throws PlayNotConfigured
     */
    public function ensureConfigured(): void;

    /**
     * Tanyakan ke Play status token untuk produk yang diharapkan. `PlayVerification::$productId`
     * adalah produk yang dilaporkan Play; pemanggil memeriksa kesamaannya dengan $expected.
     *
     * @throws PlayNotConfigured kredensial belum ada
     * @throws PlayUnavailable Play tak terjangkau atau gagal sementara; tidak boleh memberi/mencabut apa pun
     * @throws PlayPurchaseNotFound Play tidak mengenal token ini untuk paket kita
     */
    public function verify(PlayProduct $expected, string $purchaseToken): PlayVerification;
}
