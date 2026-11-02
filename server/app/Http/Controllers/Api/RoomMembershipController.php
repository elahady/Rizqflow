<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\RoomInvite;
use App\Models\RoomMember;
use App\Support\MemberId;
use App\Support\SharedRoomOwner;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

/**
 * Keanggotaan ruang yang dibagi (Tahap 9, roadmap.md "Ruang keluarga bersama"). [join] dipanggil
 * perangkat anggota baru segera setelah memindai undangan QR — sebelum mereka punya apa pun untuk
 * di-push, supaya pull pertama mereka sudah termasuk baris ruang itu. [members] menampilkan siapa
 * saja anggotanya; hanya anggota ruang itu sendiri yang boleh melihat daftarnya. [leave] adalah
 * anggota yang keluar sendiri; [remove] pemilik yang mengeluarkan anggota lain; [makeOwner] pemilik
 * yang memindahkan kepemilikan ke anggota lain (setelah itu ia boleh keluar lewat [leave]).
 *
 * Semua galat yang dibuat di sini membawa `code` yang stabil (selain `message` untuk manusia) supaya
 * klien bisa membedakan kasus yang sama-sama 403/404/422.
 */
class RoomMembershipController extends Controller
{
    /** Sama dengan batas `entries.*.room_id` di SyncController::push: id yang lebih panjang tidak pernah bisa dipakai sync. */
    private const MAX_ROOM_ID_LENGTH = 100;

    /** monetisasi.md: satu langganan Sync mencakup keluarga sampai 5 anggota (peran-anggota.md E.1 butir 3). */
    private const MAX_MEMBERS = 5;

    /** SHA-256 dalam heksadesimal (huruf kecil atau besar; disimpan huruf kecil). */
    private const INVITE_HASH_PATTERN = '/^[0-9a-fA-F]{64}$/';

    /**
     * Anggota baru bergabung (dari undangan QR) atau pengirim push sync yang belum tercatat
     * (bootstrap, lihat SyncController). Idempoten: anggota yang sudah tercatat tidak kena batas
     * jumlah lagi saat `join` diulang. Dikunci dalam satu transaksi (pola sama dengan [makeOwner])
     * supaya dua pendaftar ke-6 serentak tidak bisa sama-sama lolos batas.
     */
    public function join(Request $request, string $roomId): JsonResponse
    {
        if ($denied = $this->denyWithoutSharedRoom($request, $roomId) ?? $this->denyInvalidRoomId($roomId)) {
            return $denied;
        }

        $data = $request->validate([
            'invite_hash' => ['nullable', 'string', 'regex:'.self::INVITE_HASH_PATTERN],
        ]);
        $inviteHash = isset($data['invite_hash']) ? strtolower($data['invite_hash']) : null;

        $userId = $request->user()->id;

        /** @var JsonResponse|null $galat */
        $galat = DB::transaction(function () use ($roomId, $userId, $inviteHash): ?JsonResponse {
            $anggota = RoomMember::where('room_id', $roomId)->lockForUpdate()->get();
            if ($anggota->contains(fn (RoomMember $member) => (int) $member->user_id === $userId)) {
                // Sudah anggota: idempoten, undangan (kalau ada) tidak disentuh dan tidak dihabiskan.
                return null;
            }

            // Ruang yang belum punya anggota sama sekali dibuat oleh penggabung pertamanya, yang jadi
            // pemilik (RoomMember::tambahAnggota) — tidak ada pemilik yang bisa menerbitkan undangan.
            if ($anggota->isEmpty()) {
                RoomMember::tambahAnggota($userId, $roomId);

                return null;
            }

            // Selain itu, semua penggabung baru wajib membawa undangan terdaftar yang sah: tanpa masa
            // tenggang (keputusan pemilik 2026-11-03). Ini juga yang menutup celah bekas anggota yang
            // mendaftar ulang sendiri: undangannya sekali pakai dan sudah habis.
            if ($inviteHash === null) {
                return $this->inviteError('invite_required', 'Bergabung ke ruang ini butuh undangan dari pemilik ruang.');
            }
            $undangan = RoomInvite::where('room_id', $roomId)->where('invite_hash', $inviteHash)->lockForUpdate()->first();
            if ($undangan === null || $undangan->isUsed()) {
                return $this->inviteError('invite_invalid', 'Undangan tidak sah atau sudah dipakai.');
            }
            if ($undangan->isExpired()) {
                return $this->inviteError('invite_expired', 'Undangan sudah kedaluwarsa. Minta pemilik ruang membuat undangan baru.');
            }

            if ($anggota->count() >= self::MAX_MEMBERS) {
                return response()->json([
                    'message' => 'Ruang ini sudah penuh (maksimal '.self::MAX_MEMBERS.' anggota).',
                    'code' => 'room_full',
                ], 422);
            }

            RoomMember::tambahAnggota($userId, $roomId);
            $undangan->update(['used_by_user_id' => $userId]);

            return null;
        });

        return $galat ?? response()->json(status: 204);
    }

