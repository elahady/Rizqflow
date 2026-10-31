<?php

namespace App\Push;

use App\Play\GooglePlayClientFactory;
use App\Play\PlayNotConfigured;
use Closure;
use Google\Auth\Credentials\ServiceAccountCredentials;
use Illuminate\Http\Client\ConnectionException;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Log;
use Throwable;

/**
 * Pengirim FCM asli lewat HTTP v1 (`POST https://fcm.googleapis.com/v1/projects/{id}/messages:send`).
 * Pesan DATA saja (tanpa blok `notification`): aplikasi menampilkan teks tetap sendiri
 * (ReminderNotifier.showSync), jadi server tidak pernah mengirim teks yang terlihat pengguna.
 *
 * Kredensial: FCM_PROJECT_ID dan FCM_SERVICE_ACCOUNT_JSON (jalur berkas, JSON mentah, atau base64;
 * hanya di .env.production, tidak pernah di-commit). Token akses OAuth (scope firebase.messaging)
 * diambil sekali per instans. Token perangkat, isi pesan, dan isi jawaban FCM tidak pernah dicatat;
 * log hanya kode status.
 *
 * BELUM PERNAH diuji terhadap FCM sungguhan: hanya dengan HTTP palsu (Http::fake).
 */
final class FcmHttpV1Sender implements FcmSender
{
    public const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

    private const TIMEOUT = 10;

    private const CONNECT_TIMEOUT = 5;

    private ?string $accessToken = null;

    /** @param  Closure(): string  $accessTokenProvider */
    public function __construct(private readonly string $projectId, private readonly Closure $accessTokenProvider) {}

    /**
     * Rakit dari konfigurasi; NullFcmSender bila project id atau kunci akun layanan kosong/tidak terbaca.
     * Penguraian kunci memakai ulang GooglePlayClientFactory::credentials (format sama dengan
     * PLAY_SERVICE_ACCOUNT_JSON); pesan galatnya tidak dipakai di sini.
     */
    public static function fromConfig(?string $projectId, ?string $serviceAccountJson): FcmSender
    {
        $projectId = trim((string) $projectId);
        if ($projectId === '') {
            return new NullFcmSender;
        }

        try {
            $credentials = GooglePlayClientFactory::credentials($serviceAccountJson);
        } catch (PlayNotConfigured) {
            return new NullFcmSender;
        }

        return new self($projectId, function () use ($credentials): string {
            $token = (new ServiceAccountCredentials(self::SCOPE, $credentials))->fetchAuthToken();

            return (string) ($token['access_token'] ?? '');
        });
    }

    public function send(string $token, SyncNotificationPayload $payload): FcmSendResult
    {
        try {
            $this->accessToken ??= ($this->accessTokenProvider)();
        } catch (Throwable $e) {
            Log::warning('fcm: token akses OAuth gagal diambil', ['exception' => $e::class]);

            return FcmSendResult::Failed;
        }
        if ($this->accessToken === '') {
            return FcmSendResult::Failed;
        }

        try {
            $response = Http::withToken($this->accessToken)
                ->timeout(self::TIMEOUT)
                ->connectTimeout(self::CONNECT_TIMEOUT)
                ->acceptJson()
                ->post('https://fcm.googleapis.com/v1/projects/'.rawurlencode($this->projectId).'/messages:send', [
                    'message' => [
                        'token' => $token,
                        'data' => $payload->toData(),
                        'android' => [
                            'priority' => 'high',
                            // Banyak catatan di satu ruang cukup satu kabar di perangkat yang offline.
                            'collapse_key' => 'room-'.substr(hash('sha256', $payload->roomId), 0, 16),
                        ],
                    ],
                ]);
        } catch (ConnectionException) {
            Log::warning('fcm: tidak terjangkau');

            return FcmSendResult::Failed;
        }

        if ($response->successful()) {
            return FcmSendResult::Sent;
        }

        // UNREGISTERED (404) = token tidak berlaku lagi. INVALID_ARGUMENT (400) bisa juga karena pesan,
        // jadi hanya dianggap token rusak bila FCM menyebut field token.
        $status = $response->status();
        $errorCode = $this->fcmErrorCode($response->json());
        if ($status === 404 || $errorCode === 'UNREGISTERED') {
            return FcmSendResult::InvalidToken;
        }
        if ($status === 400 && $errorCode === 'INVALID_ARGUMENT' && str_contains((string) $response->json('error.message'), 'token')) {
            return FcmSendResult::InvalidToken;
        }
        if ($status === 401 || $status === 403) {
            // Token akses mungkin kedaluwarsa; ambil ulang pada kiriman berikutnya.
            $this->accessToken = null;
        }

        Log::warning('fcm: kiriman ditolak', ['status' => $status, 'fcm_error' => $errorCode]);

        return FcmSendResult::Failed;
    }

    private function fcmErrorCode(mixed $json): ?string
    {
        if (! is_array($json)) {
            return null;
        }
        foreach ($json['error']['details'] ?? [] as $detail) {
            if (is_array($detail) && is_string($detail['errorCode'] ?? null)) {
                return $detail['errorCode'];
            }
        }

        return is_string($json['error']['status'] ?? null) ? $json['error']['status'] : null;
    }
}
