<?php

namespace Tests\Support;

use App\Models\User;
use DateTimeInterface;
use Illuminate\Testing\TestResponse;

/**
 * Pembantu tes siklus hidup token API (Tahap 12): permintaan persis seperti klien Retrofit (tanpa
 * header Accept, isi JSON) dan penerbitan token dengan masa berlaku yang bisa diatur.
 */
trait UsesApiTokens
{
    /** Satu permintaan API. Token Bearer opsional; tanpa token berarti tanpa header Authorization sama sekali. */
    private function api(string $method, string $uri, ?string $token = null, array $body = []): TestResponse
    {
        // Guard Sanctum menyimpan pengguna antar permintaan dalam satu tes; tanpa ini token kedua
        // "dikenali" sebagai pengguna permintaan pertama.
        $this->app['auth']->forgetGuards();

        $server = ['CONTENT_TYPE' => 'application/json'];
        if ($token !== null) {
            $server['HTTP_AUTHORIZATION'] = 'Bearer '.$token;
        }

        return $this->call($method, $uri, [], [], [], $server, $body === [] ? '{}' : json_encode($body));
    }

    /** Token API untuk $user; bawaan berlaku 30 hari dari sekarang. Mengembalikan teks tokennya. */
    private function tokenFor(User $user, string $name = 'android:hp', ?DateTimeInterface $expiresAt = null, array $abilities = ['*']): string
    {
        return $user->createToken($name, $abilities, $expiresAt ?? now()->addDays(30))->plainTextToken;
    }

    /** Akun yang boleh memakai endpoint sync (paket Sync), supaya GET /api/sync/pull menjawab 200 bagi token sah. */
    private function syncUser(): User
    {
        return User::factory()->create(['plans' => ['sync']]);
    }
}