    /**
     * Pemilik mendaftarkan undangan sebelum QR-nya ditampilkan (key_grant langkah 1). Server hanya
     * menerima `invite_hash` (SHA-256 heksadesimal dari HKDF(member_secret, "rizqflow-invite")); rahasia
     * anggotanya tidak pernah dikirim. Sekali pakai dan kedaluwarsa [RoomInvite::LIFETIME_DAYS] hari.
     *
     * - Hanya pemilik ruang: 403 `not_room_owner`. Butuh Sync pemilik seperti endpoint ruang lain.
     * - Idempoten untuk hash yang sama yang belum dipakai: masa berlakunya diperbarui (percobaan ulang
     *   setelah jaringan putus tidak menghasilkan baris ganda). Hash yang sudah dipakai: 409 `invite_used`.
     * - Undangan yang menunggu dibatasi [RoomInvite::MAX_PENDING] per ruang: di atas batas, yang paling lama
     *   (belum dipakai) disingkirkan, bukan ditolak. Yang kedaluwarsa dan belum dipakai dibuang saat penerbitan.
     */
    public function invite(Request $request, string $roomId): JsonResponse
    {
        if ($denied = $this->denyWithoutSharedRoom($request, $roomId) ?? $this->denyInvalidRoomId($roomId)) {
            return $denied;
        }

        $data = $request->validate([
            'invite_hash' => ['required', 'string', 'regex:'.self::INVITE_HASH_PATTERN],
        ]);
        $inviteHash = strtolower($data['invite_hash']);
        $userId = $request->user()->id;

        return DB::transaction(function () use ($roomId, $userId, $inviteHash): JsonResponse {
            $anggota = RoomMember::where('room_id', $roomId)->orderBy('id')->lockForUpdate()->get();
            $pemilik = $anggota->first(fn (RoomMember $member) => (int) $member->user_id === $userId && $member->is_owner);
            if ($pemilik === null) {
                return response()->json([
                    'message' => 'Hanya pemilik ruang yang bisa membuat undangan.',
                    'code' => 'not_room_owner',
                ], 403);
            }

            $kedaluwarsa = now()->addDays(RoomInvite::LIFETIME_DAYS);
            $ada = RoomInvite::where('room_id', $roomId)->where('invite_hash', $inviteHash)->first();
            if ($ada !== null) {
                if ($ada->isUsed()) {
                    return response()->json([
                        'message' => 'Undangan itu sudah dipakai. Buat undangan baru dengan rahasia baru.',
                        'code' => 'invite_used',
                    ], 409);
                }
                $ada->update(['expires_at' => $kedaluwarsa]);

                return response()->json(['expires_at' => $kedaluwarsa->toIso8601String()], 201);
            }

            // Kebersihan dan batas: undangan kedaluwarsa yang tak terpakai dibuang, dan di atas batas undangan
            // menunggu yang paling lama disingkirkan (bukan ditolak). Aplikasi menerbitkan undangan baru setiap
            // sheet QR dibuka; menolak pada batas akan mengunci pemilik yang membuka sheet berulang kali tanpa
            // ada yang memindai, padahal QR yang lama tak terpakai itu memang tak perlu dipertahankan.
            RoomInvite::where('room_id', $roomId)->whereNull('used_by_user_id')->where('expires_at', '<=', now())->delete();
            $menunggu = RoomInvite::where('room_id', $roomId)->whereNull('used_by_user_id')->orderBy('id');
            $lebih = $menunggu->count() - (RoomInvite::MAX_PENDING - 1);
            if ($lebih > 0) {
                RoomInvite::whereIn('id', (clone $menunggu)->limit($lebih)->pluck('id'))->delete();
            }

            RoomInvite::create([
                'room_id' => $roomId,
                'invite_hash' => $inviteHash,
                'issued_by' => $userId,
                'expires_at' => $kedaluwarsa,
            ]);

            return response()->json(['expires_at' => $kedaluwarsa->toIso8601String()], 201);
        });
    }

    private function inviteError(string $code, string $message): JsonResponse
    {
        return response()->json(['message' => $message, 'code' => $code], 403);
    }

