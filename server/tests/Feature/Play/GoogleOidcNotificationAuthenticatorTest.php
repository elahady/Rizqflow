<?php

namespace Tests\Feature\Play;

use App\Play\GoogleOidcNotificationAuthenticator;
use Firebase\JWT\JWT;
use Google\Auth\Cache\MemoryCacheItemPool;
use Google\Client as GoogleClient;
use GuzzleHttp\Client as GuzzleClient;
use GuzzleHttp\Exception\ConnectException;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\Psr7\Request;
use GuzzleHttp\Psr7\Response;
use Illuminate\Support\Facades\Log;
use Tests\TestCase;

/**
 * Autentikator RTDN di atas klien Google ASLI dengan JWKS palsu: kunci RSA dibuat di tes, JWT
 * ditandatangani sungguhan, dan penolakan (audience, penerbit, masa berlaku, tanda tangan, email)
 * dibuktikan lewat jalur kode Google\AccessToken\Verify yang dipakai produksi. Yang TIDAK terbukti
 * di sini: token sungguhan dari Pub/Sub dan JWKS sungguhan milik Google.
 */
class GoogleOidcNotificationAuthenticatorTest extends TestCase
{
    private const AUDIENCE = 'https://rizqflow.roziqrizal.com/api/play/notifications';

    private const SERVICE_ACCOUNT = 'pubsub-push@rizqflow.iam.gserviceaccount.com';

    private const KID = 'kunci-uji-1';

    /** @var array{private: string, jwks: array<string, mixed>} */
    private array $signer;

    /** @var array{private: string, jwks: array<string, mixed>} */
    private array $stranger;

    protected function setUp(): void
    {
        parent::setUp();

        $this->signer = $this->makeKey();
        $this->stranger = $this->makeKey();
    }

    /** @return array{private: string, jwks: array<string, mixed>} */
    private function makeKey(): array
    {
        // PHP di Windows (xampp) tidak menemukan openssl.cnf sendiri; coba bawaan dulu, lalu lokasi umum.
        $base = ['private_key_type' => OPENSSL_KEYTYPE_RSA, 'private_key_bits' => 2048];
        $attempts = [$base];
        foreach ([getenv('OPENSSL_CONF') ?: null, dirname(PHP_BINARY).'/extras/ssl/openssl.cnf', '/etc/ssl/openssl.cnf', '/usr/lib/ssl/openssl.cnf'] as $path) {
            if ($path !== null && is_file($path)) {
                $attempts[] = $base + ['config' => $path];
            }
        }

        $resource = false;
        foreach ($attempts as $options) {
            $resource = @openssl_pkey_new($options);
            if ($resource !== false) {
                break;
            }
        }
        if ($resource === false) {
            $this->markTestSkipped('openssl tidak bisa membuat kunci RSA di lingkungan ini.');
        }
        openssl_pkey_export($resource, $pem, null, $options);
        $details = openssl_pkey_get_details($resource);
        $b64 = fn (string $raw) => rtrim(strtr(base64_encode($raw), '+/', '-_'), '=');

        return [
            'private' => $pem,
            'jwks' => ['keys' => [[
                'kty' => 'RSA', 'alg' => 'RS256', 'use' => 'sig', 'kid' => self::KID,
                'n' => $b64($details['rsa']['n']), 'e' => $b64($details['rsa']['e']),
            ]]],
        ];
    }

    /** @param array<string, mixed> $overrides */
    private function jwt(array $overrides = [], ?string $privateKey = null): string
    {
        $payload = $overrides + [
            'iss' => 'https://accounts.google.com',
            'aud' => self::AUDIENCE,
            'sub' => '1234567890',
            'email' => self::SERVICE_ACCOUNT,
            'email_verified' => true,
            'iat' => time() - 10,
            'exp' => time() + 3600,
        ];
        $payload = array_filter($payload, fn ($value) => $value !== null);

        return JWT::encode($payload, $privateKey ?? $this->signer['private'], 'RS256', self::KID);
    }

    /** Autentikator dengan klien Google asli yang JWKS-nya datang dari HTTP palsu (atau galat jaringan). */
    private function authenticator(?string $email = self::SERVICE_ACCOUNT, string $audience = self::AUDIENCE, ?\Throwable $networkError = null): GoogleOidcNotificationAuthenticator
    {
        $jwks = $this->signer['jwks'];

        return new GoogleOidcNotificationAuthenticator($audience, $email, function (string $aud) use ($jwks, $networkError) {
            $queue = $networkError !== null
                ? [$networkError]
                : [new Response(200, ['Content-Type' => 'application/json'], json_encode($jwks))];

            $client = new GoogleClient(['client_id' => $aud]);
            $client->setHttpClient(new GuzzleClient(['handler' => new MockHandler($queue)]));
            $client->setCache(new MemoryCacheItemPool);

            return $client;
        });
    }

