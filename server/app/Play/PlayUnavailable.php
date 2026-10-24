<?php

namespace App\Play;

/**
 * Play tidak terjangkau atau gagal sementara (jaringan, kredensial ditolak, kuota, galat 5xx,
 * jawaban yang tidak bisa dipahami). Hasilnya "unverifiable": tidak pernah memberi atau mencabut
 * paket.
 */
class PlayUnavailable extends PlayException
{
    public function __construct(string $message = 'Google Play tidak dapat dihubungi saat ini.')
    {
        parent::__construct($message);
    }
}
