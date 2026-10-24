<?php

namespace App\Providers;

use App\Auth\GoogleApiIdTokenVerifier;
use App\Auth\GoogleIdTokenVerifier;
use App\Play\GoogleOidcNotificationAuthenticator;
use App\Play\GooglePlayClientFactory;
use App\Play\GooglePlayPurchaseVerifier;
use App\Play\PlayNotificationAuthenticator;
use App\Play\PlayPurchaseVerifier;
use Illuminate\Cache\RateLimiting\Limit;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\RateLimiter;
use Illuminate\Support\ServiceProvider;

class AppServiceProvider extends ServiceProvider
{
    /**
     * Register any application services.
     */
    public function register(): void
    {
        $this->app->bind(GoogleIdTokenVerifier::class, fn () => new GoogleApiIdTokenVerifier(
            (string) config('services.google.client_id'),
        ));

        // Verifikasi pembelian Google Play (Tahap 9). Satu instance per proses supaya klien dan
        // token akses Google dipakai ulang dalam satu permintaan; klien dibangun malas, jadi server
        // tanpa kredensial tetap jalan (verifikasi lalu menjawab PlayNotConfigured).
        $this->app->singleton(PlayPurchaseVerifier::class, fn () => new GooglePlayPurchaseVerifier(
            (string) config('services.play.package_name'),
            fn () => GooglePlayClientFactory::make(config('services.play.service_account_json')),
        ));

        $this->app->bind(PlayNotificationAuthenticator::class, fn () => new GoogleOidcNotificationAuthenticator(
            (string) config('services.play.rtdn_audience'),
            config('services.play.rtdn_service_account_email'),
        ));
    }

    /**
     * Bootstrap any application services.
     */
    public function boot(): void
    {
        // Klaim memanggil Google untuk tiap item (sampai 10): dibatasi per akun supaya klien yang
        // salah tingkah tidak menghabiskan kuota Play Developer API.
        RateLimiter::for('purchase-claim', fn (Request $request) => Limit::perMinute(10)
            ->by('purchase-claim:'.($request->user()?->getAuthIdentifier() ?? $request->ip())));

        // Endpoint RTDN tanpa Sanctum: batas per IP melindungi pemeriksaan JWT dari banjir token palsu.
        // Batas longgar; Pub/Sub mengulang pesan yang ditolak 429.
        RateLimiter::for('play-notifications', fn (Request $request) => Limit::perMinute(120)
            ->by('play-notifications:'.$request->ip()));
    }
}
