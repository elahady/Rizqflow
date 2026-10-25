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
use Laravel\Sanctum\PersonalAccessToken;

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

        // Putar token API (HANYA POST /auth/refresh): klien sah memutar token sekali dalam berbulan-bulan,
        // jadi 10 per menit sangat longgar bagi klien yang benar dan menahan klien yang salah tingkah.
        // Kunci per akun + nama token (= per perangkat, nama `android:<device_id>` ikut berputar), BUKAN
        // per akun: pemegang token curian yang memutar token berulang hanya menghabiskan jatah garis
        // keturunan token itu sendiri, tidak jatah perangkat lain milik korban. BUKAN per id token pula,
        // sebab tiap rotasi menerbitkan id baru sehingga batasnya tak pernah tercapai. Logout dan
        // DELETE /auth/tokens sengaja tanpa batas (idempoten, murah) supaya tak bisa dihalangi lewat refresh.
        RateLimiter::for('api-token-refresh', function (Request $request) {
            $user = $request->user();
            $current = $user?->currentAccessToken();

            $key = match (true) {
                $user !== null && $current instanceof PersonalAccessToken => $user->getAuthIdentifier().'|'.md5($current->name),
                $user !== null => (string) $user->getAuthIdentifier(),
                default => (string) $request->ip(),
            };

            return Limit::perMinute(10)->by('api-token-refresh:'.$key);
        });

        // Masuk dengan Google dari aplikasi: tanpa token, jadi dibatasi per IP. 30 per menit, longgar
        // karena banyak ponsel berbagi satu IP di balik NAT seluler; cukup untuk menahan banjir
        // permintaan (tiap permintaan memanggil verifikasi Google dan bisa membuat akun/token).
        RateLimiter::for('api-login', fn (Request $request) => Limit::perMinute(30)
            ->by('api-login:'.$request->ip()));

        // Salinan kunci pemulihan (PUT/GET/DELETE /account/recovery-key): satu jatah bersama 20 per menit
        // per akun. Klien sah memanggilnya sekali-sekali (buat, pulihkan), jadi sangat longgar; menahan
        // klien yang salah tingkah dan percobaan menguras server dengan blob besar. Rutenya di dalam
        // auth:sanctum, jadi permintaan 401 tidak menghabiskan jatah; kunci `?? ip` hanya cadangan.
        RateLimiter::for('recovery-key', fn (Request $request) => Limit::perMinute(20)
            ->by('recovery-key:'.($request->user()?->getAuthIdentifier() ?? $request->ip())));

        // Endpoint RTDN tanpa Sanctum: batas per IP melindungi pemeriksaan JWT dari banjir token palsu.
        // Batas longgar; Pub/Sub mengulang pesan yang ditolak 429.
        RateLimiter::for('play-notifications', fn (Request $request) => Limit::perMinute(120)
            ->by('play-notifications:'.$request->ip()));
    }
}
