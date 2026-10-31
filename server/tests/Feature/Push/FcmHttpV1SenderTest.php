<?php

namespace Tests\Feature\Push;

use App\Push\FcmHttpV1Sender;
use App\Push\FcmSendResult;
use App\Push\NullFcmSender;
use App\Push\SyncNotificationPayload;
use Illuminate\Http\Client\Request;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Log;
use InvalidArgumentException;
use Tests\TestCase;

/**
 * Pengirim FCM HTTP v1 dengan HTTP palsu (Http::fake). BUKAN bukti terhadap FCM sungguhan: bentuk
 * jawaban galat disusun dari dokumentasi FCM v1.
 */
class FcmHttpV1SenderTest extends TestCase
{
    private function sender(): FcmHttpV1Sender
    {
        return new FcmHttpV1Sender('rizqflow-uji', fn () => 'akses-oauth-palsu');
    }

    public function test_mengirim_pesan_data_saja_dengan_room_id_dan_kind(): void
    {
        Http::fake(['fcm.googleapis.com/*' => Http::response(['name' => 'projects/rizqflow-uji/messages/1'])]);

        $hasil = $this->sender()->send('fcm-token-perangkat', SyncNotificationPayload::newRecord('ruang-1'));

        $this->assertSame(FcmSendResult::Sent, $hasil);
        Http::assertSent(function (Request $request) {
            $message = $request->data()['message'];

            return $request->url() === 'https://fcm.googleapis.com/v1/projects/rizqflow-uji/messages:send'
                && $request->method() === 'POST'
                && $request->hasHeader('Authorization', 'Bearer akses-oauth-palsu')
                && $message['token'] === 'fcm-token-perangkat'
                && $message['data'] === ['roomId' => 'ruang-1', 'kind' => 'NEW_RECORD']
                && ! array_key_exists('notification', $message)
                && array_keys($message) === ['token', 'data', 'android'];
        });
    }

    public function test_unregistered_berarti_token_tidak_berlaku(): void
    {
        Http::fake(['fcm.googleapis.com/*' => Http::response([
            'error' => ['code' => 404, 'status' => 'NOT_FOUND', 'details' => [['@type' => 'type.googleapis.com/google.firebase.fcm.v1.FcmError', 'errorCode' => 'UNREGISTERED']]],
        ], 404)]);

        $this->assertSame(FcmSendResult::InvalidToken, $this->sender()->send('t', SyncNotificationPayload::newRecord('r')));
    }

    public function test_galat_server_bukan_alasan_membuang_token_dan_tidak_mencatat_token(): void
    {
        Http::fake(['fcm.googleapis.com/*' => Http::response(['error' => ['code' => 503, 'status' => 'UNAVAILABLE']], 503)]);
        $log = [];
        Log::listen(function ($pesan) use (&$log) {
            $log[] = $pesan->message.' '.json_encode($pesan->context);
        });

        $hasil = $this->sender()->send('fcm-token-rahasia', SyncNotificationPayload::newRecord('ruang-rahasia'));

        $this->assertSame(FcmSendResult::Failed, $hasil);
        $this->assertNotEmpty($log);
        foreach ($log as $baris) {
            $this->assertStringNotContainsString('fcm-token-rahasia', $baris);
            $this->assertStringNotContainsString('akses-oauth-palsu', $baris);
            $this->assertStringNotContainsString('ruang-rahasia', $baris);
        }
    }

    public function test_token_akses_gagal_diambil_berarti_gagal_tanpa_memanggil_fcm(): void
    {
        Http::fake();
        $sender = new FcmHttpV1Sender('rizqflow-uji', fn () => throw new \RuntimeException('kunci rusak'));

        $this->assertSame(FcmSendResult::Failed, $sender->send('t', SyncNotificationPayload::newRecord('r')));
        Http::assertNothingSent();
    }

    public function test_tanpa_konfigurasi_menjadi_null_sender(): void
    {
        $this->assertInstanceOf(NullFcmSender::class, FcmHttpV1Sender::fromConfig(null, null));
        $this->assertInstanceOf(NullFcmSender::class, FcmHttpV1Sender::fromConfig('rizqflow-uji', ''));
        $this->assertInstanceOf(NullFcmSender::class, FcmHttpV1Sender::fromConfig('', '{"client_email":"a@b","private_key":"k"}'));
        $this->assertInstanceOf(NullFcmSender::class, FcmHttpV1Sender::fromConfig('rizqflow-uji', 'bukan-json'));
        $this->assertInstanceOf(FcmHttpV1Sender::class, FcmHttpV1Sender::fromConfig('rizqflow-uji', '{"client_email":"a@b","private_key":"k"}'));
    }

    public function test_payload_hanya_menerima_kind_yang_dikenal_dan_room_tidak_kosong(): void
    {
        $this->assertSame(['roomId' => 'r', 'kind' => 'MEMBER_REMOVED'], SyncNotificationPayload::of('r', 'MEMBER_REMOVED')->toData());

        $this->expectException(InvalidArgumentException::class);
        SyncNotificationPayload::of('r', 'NOMINAL_150000');
    }

    public function test_room_kosong_ditolak(): void
    {
        $this->expectException(InvalidArgumentException::class);
        SyncNotificationPayload::newRecord('  ');
    }
}
