<?php

namespace Tests\Feature\Api;

use App\Models\User;
use App\Support\ApiErrorResponses;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\Exceptions\PostTooLargeException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\RateLimiter;
use Illuminate\Support\Facades\Route;
use Tests\Support\UsesApiTokens;
use Tests\TestCase;

/**
 * Badan galat JSON `/api/*` yang berasal dari pengecualian diseragamkan (`message` Indonesia + `code` stabil)
 * dengan galat buatan controller, dan batas laju sync/ruang terpasang (Tahap 9, pengerasan server).
 */
class ApiErrorBodiesTest extends TestCase
{
    use RefreshDatabase;
    use UsesApiTokens;

    protected function setUp(): void
    {
        parent::setUp();

        // .env lokal biasanya APP_DEBUG=true (jejak galat ikut di badan JSON); produksi false. Tes memeriksa bentuk produksi.
        config(['app.debug' => false]);
    }

    public function test_an_unknown_api_route_is_a_json_404_with_a_code(): void
    {
        $this->api('GET', '/api/tidak-ada')
            ->assertNotFound()
            ->assertExactJson(['message' => 'Alamat atau data yang diminta tidak ditemukan.', 'code' => 'not_found']);
    }

    public function test_a_wrong_method_is_a_json_405_with_a_code(): void
    {
        $token = $this->tokenFor($this->syncUser());

        $this->api('GET', '/api/sync/push', $token)
            ->assertStatus(405)
            ->assertJsonPath('code', 'method_not_allowed')
            ->assertJsonStructure(['message', 'code']);
    }

    public function test_a_missing_token_is_a_json_401_with_a_code_and_an_indonesian_message(): void
    {
        $this->api('POST', '/api/sync/push')
            ->assertUnauthorized()
            ->assertExactJson(['message' => 'Perlu masuk, atau sesi sudah berakhir.', 'code' => 'unauthenticated']);
    }

    public function test_an_expired_or_unknown_token_is_the_same_401(): void
    {
        $this->api('GET', '/api/sync/history', 'token-yang-tidak-pernah-ada')
            ->assertUnauthorized()
            ->assertJsonPath('code', 'unauthenticated');
    }

    public function test_validation_errors_keep_their_shape_and_gain_a_code(): void
    {
        $token = $this->tokenFor($this->syncUser());

        $response = $this->api('POST', '/api/sync/push', $token, ['entries' => []])->assertStatus(422);

        $response->assertJsonPath('code', 'validation_failed');
        $response->assertJsonStructure(['message', 'code', 'errors' => ['entries']]);
    }

    public function test_a_controller_made_code_is_never_overwritten(): void
    {
        $token = $this->tokenFor(User::factory()->create(['plans' => []]));

        // 403 buatan controller (DeniesWithoutSync) membawa `sync_required`, bukan `forbidden`.
        $this->api('GET', '/api/sync/pull?device_id=x', $token)->assertForbidden()->assertJsonPath('code', 'sync_required');
        // 422 buatan controller (device_id wajib) tetap `device_id_required`, bukan `validation_failed`.
        $this->api('GET', '/api/sync/pull', $this->tokenFor($this->syncUser()))->assertStatus(422)->assertJsonPath('code', 'device_id_required');
    }

    public function test_a_throttled_request_is_a_json_429_with_a_code_retry_after_and_the_header(): void
    {
        $token = $this->tokenFor($this->syncUser());
        $entry = ['record_type' => 'r', 'record_id' => 'x', 'device_id' => 'd', 'payload' => 'p', 'changed_at' => '2026-11-01T10:00:00Z'];

        for ($i = 0; $i < 120; $i++) {
            $this->api('POST', '/api/sync/push', $token, ['entries' => [$entry]])->assertOk();
        }
        $blocked = $this->api('POST', '/api/sync/push', $token, ['entries' => [$entry]])->assertStatus(429);

        $blocked->assertJsonPath('code', 'rate_limited');
        $blocked->assertJsonPath('message', 'Terlalu banyak permintaan. Coba lagi sebentar lagi.');
        $this->assertIsInt($blocked->json('retry_after'));
        $this->assertGreaterThanOrEqual(1, $blocked->json('retry_after'));
        $this->assertSame((string) $blocked->json('retry_after'), $blocked->headers->get('Retry-After'));
    }