    /**
     * Anggota keluar dari ruang atas kemauan sendiri (Tahap 9, sync-desain.md bagian G).
     *
     * - Hanya menghapus baris keanggotaan pemanggil; baris sync yang pernah ia tulis tetap di server
     *   (anggota lain masih membutuhkannya), dan yang bersangkutan berhenti menerima baris ruang itu.
     * - Pemilik tidak boleh keluar sebelum memindahkan kepemilikan (lihat [makeOwner]): kalau dibiarkan,
     *   ruang akan tanpa pemilik dan tak seorang pun bisa mengeluarkan anggota lagi. Dijawab 422
     *   `owner_cannot_leave`.
     * - Tidak butuh langganan Sync (seperti `DELETE /push-token`): pengguna yang langganannya berakhir
     *   tetap boleh melepaskan dirinya dari ruang. Tidak ada efek samping pada akun lain.
     * - Bukan anggota: 404 `not_room_member` (juga untuk ulangan setelah berhasil keluar).
     *
     * Baris keanggotaan dihapus fisik (bukan diberi status `removed`): setelah keluar, `join` dan push
     * ke ruang itu ditolak (403 `invite_required`/`not_room_member`) sampai pemilik menerbitkan undangan
     * baru, jadi aplikasi yang masih berjalan tidak bisa mendaftarkan dirinya lagi (key_grant langkah 1).
     */
    public function leave(Request $request, string $roomId): JsonResponse
    {
        if ($invalid = $this->denyInvalidRoomId($roomId)) {
            return $invalid;
        }

        $member = RoomMember::where('room_id', $roomId)->where('user_id', $request->user()->id)->first();
        if ($member === null) {
            return response()->json([
                'message' => 'Kamu bukan anggota ruang ini.',
                'code' => 'not_room_member',
            ], 404);
        }

        if ($member->is_owner) {
            return response()->json([
                'message' => 'Pemilik ruang tidak bisa keluar sebelum kepemilikan dipindahkan.',
                'code' => 'owner_cannot_leave',
            ], 422);
        }

        $member->delete();

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
        if ($denied = $this->denyWithoutSharedRoom($request, $roomId) ?? $this->denyInvalidRoomId($roomId)) {
            return $denied;
        }

        $isOwner = RoomMember::where('room_id', $roomId)
            ->where('user_id', $request->user()->id)
            ->where('is_owner', true)
            ->exists();
        if (! $isOwner) {
            // `code` membedakan 403 ini dari 403 "butuh Sync" di klien (yang membuka paywall).
            return response()->json([
                'message' => 'Hanya pemilik ruang yang bisa mengeluarkan anggota.',
                'code' => 'not_room_owner',
            ], 403);
        }

        $target = RoomMember::where('room_id', $roomId)->where('user_id', $userId)->first();
        if ($target === null) {
            return response()->json([
                'message' => 'Anggota tidak ditemukan di ruang ini.',
                'code' => 'member_not_found',
            ], 404);
        }

        if ($target->is_owner) {
            return response()->json([
                'message' => 'Pemilik ruang tidak bisa dikeluarkan.',
                'code' => 'owner_cannot_be_removed',
            ], 422);
        }

        $target->delete();

        return response()->json(status: 204);
    }

    /**
     * Pemilik memindahkan kepemilikan ruang ke anggota lain (roadmap.md Tahap 9, "pindah kepemilikan").
     *
     * - Hanya pemilik SAAT INI yang boleh memanggil: 403 `not_room_owner` (sama dengan [remove]).
     * - Target harus anggota aktif ruang itu: 404 `member_not_found`.
     * - Target yang sudah pemilik ditolak 422 `already_owner` (tidak idempoten: pemanggil diberi tahu
     *   bahwa tidak ada yang berubah, dan konsisten dengan 422 `owner_cannot_be_removed` di [remove]).
     * - Hanya mengubah kolom `is_owner` pada dua baris anggota. Tidak mengeluarkan siapa pun dan tidak
     *   menyentuh `sync_log_entries` atau kunci ruang: pemilik lama dan baru tetap anggota dengan kunci
     *   yang sama. Pemilik lama lalu bisa memanggil [leave] seperti anggota biasa.
     * - Atomik dan tanpa balapan: seluruh baris anggota ruang itu dikunci (`lockForUpdate`, diurutkan
     *   `id`) dalam satu transaksi, lalu semua pemeriksaan diulang di dalam kunci. Dua permintaan
     *   serentak tidak bisa menghasilkan dua pemilik atau nol pemilik. SQLite (tes) mengabaikan kunci
     *   baris, tetapi transaksinya tetap berjalan serial.
     */
    public function makeOwner(Request $request, string $roomId, string $userId): JsonResponse
    {
        if ($denied = $this->denyWithoutSharedRoom($request, $roomId) ?? $this->denyInvalidRoomId($roomId)) {
            return $denied;
        }

        $callerId = $request->user()->id;

        return DB::transaction(function () use ($roomId, $callerId, $userId): JsonResponse {
            $anggota = RoomMember::where('room_id', $roomId)->orderBy('id')->lockForUpdate()->get();

            $pemilikSaatIni = $anggota->first(fn (RoomMember $member) => (int) $member->user_id === $callerId && $member->is_owner);
            if ($pemilikSaatIni === null) {
                return response()->json([
                    'message' => 'Hanya pemilik ruang yang bisa memindahkan kepemilikan.',
                    'code' => 'not_room_owner',
                ], 403);
            }

            $target = $anggota->first(fn (RoomMember $member) => (int) $member->user_id === (int) $userId);
            if ($target === null) {
                return response()->json([
                    'message' => 'Anggota tidak ditemukan di ruang ini.',
                    'code' => 'member_not_found',
                ], 404);
            }

            if ($target->is_owner) {
                return response()->json([
                    'message' => 'Anggota itu sudah menjadi pemilik ruang.',
                    'code' => 'already_owner',
                ], 422);
            }

            $pemilikSaatIni->update(['is_owner' => false]);
            $target->update(['is_owner' => true]);

            return response()->json(status: 204);
        });
    }

