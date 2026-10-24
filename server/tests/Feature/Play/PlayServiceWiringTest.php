<?php

namespace Tests\Feature\Play;

use App\Play\GoogleOidcNotificationAuthenticator;
use App\Play\GooglePlayPurchaseVerifier;
use App\Play\PlayNotConfigured;
use App\Play\PlayNotificationAuthenticator;
use App\Play\PlayPurchaseVerifier;
use ReflectionProperty;
use Tests\TestCase;

/**
 * Penyambungan konfigurasi `services.play.*` ke implementasi nyata lewat kontainer (AppServiceProvider).
 * Tes lain memakai verifier dan autentikator palsu atau membangun kelas Google langsung, jadi salah
 * ketik kunci konfigurasi di sini tidak akan terlihat di sana. Kunci akun layanan di bawah palsu.
 */
class PlayServiceWiringTest extends TestCase
{
    private function accountJson(): string
    {
        return json_encode([
            'type' => 'service_account',
            'client_email' => 'uji@proyek-uji.iam.gserviceaccount.com',
            'private_key' => "-----BEGIN PRIVATE KEY-----\nPALSU-UNTUK-TES\n-----END PRIVATE KEY-----\n",
            'client_id' => '123',
        ]);
    }

    private function privateValue(object $object, string $property): mixed
    {
        return (new ReflectionProperty($object, $property))->getValue($object);
    }

    public function test_verifier_is_the_google_implementation_with_the_configured_package_name(): void
    {
        config(['services.play.package_name' => 'com.contoh.paket']);

        $verifier = app(PlayPurchaseVerifier::class);

        $this->assertInstanceOf(GooglePlayPurchaseVerifier::class, $verifier);
        $this->assertSame('com.contoh.paket', $this->privateValue($verifier, 'packageName'));
    }

    public function test_package_name_defaults_to_the_app_id(): void
    {
        $verifier = app(PlayPurchaseVerifier::class);

        $this->assertSame('com.roziqrizal.rizqflow', $this->privateValue($verifier, 'packageName'));
    }

    public function test_verifier_without_a_service_account_is_not_configured(): void
    {
        config(['services.play.service_account_json' => null]);

        $this->expectException(PlayNotConfigured::class);

        app(PlayPurchaseVerifier::class)->ensureConfigured();
    }

    public function test_verifier_reads_the_service_account_from_the_configured_key(): void
    {
        config(['services.play.service_account_json' => base64_encode($this->accountJson())]);

        app(PlayPurchaseVerifier::class)->ensureConfigured();

        $this->addToAssertionCount(1); // tidak melempar PlayNotConfigured
    }

    public function test_verifier_is_one_instance_per_process(): void
    {
        $this->assertSame(app(PlayPurchaseVerifier::class), app(PlayPurchaseVerifier::class));
    }

    public function test_notification_authenticator_is_configured_from_the_audience_key(): void
    {
        config(['services.play.rtdn_audience' => 'https://contoh.test/api/play/notifications']);

        $authenticator = app(PlayNotificationAuthenticator::class);

        $this->assertInstanceOf(GoogleOidcNotificationAuthenticator::class, $authenticator);
        $this->assertTrue($authenticator->isConfigured());
        $this->assertSame('https://contoh.test/api/play/notifications', $this->privateValue($authenticator, 'audience'));
    }

    public function test_notification_authenticator_without_an_audience_is_not_configured(): void
    {
        config(['services.play.rtdn_audience' => null]);

        $this->assertFalse(app(PlayNotificationAuthenticator::class)->isConfigured());
    }

    public function test_notification_authenticator_gets_the_configured_service_account_email(): void
    {
        config([
            'services.play.rtdn_audience' => 'https://contoh.test/hook',
            'services.play.rtdn_service_account_email' => 'pubsub@proyek-uji.iam.gserviceaccount.com',
        ]);

        $authenticator = app(PlayNotificationAuthenticator::class);

        $this->assertSame('pubsub@proyek-uji.iam.gserviceaccount.com', $this->privateValue($authenticator, 'serviceAccountEmail'));
    }
}
