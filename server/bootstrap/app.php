<?php

use App\Http\Middleware\EnsureEmailCodesEnabled;
use Illuminate\Foundation\Application;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Foundation\Configuration\Middleware;
use Illuminate\Http\Request;
use Symfony\Component\HttpKernel\Exception\HttpException;

return Application::configure(basePath: dirname(__DIR__))
    ->withRouting(
        web: __DIR__.'/../routes/web.php',
        api: __DIR__.'/../routes/api.php',
        commands: __DIR__.'/../routes/console.php',
        health: '/up',
    )
    ->withMiddleware(function (Middleware $middleware): void {
        // Di VPS, traffic masuk lewat Cloudflare lalu nginx host (proxy ke container). Tanpa ini
        // Laravel mengira request HTTP biasa dan membuat URL http://, bukan https://.
        $middleware->trustProxies(at: '*');

        $middleware->alias(['email-codes' => EnsureEmailCodesEnabled::class]);
    })
    ->withExceptions(function (Exceptions $exceptions): void {
        // Semua /api/* menjawab galat sebagai JSON (401, 403, 422, 404, 500) tanpa memandang header
        // Accept. Klien Android (Retrofit/OkHttp) tidak mengirim `Accept: application/json`, dan
        // tanpa ini Laravel mengalihkan 401 ke halaman /login (302 lalu HTML) serta 422 ke halaman
        // sebelumnya, sehingga kontrak API (mis. 401 tanpa token, 422 body salah) tidak terpenuhi.
        $exceptions->shouldRenderJsonWhen(
            fn (Request $request) => $request->is('api/*') || $request->expectsJson(),
        );

        // Sesi berakhir (token CSRF tak cocok, mis. form dibiarkan terbuka lama): kembali ke Masuk
        // dengan notice, bukan halaman 419. Permintaan Livewire/JSON tetap ditangani Livewire.
        $exceptions->render(function (HttpException $e, $request) {
            if ($e->getStatusCode() !== 419 || $request->expectsJson() || $request->hasHeader('X-Livewire')) {
                return null;
            }

            return redirect()->route('login')
                ->with('status', 'Sesimu berakhir karena lama tidak dipakai. Masuk lagi untuk melanjutkan.');
        });
    })->create();