    public function test_the_push_limit_is_per_account_not_global(): void
    {
        $first = $this->tokenFor($this->syncUser());
        $second = $this->tokenFor($this->syncUser());
        $entry = ['record_type' => 'r', 'record_id' => 'x', 'device_id' => 'd', 'payload' => 'p', 'changed_at' => '2026-11-01T10:00:00Z'];

        for ($i = 0; $i < 121; $i++) {
            $this->api('POST', '/api/sync/push', $first, ['entries' => [$entry]]);
        }

        $this->api('POST', '/api/sync/push', $first, ['entries' => [$entry]])->assertStatus(429);
        $this->api('POST', '/api/sync/push', $second, ['entries' => [$entry]])->assertOk();
    }

    public function test_the_other_limiters_are_installed_with_generous_per_account_limits(): void
    {
        $request = Request::create('/api/sync/pull');
        $request->setUserResolver(fn () => User::factory()->make(['id' => 7]));

        $this->assertSame(120, RateLimiter::limiter('sync-push')($request)->maxAttempts);
        $this->assertSame(600, RateLimiter::limiter('sync-pull')($request)->maxAttempts);
        $this->assertSame(60, RateLimiter::limiter('room-membership')($request)->maxAttempts);

        $middlewareOf = fn (string $method, string $uri) => collect(Route::getRoutes()->getRoutes())
            ->first(fn ($route) => $route->uri() === $uri && in_array($method, $route->methods(), true))
            ->gatherMiddleware();

        $this->assertContains('throttle:sync-push', $middlewareOf('POST', 'api/sync/push'));
        $this->assertContains('throttle:sync-pull', $middlewareOf('GET', 'api/sync/pull'));
        $this->assertContains('throttle:sync-pull', $middlewareOf('GET', 'api/sync/history'));
        foreach ([['POST', 'api/rooms/{roomId}/join'], ['POST', 'api/rooms/{roomId}/leave'], ['GET', 'api/rooms/{roomId}/members'], ['DELETE', 'api/rooms/{roomId}/members/{userId}']] as [$method, $uri]) {
            $this->assertContains('throttle:room-membership', $middlewareOf($method, $uri), "$method $uri");
        }
    }

    // ---- ApiErrorResponses::normalize langsung (kasus yang sulit dipicu lewat HTTP)

    public function test_payload_too_large_and_server_errors_get_codes_and_indonesian_messages(): void
    {
        $request = Request::create('/api/sync/push', 'POST');

        $tooLarge = ApiErrorResponses::normalize(new JsonResponse(['message' => 'Payload Too Large'], 413), new PostTooLargeException, $request);
        $this->assertSame(['message' => 'Badan permintaan terlalu besar.', 'code' => 'payload_too_large'], $tooLarge->getData(true));

        $server = ApiErrorResponses::normalize(new JsonResponse(['message' => 'Server Error'], 500), new \RuntimeException('x'), $request);
        $this->assertSame(['message' => 'Terjadi kesalahan di server. Coba lagi nanti.', 'code' => 'server_error'], $server->getData(true));
    }

    public function test_debug_keeps_the_original_message_but_still_adds_the_code(): void
    {
        config(['app.debug' => true]);
        $request = Request::create('/api/sync/push', 'POST');

        $response = ApiErrorResponses::normalize(new JsonResponse(['message' => 'SQLSTATE[...] sebenarnya'], 500), new \RuntimeException('x'), $request);

        $this->assertSame('SQLSTATE[...] sebenarnya', $response->getData(true)['message']);
        $this->assertSame('server_error', $response->getData(true)['code']);
    }

    public function test_web_routes_and_successful_responses_are_not_touched(): void
    {
        $web = new JsonResponse(['message' => 'Not Found'], 404);
        $this->assertSame(['message' => 'Not Found'], ApiErrorResponses::normalize($web, new \RuntimeException, Request::create('/denah'))->getData(true));

        $ok = new JsonResponse(['ids' => [1]], 200);
        $this->assertSame(['ids' => [1]], ApiErrorResponses::normalize($ok, new \RuntimeException, Request::create('/api/sync/push'))->getData(true));
    }

    public function test_web_pages_still_render_the_normal_404_page_not_json(): void
    {
        $this->get('/halaman-yang-tidak-ada')->assertNotFound()->assertHeader('Content-Type', 'text/html; charset=utf-8');
    }
}
