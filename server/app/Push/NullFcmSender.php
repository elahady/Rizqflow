<?php

namespace App\Push;

/** Dipakai bila FCM belum dikonfigurasi: tidak mengirim apa pun. Sync tetap berjalan normal. */
final class NullFcmSender implements FcmSender
{
    public function send(string $token, SyncNotificationPayload $payload): FcmSendResult
    {
        return FcmSendResult::NotConfigured;
    }
}
