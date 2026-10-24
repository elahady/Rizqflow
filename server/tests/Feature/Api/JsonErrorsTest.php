<?php

namespace Tests\Feature\Api;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/**
 * Klien Android tidak mengirim `Accept: application/json`; galat /api/* tetap harus berupa JSON
 * (401 tanpa token, 422 body salah), bukan pengalihan ke halaman /login.
 */
class JsonErrorsTest extends TestCase
{
    use RefreshDatabase;

    /** Permintaan JSON tanpa header Accept, persis seperti Retrofit. */
    private function callWithoutAccept(string $method, string $uri, string $body = '{}', ?string $bearer = null)
    {
        $server = ['CONTENT_TYPE' => 'application/json'];
        if ($bearer !== null) {
            $server['HTTP_AUTHORIZATION'] = 'Bearer '.$bearer;
        }

        return $this->call($method, $uri, [], [], [], $server, $body);
    }

    public function test_missing_api_token_is_a_json_401_not_a_redirect_to_login(): void
    {
        foreach (['/api/purchases/claim', '/api/sync/push', '/api/rooms/abc/join'] as $uri) {
            $response = $this->callWithoutAccept('POST', $uri);

            $response->assertStatus(401)->assertJsonStructure(['message']);
            $this->assertNull($response->headers->get('Location'), $uri);
        }
        $this->callWithoutAccept('DELETE', '/api/account')->assertStatus(401)->assertJsonStructure(['message']);
    }

    public function test_validation_errors_are_a_json_422_not_a_redirect(): void
    {
        $token = User::factory()->create()->createToken('t')->plainTextToken;

        $this->callWithoutAccept('POST', '/api/purchases/claim', '{}', $token)
            ->assertStatus(422)
            ->assertJsonValidationErrors('purchases');
    }

    public function test_web_pages_still_redirect_guests_to_login(): void
    {
        $this->get('/denah')->assertRedirect('/login');
    }
}
