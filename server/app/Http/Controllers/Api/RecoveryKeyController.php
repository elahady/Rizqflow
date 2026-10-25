<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Api\Concerns\DeniesWithoutSync;
use App\Http\Controllers\Controller;
use App\Models\RecoveryKey;
use Closure;
use Illuminate\Database\QueryException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Log;
use Illuminate\Validation\Rule;

/**
 * Salinan kunci akun yang dibungkus kode pemulihan 24 kata (Tahap 13, docs/server-sync.md "Model
 * kunci"): satu blob buram per akun supaya perangkat baru bisa memulihkan kunci. Pembungkusan
 * (AES-256-GCM, kunci PBKDF2 dari frasa) terjadi di klien; server hanya menyimpan dan mengembalikan
 * byte yang sama. Server tidak pernah melihat kode pemulihan atau kunci terbaca, dan `wrapped_key`
 * tidak pernah masuk log, pesan galat, atau respons selain GET milik pemiliknya.
 *
 * Gating: PUT butuh langganan Sync (Feature::EncryptedSync), tetapi GET dan DELETE TIDAK. Pengguna
 * yang langganannya berakhir tetap harus bisa memulihkan kunci miliknya sendiri (itu datanya, bukan
 * fitur berbayar) dan menghapus salinannya dari server.
 *
 * Semua rute hanya menyentuh baris milik `$request->user()`; tidak ada parameter id atau user_id dari
 * klien yang dipakai, jadi tidak ada jalan membaca atau menimpa salinan akun lain.
 */
class RecoveryKeyController extends Controller
{
    use DeniesWithoutSync;

    /** Membuat ulang kode pemulihan = mengganti satu-satunya salinan (atomik; user_id unik). */
    public function update(Request $request): JsonResponse
    {
        if ($denied = $this->denyWithoutSync($request)) {
            return $denied;
        }

        $data = $request->validate([
            // `bail` + batas panjang lebih dulu: string raksasa ditolak sebelum didekode.
            'wrapped_key' => [
                'bail', 'required', 'string', 'max:'.RecoveryKey::MAX_ENCODED_LENGTH,
                function (string $attribute, mixed $value, Closure $fail): void {
                    $bytes = base64_decode($value, true);
                    // base64 standar kanonik: decode strict saja masih menerima spasi/baris baru dan
                    // padding yang hilang; encode ulang harus sama persis dengan masukannya.
                    if ($bytes === false || base64_encode($bytes) !== $value) {
                        $fail('wrapped_key harus berupa base64 standar yang sah (dengan padding, tanpa spasi).');

                        return;
                    }
                    if ($bytes === '' || strlen($bytes) > RecoveryKey::MAX_BYTES) {
                        $fail('Ukuran wrapped_key setelah didekode harus 1 sampai '.RecoveryKey::MAX_BYTES.' byte.');
                    }
                },
            ],
            // Tipe wajib int sungguhan: "1", 1.0, true ditolak.
            'version' => ['required', 'integer:strict', Rule::in(RecoveryKey::KNOWN_VERSIONS)],
        ], [
            'wrapped_key.max' => 'wrapped_key terlalu besar (maksimum '.RecoveryKey::MAX_BYTES.' byte).',
            'version.in' => 'version tidak dikenal.',
        ]);

        $userId = $request->user()->id;

        try {
            RecoveryKey::upsert(
                [['user_id' => $userId, 'version' => $data['version'], 'wrapped_key' => base64_decode($data['wrapped_key'], true)]],
                ['user_id'],
                ['version', 'wrapped_key'],
            );
        } catch (QueryException $e) {
            // Pesan QueryException memuat SQL beserta nilai binding, yaitu isi blob. Jangan sampai
            // ikut ke log atau respons: catat hanya yang tidak membocorkan, jawab galat umum.
            Log::error('Gagal menyimpan salinan kunci pemulihan.', ['user_id' => $userId, 'sql_state' => $e->getCode()]);

            return response()->json(['message' => 'Gagal menyimpan kode pemulihan, coba lagi.'], 500);
        }

        // Baca ulang tanpa kolom wrapped_key: jawabannya tidak mengulang blob.
        $row = RecoveryKey::where('user_id', $userId)->first(['version', 'updated_at']);

        return response()->json(['version' => $row->version, 'updated_at' => self::iso($row)]);
    }

    public function show(Request $request): JsonResponse
    {
        $row = RecoveryKey::where('user_id', $request->user()->id)->first();

        if ($row === null) {
            // `code` adalah penanda kontrak dengan klien: hanya 404 dari aplikasi ini yang memuatnya,
            // sehingga klien bisa membedakannya dari 404 rute tak dikenal atau proxy.
            return response()->json(['message' => 'Belum ada kode pemulihan', 'code' => 'no_recovery_key'], 404);
        }

        return response()->json([
            // `wrapped_key` di-hidden pada model; hanya jalur ini yang membacanya, dan hanya untuk pemilik.
            'wrapped_key' => base64_encode($row->getRawOriginal('wrapped_key')),
            'version' => $row->version,
            'updated_at' => self::iso($row),
        ])->header('Cache-Control', 'no-store, private');
    }

    /** Idempoten: 204 juga bila belum ada. */
    public function destroy(Request $request): JsonResponse
    {
        RecoveryKey::where('user_id', $request->user()->id)->delete();

        return response()->json(status: 204);
    }

    private static function iso(RecoveryKey $row): string
    {
        return $row->updated_at->copy()->utc()->format('Y-m-d\TH:i:s\Z');
    }
}