    public function test_valid_token_for_our_audience_and_service_account_is_authentic(): void
    {
        $this->assertTrue($this->authenticator()->isAuthentic($this->jwt()));
    }

    public function test_token_for_another_audience_is_rejected(): void
    {
        $this->assertFalse($this->authenticator()->isAuthentic($this->jwt(['aud' => 'https://orang-lain.example/hook'])));
    }

    public function test_token_without_an_audience_claim_is_rejected(): void
    {
        // Verify bawaan Google melewatkan pemeriksaan audience bila klaim aud tak ada; kita wajib menolaknya.
        $this->assertFalse($this->authenticator()->isAuthentic($this->jwt(['aud' => null])));
    }

    public function test_audience_given_as_a_list_is_rejected(): void
    {
        // Token Google selalu memakai aud berupa string; daftar (bahkan yang memuat audience kita) ditolak.
        $this->assertFalse($this->authenticator()->isAuthentic($this->jwt(['aud' => ['x', self::AUDIENCE]])));
        $this->assertFalse($this->authenticator()->isAuthentic($this->jwt(['aud' => ['x', 'y']])));
    }

    public function test_expired_token_is_rejected(): void
    {
        $this->assertFalse($this->authenticator()->isAuthentic($this->jwt(['iat' => time() - 7200, 'exp' => time() - 3600])));
    }

    public function test_token_from_another_issuer_is_rejected(): void
    {
        $this->assertFalse($this->authenticator()->isAuthentic($this->jwt(['iss' => 'https://penerbit-palsu.example'])));
    }

    public function test_token_signed_with_a_different_key_is_rejected(): void
    {
        $this->assertFalse($this->authenticator()->isAuthentic($this->jwt(privateKey: $this->stranger['private'])));
    }

    public function test_unsigned_alg_none_token_is_rejected(): void
    {
        $b64 = fn (array $data) => rtrim(strtr(base64_encode(json_encode($data)), '+/', '-_'), '=');
        $token = $b64(['alg' => 'none', 'typ' => 'JWT']).'.'.$b64(['iss' => 'https://accounts.google.com', 'aud' => self::AUDIENCE, 'exp' => time() + 3600]).'.';

        $this->assertFalse($this->authenticator()->isAuthentic($token));
    }

    public function test_service_account_email_must_match_and_be_verified_when_configured(): void
    {
        $auth = $this->authenticator();

        $this->assertFalse($auth->isAuthentic($this->jwt(['email' => 'penyerang@example.com'])));
        $this->assertFalse($auth->isAuthentic($this->jwt(['email_verified' => false])));
        $this->assertFalse($auth->isAuthentic($this->jwt(['email_verified' => null])));
        $this->assertFalse($auth->isAuthentic($this->jwt(['email' => null])));
        $this->assertTrue($auth->isAuthentic($this->jwt(['email' => strtoupper(self::SERVICE_ACCOUNT)])));
    }

    public function test_email_is_not_checked_when_no_service_account_is_configured(): void
    {
        $auth = $this->authenticator(email: null);

        $this->assertTrue($auth->isAuthentic($this->jwt(['email' => null, 'email_verified' => null])));
    }

    public function test_garbage_empty_and_oversized_tokens_are_rejected(): void
    {
        $auth = $this->authenticator();

        $this->assertFalse($auth->isAuthentic('bukan-jwt'));
        $this->assertFalse($auth->isAuthentic(''));
        $this->assertFalse($auth->isAuthentic(str_repeat('a', 9000)));
    }

    public function test_unreachable_google_keys_reject_without_leaking_the_token(): void
    {
        $token = $this->jwt();
        $logged = [];
        Log::shouldReceive('warning')->andReturnUsing(function (...$args) use (&$logged) {
            $logged[] = json_encode($args);
        });

        $auth = $this->authenticator(networkError: new ConnectException('gagal', new Request('GET', 'https://www.googleapis.com/oauth2/v3/certs')));

        $this->assertFalse($auth->isAuthentic($token));
        $this->assertNotEmpty($logged);
        $this->assertStringNotContainsString($token, implode("\n", $logged));
    }

    public function test_blank_audience_means_not_configured_and_authenticates_nothing(): void
    {
        $auth = $this->authenticator(audience: '');

        $this->assertFalse($auth->isConfigured());
        $this->assertFalse($auth->isAuthentic($this->jwt(['aud' => ''])));
        $this->assertTrue($this->authenticator()->isConfigured());
    }
}
