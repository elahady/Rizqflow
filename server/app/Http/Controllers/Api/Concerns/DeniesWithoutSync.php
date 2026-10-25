<?php

namespace App\Http\Controllers\Api\Concerns;

use App\Support\Feature;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/** Satu-satunya tempat yang menjawab "boleh atau tidak" untuk endpoint yang butuh Sync — lihat Support\Entitlements. */
trait DeniesWithoutSync
{
    private function denyWithoutSync(Request $request): ?JsonResponse
    {
        if ($request->user()->entitlements()->isEnabled(Feature::EncryptedSync)) {
            return null;
        }

        return response()->json(['message' => 'Butuh langganan Sync.'], 403);
    }
}
