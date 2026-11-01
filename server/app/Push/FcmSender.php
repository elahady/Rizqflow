<?php

namespace App\Push;

/**
 * Pengirim pesan data Firebase Cloud Messaging ke satu token perangkat (Tahap 9/13, server-sync.md
 * "Notifikasi push"). Implementasi asli: FcmHttpV1Sender; tanpa konfigurasi: NullFcmSender; tes:
 * Tests\Support\FakeFcmSender.
 *
 * Isi pesan hanya bisa berupa SyncNotificationPayload (roomId + kind), jadi tidak ada jalan mengirim
 * data lain lewat antarmuka ini. Pengirim tidak boleh mencatat token atau isi pesan ke log.
 */
interface FcmSender
{
    public function send(string $token, SyncNotificationPayload $payload): FcmSendResult;
}
