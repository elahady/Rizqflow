<?php

namespace Tests\Support;

use App\Push\FcmSender;
use App\Push\FcmSendResult;
use App\Push\SyncNotificationPayload;

/** Pengirim FCM palsu untuk tes: mencatat setiap kiriman dan menjawab sesuai yang diatur per token. */
final class FakeFcmSender implements FcmSender
{
    /** @var list<array{token: string, data: array<string, string>}> */
    public array $sent = [];

    /** @var array<string, FcmSendResult> */
    private array $results = [];

    public function answer(string $token, FcmSendResult $result): self
    {
        $this->results[$token] = $result;

        return $this;
    }

    public function send(string $token, SyncNotificationPayload $payload): FcmSendResult
    {
        $this->sent[] = ['token' => $token, 'data' => $payload->toData()];

        return $this->results[$token] ?? FcmSendResult::Sent;
    }

    /** @return list<string> */
    public function tokens(): array
    {
        return array_column($this->sent, 'token');
    }
}
