<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\RoomMember;
use App\Support\Feature;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/**
 * Keanggotaan ruang yang dibagi (Tahap 9, roadmap.md "Ruang keluarga bersama"). [join] dipanggil
 * perangkat anggota baru segera setelah memindai undangan QR — sebelum mereka punya apa pun untuk
 * di-push, supaya pull pertama mereka sudah termasuk baris ruang itu. [members] menampilkan siapa
 * saja anggotanya; hanya anggota ruang itu sendiri yang boleh melihat daftarnya.
 */
class RoomMembershipController extends Controller
{
    public function join(Request $request, string $roomId): JsonResponse
    {
        if ($denied = $this->denyWithoutSharedRoom($request)) {
            return $denied;
        }

        RoomMember::firstOrCreate([
            'user_id' => $request->user()->id,
            'room_id' => $roomId,
        ]);

        return response()->json(status: 204);
    }

    public function members(Request $request, string $roomId): JsonResponse
    {
        if ($denied = $this->denyWithoutSharedRoom($request)) {
            return $denied;
        }

        $userId = $request->user()->id;

        $isMember = RoomMember::where('room_id', $roomId)->where('user_id', $userId)->exists();
        if (! $isMember) {
            return response()->json(['message' => 'Bukan anggota ruang ini.'], 403);
        }

        $members = RoomMember::where('room_id', $roomId)
            ->with('user:id,name,email')
            ->get()
            ->map(fn (RoomMember $member) => [
                'name' => $member->user->name,
                'email' => $member->user->email,
                'you' => $member->user_id === $userId,
            ]);

        return response()->json(['members' => $members]);
    }

    /** Satu-satunya tempat yang menjawab "boleh atau tidak" untuk ruang keluarga — lihat Support\Entitlements. */
    private function denyWithoutSharedRoom(Request $request): ?JsonResponse
    {
        if ($request->user()->entitlements()->isEnabled(Feature::SharedFamilyRoom)) {
            return null;
        }

        return response()->json(['message' => 'Butuh langganan Sync.'], 403);
    }
}
