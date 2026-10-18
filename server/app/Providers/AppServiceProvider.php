<?php

namespace App\Providers;

use App\Auth\GoogleApiIdTokenVerifier;
use App\Auth\GoogleIdTokenVerifier;
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
    }

    /**
     * Bootstrap any application services.
     */
    public function boot(): void
    {
        //
    }
}
