<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\PlayPurchase;
use App\Play\ClaimStatus;
use App\Play\PlanSynchronizer;
use App\Play\PlayNotConfigured;
use App\Play\PurchaseClaimService;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/**
 * Klaim pembelian Pro dan langganan Sync dari aplikasi Android (Tahap 9, docs/server-sync.md
 * "Pembelian Pro dan Sync"). Aplikasi mengirim token pembelian setelah masuk; server memverifikasi
 * ke Google Play dan mengisi `users.plans`. Semua keputusan ada di PurchaseClaimService.
 */
class PurchaseController extends Controller
{
    private const MAX_ITEMS = 10;

    /**
     * Respons 200: `plans` (paket akun ini di server SETELAH klaim, urutan pro lalu sync) dan
     * `results` (satu per item masukan, urutan sama). 503 bila verifikasi Play belum
     * dikonfigurasi (tidak ada yang diubah). Token pembelian tidak pernah ada di respons.
     */
    public function claim(Request $request, PurchaseClaimService $claims): JsonResponse
    {
        $data = $request->validate([
            'purchases' => ['required', 'array', 'min:1', 'max:'.self::MAX_ITEMS],
            'purchases.*' => ['array'],
            'purchases.*.product_id' => ['required', 'string', 'max:100'],
            'purchases.*.purchase_token' => ['required', 'string', 'max:'.PlayPurchase::MAX_TOKEN_LENGTH],
        ]);

        $items = array_values($data['purchases']);

        try {
            $statuses = $claims->claim($request->user(), $items);
        } catch (PlayNotConfigured $e) {
            return response()->json(['message' => $e->getMessage()], 503);
        }

        return response()->json([
            'plans' => PlanSynchronizer::plansOf($request->user()),
            'results' => array_map(
                fn (array $item, ClaimStatus $status) => ['product_id' => $item['product_id'], 'status' => $status->value],
                $items,
                $statuses,
            ),
        ]);
    }
}
