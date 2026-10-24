<?php

namespace Tests\Feature\Play;

use App\Play\GooglePlayClientFactory;
use App\Play\PlayNotConfigured;
use Google\Service\AndroidPublisher;
use Tests\TestCase;

/** Pembacaan PLAY_SERVICE_ACCOUNT_JSON: jalur berkas, JSON mentah, atau base64. Kunci di bawah palsu (bukan akun layanan sungguhan). */
class GooglePlayClientFactoryTest extends TestCase
{
    private const FAKE_KEY = "-----BEGIN PRIVATE KEY-----\nPALSU-UNTUK-TES\n-----END PRIVATE KEY-----\n";

    private function accountJson(): string
    {
        return json_encode([
            'type' => 'service_account',
            'project_id' => 'proyek-uji',
            'client_email' => 'uji@proyek-uji.iam.gserviceaccount.com',
            'private_key' => self::FAKE_KEY,
            'client_id' => '123',
        ]);
    }

    public function test_reads_raw_json(): void
    {
        $credentials = GooglePlayClientFactory::credentials($this->accountJson());

        $this->assertSame('uji@proyek-uji.iam.gserviceaccount.com', $credentials['client_email']);
        $this->assertSame(self::FAKE_KEY, $credentials['private_key']);
    }

    public function test_reads_base64_encoded_json(): void
    {
        $credentials = GooglePlayClientFactory::credentials(base64_encode($this->accountJson()));

        $this->assertSame('uji@proyek-uji.iam.gserviceaccount.com', $credentials['client_email']);
    }

    public function test_reads_a_file_path(): void
    {
        $path = tempnam(sys_get_temp_dir(), 'play-sa-');
        file_put_contents($path, $this->accountJson());

        try {
            $credentials = GooglePlayClientFactory::credentials($path);
        } finally {
            unlink($path);
        }

        $this->assertSame('uji@proyek-uji.iam.gserviceaccount.com', $credentials['client_email']);
    }

    public function test_blank_or_unreadable_values_are_not_configured(): void
    {
        $bad = [
            'null' => null,
            'kosong' => '',
            'spasi' => '   ',
            'jalur yang tidak ada' => '/tidak/ada/berkas-ini.json',
            'base64 sampah' => '!!!bukan-base64!!!',
            'JSON rusak' => '{rusak',
            'tanpa private_key' => json_encode(['client_email' => 'a@b.c']),
            'tanpa client_email' => json_encode(['private_key' => self::FAKE_KEY]),
            'field bukan string' => json_encode(['client_email' => ['a'], 'private_key' => 1]),
        ];

        foreach ($bad as $name => $value) {
            try {
                GooglePlayClientFactory::credentials($value);
                $this->fail("{$name} harus PlayNotConfigured");
            } catch (PlayNotConfigured) {
                $this->addToAssertionCount(1);
            }
        }
    }

    public function test_error_message_never_contains_the_configured_value(): void
    {
        $secret = 'KUNCI-RAHASIA-JANGAN-BOCOR';

        try {
            GooglePlayClientFactory::credentials(json_encode(['client_email' => 'a@b.c', 'private_key' => '', 'x' => $secret]));
            $this->fail('Harus melempar');
        } catch (PlayNotConfigured $e) {
            $this->assertStringNotContainsString($secret, $e->getMessage());
        }
    }

    public function test_builds_an_android_publisher_service_without_any_network_call(): void
    {
        $service = GooglePlayClientFactory::make($this->accountJson());

        $this->assertInstanceOf(AndroidPublisher::class, $service);
        $this->assertNotNull($service->purchases_subscriptionsv2);
        $this->assertNotNull($service->purchases_productsv2);
    }

    public function test_client_asks_for_the_androidpublisher_scope_as_a_service_account(): void
    {
        $client = GooglePlayClientFactory::make($this->accountJson())->getClient();

        // Tanpa scope ini token OAuth ditolak Play (401/403) dan semua klaim diam-diam menjadi unverifiable.
        $this->assertContains(AndroidPublisher::ANDROIDPUBLISHER, $client->getScopes());
        $this->assertTrue($client->isUsingApplicationDefaultCredentials(), 'kredensial akun layanan terpasang');
    }

    public function test_http_client_returns_error_responses_instead_of_throwing_and_has_timeouts(): void
    {
        $http = GooglePlayClientFactory::make($this->accountJson())->getClient()->getHttpClient();

        // http_errors=false: jawaban galat ditangani REST::execute menjadi GoogleServiceException berkode HTTP,
        // yang dipetakan verifier (400/404/410 tak dikenal, 5xx tak terjangkau). Bila true, pemetaan itu tak jalan.
        $this->assertFalse($http->getConfig('http_errors'));
        $this->assertSame(10, $http->getConfig('timeout'));
        $this->assertSame(5, $http->getConfig('connect_timeout'));
    }

    public function test_make_without_configuration_is_not_configured(): void
    {
        $this->expectException(PlayNotConfigured::class);

        GooglePlayClientFactory::make(null);
    }
}