    public function members(Request $request, string $roomId): JsonResponse
    {
        if ($denied = $this->denyWithoutSharedRoom($request, $roomId) ?? $this->denyInvalidRoomId($roomId)) {
            return $denied;
        }

        $userId = $request->user()->id;

        $isMember = RoomMember::where('room_id', $roomId)->where('user_id', $userId)->exists();
        if (! $isMember) {
            return response()->json([
                'message' => 'Bukan anggota ruang ini.',
                'code' => 'not_room_member',
            ], 403);
        }

        // Undangan yang dipakai tiap anggota: pemilik memakainya untuk mengikat rahasia undangan ke anggota
        // (key_grant langkah 2). Pemilik/pembuat ruang tidak memakai undangan, jadi null.
        $undanganDipakai = RoomInvite::where('room_id', $roomId)
            ->whereNotNull('used_by_user_id')
            ->pluck('invite_hash', 'used_by_user_id');

        $members = RoomMember::where('room_id', $roomId)
            ->with('user:id,name,email')
            ->get()
            ->map(fn (RoomMember $member) => [
                // Dipakai aplikasi pemilik sebagai {userId} di DELETE /rooms/{roomId}/members/{userId}.
                'user_id' => $member->user_id,
                // Sama dengan `author` baris sync ruang ini (peran-anggota.md E.1 butir 1); id internal
                // di atas tetap ada untuk endpoint hapus/pindah-kepemilikan yang sudah ada, tidak diganti.
                'member_id' => MemberId::dari($roomId, $member->user_id),
                'name' => $member->user->name,
                'email' => $member->user->email,
                'you' => $member->user_id === $userId,
                'owner' => $member->is_owner,
                'role' => $member->is_owner ? 'owner' : 'member',
                'invite_hash' => $undanganDipakai[$member->user_id] ?? null,
            ]);

        return response()->json(['members' => $members]);
    }

    /**
     * Satu-satunya tempat yang menjawab "boleh atau tidak" untuk ruang keluarga — lihat Support\Entitlements.
     * Dibuka oleh Sync PEMILIK ruang [roomId] (keputusan pemilik I-8), bukan Sync peminta sendiri — lihat
     * Entitlements::allowsSharedRoom.
     */
    private function denyWithoutSharedRoom(Request $request, string $roomId): ?JsonResponse
    {
        $allowed = $request->user()->entitlements()->allowsSharedRoom(SharedRoomOwner::entitlementsOf($roomId));
        if ($allowed) {
            return null;
        }

        return response()->json(['message' => 'Butuh langganan Sync.', 'code' => 'sync_required'], 403);
    }

    /** Id ruang yang lebih panjang dari batas push tidak mungkin sah; tolak sebelum menyentuh database (kolom MySQL varchar(255)). */
    private function denyInvalidRoomId(string $roomId): ?JsonResponse
    {
        if ($roomId !== '' && mb_strlen($roomId) <= self::MAX_ROOM_ID_LENGTH) {
            return null;
        }

        return response()->json([
            'message' => 'Id ruang tidak sah (1 sampai '.self::MAX_ROOM_ID_LENGTH.' karakter).',
            'code' => 'invalid_room_id',
        ], 422);
    }
}
