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

        RoomMember::tambahAnggota($request->user()->id, $roomId);

        return response()->json(status: 204);
    }

    /**
     * Mengeluarkan anggota dari ruang (keputusan 2026-10-27): hanya pemilik ruang yang boleh, dan
     * pemilik sendiri tidak bisa dikeluarkan (belum ada alur pindah kepemilikan). Yang dikeluarkan
     * kehilangan akses pull ke baris ruang ini; kunci ruang baru dibagikan lewat undangan QR ulang
     * oleh sisi aplikasi (RoomMemberKeyRedistribution), bukan oleh endpoint ini.
     */
    public function remove(Request $request, string $roomId, string $userId): JsonResponse
    {
        if ($denied = $this->denyWithoutSharedRoom($request)) {
            return $denied;
        }

        $isOwner = RoomMember::where('room_id', $roomId)
            ->where('user_id', $request->user()->id)
            ->where('is_owner', true)
            ->exists();
        if (! $isOwner) {
            return response()->json(['message' => 'Hanya pemilik ruang yang bisa mengeluarkan anggota.'], 403);
        }

        $target = RoomMember::where('room_id', $roomId)->where('user_id', $userId)->first();
        if ($target === null) {
            return response()->json(['message' => 'Anggota tidak ditemukan di ruang ini.'], 404);
        }

        if ($target->is_owner) {
            return response()->json(['message' => 'Pemilik ruang tidak bisa dikeluarkan.'], 422);
        }

        $target->delete();

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
                'owner' => $member->is_owner,
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
