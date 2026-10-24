<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Play\PlayNotConfigured;
use App\Play\PlayNotification;
use App\Play\PlayNotificationAuthenticator;
use App\Play\PlayNotificationHandler;
use App\Play\PlayUnavailable;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;

/**
 * Endpoint push Pub/Sub untuk Real-time Developer Notifications Google Play (Tahap 9). Tanpa
 * Sanctum: pemanggilnya Google, diautentikasi lewat JWT OIDC di header Authorization. Rute ini
 * tidak pernah menerima permintaan tanpa autentikasi.
 *
 * Kode jawaban (Pub/Sub menganggap 102/200/201/202/204 sebagai "sudah diterima" dan mengulang
 * semua yang lain):
 * - 503: audience belum dikonfigurasi (atau kredensial Play belum ada saat memproses);
 * - 401: token tidak sah, tanpa alasan rinci;
 * - 204: pesan sah diproses, tidak relevan, atau badannya rusak (tidak diulang: pesan rusak tidak
 *   akan pernah bisa diurai, jadi mengulangnya hanya membuang antrean);
 * - 500: Play gagal sementara saat verifikasi ulang, supaya Pub/Sub mengulang.
 */
class PlayNotificationController extends Controller
{
    public function receive(
        Request $request,
        PlayNotificationAuthenticator $authenticator,
        PlayNotificationHandler $handler,
    ): Response|JsonResponse {
        if (! $authenticator->isConfigured()) {
            return response()->json(['message' => 'Notifikasi Play belum dikonfigurasi di server.'], 503);
        }

        $bearer = $request->bearerToken();
        if ($bearer === null || ! $authenticator->isAuthentic($bearer)) {
            return response()->json(['message' => 'Unauthenticated.'], 401);
        }

        $notification = PlayNotification::fromPubSub($request->json()->all());
        if ($notification === null) {
            return response()->noContent();
        }

        try {
            $handler->handle($notification);
        } catch (PlayNotConfigured) {
            return response()->json(['message' => 'Verifikasi pembelian belum dikonfigurasi di server.'], 503);
        } catch (PlayUnavailable) {
            return response()->json(['message' => 'Verifikasi sementara gagal.'], 500);
        }

        return response()->noContent();
    }
}
