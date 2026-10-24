<?php

namespace App\Play;

/** Kredensial akun layanan Play belum diisi (atau tidak terbaca) di server: dipetakan ke 503. */
class PlayNotConfigured extends PlayException
{
    public function __construct(string $message = 'Verifikasi pembelian Google Play belum dikonfigurasi di server.')
    {
        parent::__construct($message);
    }
}
