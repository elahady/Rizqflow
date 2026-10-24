<?php

namespace App\Play;

/**
 * Play menjawab 400 atau 404 untuk token ini: tidak dikenal untuk paket kita. Saat klaim itu
 * berarti "tidak berhak" tanpa mencatat apa pun; saat memverifikasi ulang baris yang sudah ada,
 * jawaban ini ambigu (bisa saja salah konfigurasi) sehingga diperlakukan seperti tak terverifikasi.
 */
class PlayPurchaseNotFound extends PlayException
{
    public function __construct()
    {
        parent::__construct('Google Play tidak mengenal token pembelian ini.');
    }
}
