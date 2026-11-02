<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Api\Concerns\DeniesWithoutSync;
use App\Http\Controllers\Controller;
use App\Models\RoomMember;
use App\Models\SyncCompactionState;
use App\Models\SyncDevice;
use App\Models\SyncDeviceCursor;
use App\Models\SyncLogEntry;
use App\Models\SyncQuota;
use App\Models\SyncRecordFloor;
use App\Models\SyncScopeActivity;
use App\Models\SyncScopeWatermark;
use App\Push\RoomActivityNotifier;
use App\Support\MemberId;
use App\Support\SharedRoomOwner;
use App\Support\SyncQuotaExceeded;
use Carbon\Carbon;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;

use function Illuminate\Support\defer;

/**
 * Sinkronisasi antar-perangkat terenkripsi (Tahap 9, server-sync.md "Model konflik sinkronisasi"):
 * log append-only, server tidak pernah membaca `payload` (ciphertext buram dari aplikasi). [push]
 * menerima perubahan dari satu perangkat; [pull] mengirim ulang perubahan dari perangkat lain
 * sejak kursor terakhir. Resolusi "siapa menang" (last-write-wins dari `changed_at`) dikerjakan di
 * aplikasi setelah mendekripsi, bukan di sini — server hanya menyimpan dan meneruskan apa adanya.
 * Pull dari kursor 0 di perangkat baru sekaligus berfungsi sebagai restore dari backup.
 *
 * [pull] juga mengirim baris ruang yang dibagi (lihat RoomMember/RoomMembershipController), tidak
 * hanya baris milik akun sendiri — baris tanpa room_id (mis. Account) tetap selalu privat per akun.
 *
 * [pull] punya dua mode: tanpa `scope` (aliran gabungan, kursor global per perangkat, klien lama) dan
 * `scope=a|r` (satu cakupan per panggilan, kursor per cakupan; sync-desain.md bagian G). Kontrak klien
 * ada di docs/server-sync.md bagian "Pull per cakupan".
 *
 * Kuota per akun (push ditolak 422 `quota_exceeded`) dan kursor basi (pull dijawab 410
 * `cursor_expired`) mengikuti sync-desain.md bagian G dan J #6/#12; lihat SyncQuota dan SyncRetention.
 */
class SyncController extends Controller
{
    use DeniesWithoutSync;

    private const MAX_BATCH = 500;

    private const MAX_PAYLOAD_LENGTH = 262144; // ~256 KB per baris (base64), cukup longgar untuk satu rekaman

    private const DEFAULT_PULL_LIMIT = 200;

    private const MAX_PULL_LIMIT = 500;

    /** Sama dengan batas `entries.*.room_id` di push dan di RoomMembershipController. */
    private const MAX_ROOM_ID_LENGTH = 100;

    public function push(Request $request): JsonResponse
    {
        $data = $request->validate([
            'entries' => ['required', 'array', 'min:1', 'max:'.self::MAX_BATCH],
            'entries.*.room_id' => ['nullable', 'string', 'max:100'],
            'entries.*.record_type' => ['required', 'string', 'max:60'],
            'entries.*.record_id' => ['required', 'string', 'max:100'],
            'entries.*.device_id' => ['required', 'string', 'max:100'],
            'entries.*.payload' => ['required', 'string', 'max:'.self::MAX_PAYLOAD_LENGTH],
            'entries.*.deleted' => ['boolean'],
            'entries.*.changed_at' => ['required', 'date'],
            // Tahap 2 sync-desain.md bagian G: presisi milidetik tambahan; nullable untuk klien lama
            // (dihitung dari changed_at bila tidak dikirim).
            'entries.*.changed_at_ms' => ['nullable', 'integer', 'min:0'],
        ]);

        // Gerbang Sync (keputusan pemilik I-8, dipindah ke sini setelah body tervalidasi): baris ruang bersama
        // dibuka oleh Sync PEMILIK ruang itu, bukan Sync pengirim sendiri. Klien sungguhan tidak pernah mencampur
        // cakupan dalam satu batch (PersonalSyncCoordinator vs SyncCoordinator, lihat SyncCoordinator.push),
        // jadi batch yang (secara tak lazim) mencampur beberapa room_id atau mencampur pribadi+ruang tetap wajib
        // Sync pengirim sendiri sebagai jalan aman, sama seperti sebelumnya.
        $entryRoomIds = array_unique(array_map(static fn (array $entry) => $entry['room_id'] ?? null, $data['entries']));
        $sharedRoomId = count($entryRoomIds) === 1 ? $entryRoomIds[0] : null;
        $allowed = $sharedRoomId !== null
            ? $request->user()->entitlements()->allowsSharedRoom(SharedRoomOwner::entitlementsOf($sharedRoomId))
            : $this->denyWithoutSync($request) === null;
        if (! $allowed) {
            return response()->json(['message' => 'Butuh langganan Sync.', 'code' => 'sync_required'], 403);
        }

        $userId = $request->user()->id;

        // Push TIDAK lagi mendaftarkan non-anggota ke ruang yang sudah punya anggota (key_grant langkah 1,
        // keputusan pemilik 2026-11-03): itu jalan bekas anggota yang dikeluarkan untuk tercatat lagi.
        // Bergabung hanya lewat `join` dengan undangan terdaftar. Ruang yang belum punya anggota tetap
        // dibuat oleh pendorong pertamanya (jadi pemilik), lihat RoomMember::tambahAnggota di bawah.
        $roomIdsBatch = array_unique(array_filter(array_column($data['entries'], 'room_id')));
        foreach ($roomIdsBatch as $roomId) {
            if (! RoomMember::bolehMenulis($userId, $roomId)) {
                return response()->json([
                    'message' => 'Bukan anggota ruang ini.',
                    'code' => 'not_room_member',
                ], 403);
            }
        }

        try {
            // Satu transaksi DB per batch (Tahap 2 bagian G): gagal di tengah tidak meninggalkan separuh baris.
            [$ids, $ruangBaru] = DB::transaction(function () use ($data, $userId) {
                // Kunci penghitung kuota akun lebih dulu: push serentak satu akun antre di sini.
                $kuota = SyncQuota::kunciUntukTulis($userId);
                $barisBaru = 0;
                $bytesBaru = 0;

                $ids = [];
                // Ruang yang benar-benar menerima baris BARU (bukan ulangan idempoten atau baris ditolak):
                // hanya ini yang memicu kabar ke anggota lain.
                $ruangBaru = [];
                // Cakupan yang benar-benar menerima push per perangkat (diterima atau ulangan idempoten,
                // bukan baris yang ditolak penjaga): bahan waktu push per cakupan di Riwayat sinkron.
                $cakupanPush = [];

                // Siapa pun yang push ke sebuah room_id otomatis jadi anggotanya (idempoten) — bootstrap
                // keanggotaan tanpa butuh endpoint "buat ruang" terpisah, lihat RoomMembershipController.
                // Penambahan lewat RoomMember::tambahAnggota agar pendaftar pertama tercatat sebagai pemilik.
                $roomIds = array_unique(array_filter(array_column($data['entries'], 'room_id')));
                foreach ($roomIds as $roomId) {
                    RoomMember::tambahAnggota($userId, $roomId);
                }

                foreach ($data['entries'] as $entry) {
                    $roomId = $entry['room_id'] ?? null;
                    $changedAtMs = $entry['changed_at_ms'] ?? Carbon::parse($entry['changed_at'])->getTimestampMs();

                    // Penulis tunggal (Tahap 2 bagian B R3): hanya penulis PERTAMA baris ini (dalam ruang
                    // yang sama) boleh mengubah atau menghapusnya — anggota lain memegang kunci ruang yang
                    // sama jadi `device_id`/isi payload bisa dipalsukan; id baris tidak bisa.
                    if ($roomId !== null) {
                        $firstAuthor = SyncLogEntry::where('room_id', $roomId)
                            ->where('record_id', $entry['record_id'])
                            ->orderBy('id')
                            ->value('user_id');
                        if ($firstAuthor !== null && $firstAuthor !== $userId) {
                            Log::info('sync push: baris ditolak, bukan penulis pertama', [
                                'room_id' => $roomId,
                                'record_id' => $entry['record_id'],
                                'first_author' => $firstAuthor,
                                'rejected_user' => $userId,
                            ]);

                            continue;
                        }
                    }

                    // Lantai per rekaman (Tahap 9): tombstone rekaman ini sudah dihapus fisik oleh
                    // sync:compact. Versi dengan cap waktu <= tombstone itu adalah versi lama yang
                    // terlambat; menyimpannya akan menghidupkan kembali rekaman yang sudah dihapus karena
                    // tidak ada lagi tombstone yang mengalahkannya. Ditolak diam-diam seperti baris bukan
                    // penulis pertama (klien tidak memakai `ids` per baris). Versi yang lebih baru dari
                    // lantai tetap diterima: itu memang edit sesudah hapus (last-write-wins).
                    $lantai = SyncRecordFloor::lantai($userId, $roomId, $entry['record_id']);
                    if ($lantai !== null && $changedAtMs <= $lantai) {
                        Log::info('sync push: baris ditolak, lebih lama dari lantai kompaksi', [
                            'room_id' => $roomId,
                            'record_id' => $entry['record_id'],
                            'user_id' => $userId,
                        ]);

                        continue;
                    }

                    // Idempoten: push yang diulang (ack hilang di klien) menemukan baris yang sama dan
                    // mengembalikan id-nya, tidak menggandakan baris.
                    $existing = SyncLogEntry::where('user_id', $userId)
                        ->where('device_id', $entry['device_id'])
                        ->where('record_id', $entry['record_id'])
                        ->where('changed_at_ms', $changedAtMs)
                        ->where('deleted', $entry['deleted'] ?? false)
                        ->first();
                    if ($existing !== null) {
                        $ids[] = $existing->id;
                        $cakupanPush[$entry['device_id']][$roomId ?? ''] = $roomId;

                        continue;
                    }

                    $ids[] = SyncLogEntry::create([
                        'user_id' => $userId,
                        'room_id' => $roomId,
                        'record_type' => $entry['record_type'],
                        'record_id' => $entry['record_id'],
                        'device_id' => $entry['device_id'],
                        'payload' => $entry['payload'],
                        'deleted' => $entry['deleted'] ?? false,
                        'changed_at' => $entry['changed_at'],
                        'changed_at_ms' => $changedAtMs,
                    ])->id;

                    $cakupanPush[$entry['device_id']][$roomId ?? ''] = $roomId;
                    $barisBaru++;
                    $bytesBaru += strlen($entry['payload']);
                    if ($roomId !== null) {
                        $ruangBaru[$roomId] = true;
                    }
                }

                // Waktu push per cakupan (metadata saja). Di dalam transaksi: batch yang melewati kuota
                // di-rollback bersama baris-barisnya, jadi tidak ada catatan push untuk push yang gagal.
                foreach ($cakupanPush as $deviceId => $ruang) {
                    SyncScopeActivity::catatPush($userId, (string) $deviceId, array_values($ruang));
                }

                // Melempar bila melewati kuota: seluruh batch di-rollback, tidak ada yang tersimpan.
                $kuota->tambahPemakaian($barisBaru, $bytesBaru);

                return [$ids, array_map('strval', array_keys($ruangBaru))];
            });
        } catch (SyncQuotaExceeded) {
            return response()->json([
                'message' => 'Penyimpanan sinkron akun ini sudah penuh. Data di perangkat tetap aman dan tidak ada yang dihapus; sinkron baru dijeda sampai ada ruang tersedia.',
                'code' => 'quota_exceeded',
            ], 422);
        }

        // Kabar generik "ada catatan baru" ke anggota LAIN ruang itu (bukan pencatatnya), hanya setelah
        // transaksi berhasil. Isinya hanya roomId + kind (App\Push\SyncNotificationPayload), tanpa
        // nominal, nama, atau catatan. `defer`: dijalankan setelah jawaban terkirim (PHP-FPM), bukan
        // lewat antrean karena server belum punya queue worker; galatnya ditelan (`rescue`) sehingga
        // FCM yang lambat atau gagal tidak pernah memengaruhi sync.
        if ($ruangBaru !== []) {
            defer(fn () => app(RoomActivityNotifier::class)->notifyNewRecords($userId, $ruangBaru));
        }

        // Semua baris ditolak (bukan penulis pertama): cursor tetap baris terakhir milik user ini,
        // bukan 0 — klien lama membaca ini apa adanya (lihat catatan SyncCoordinator: tidak dipakai
        // untuk apa pun selain ditampilkan).
        $cursor = $ids === [] ? (SyncLogEntry::where('user_id', $userId)->max('id') ?? 0) : max($ids);

        return response()->json([
            'ids' => $ids,
            'cursor' => $cursor,
            'server_time_ms' => now()->getTimestampMs(),
        ]);
    }

    /**
     * Tarik perubahan. Dua mode:
     *
     * - TANPA `scope` (klien lama, perilaku tidak berubah): satu aliran berisi baris milik akun sendiri
     *   plus semua ruang yang diikuti, dengan satu kursor global per perangkat (`sync_devices`).
     * - `scope=a` (pribadi: `user_id = saya AND room_id IS NULL`) atau `scope=r&room_id=X` (satu ruang
     *   bersama, hanya untuk anggota aktif), dengan satu kursor PER CAKUPAN per perangkat
     *   (`sync_device_cursors`). Kursor `after` adalah id baris log global yang dipakai bersama semua
     *   cakupan, tetapi nilainya hanya bermakna untuk cakupan yang menariknya: jangan dibawa ke cakupan lain.
     *
     * Pull per cakupan TIDAK pernah mendaftarkan pemanggilnya sebagai anggota (beda dengan push); bukan
     * anggota dijawab 403 `not_room_member`.
     */
    public function pull(Request $request): JsonResponse
    {
        // Gerbang Sync: untuk cakupan ruang bersama (`scope=r`) dipindah ke bawah, SETELAH scope diketahui
        // (keputusan pemilik I-8: Sync PEMILIK ruang itu yang menentukan, bukan Sync peminta sendiri). Cakupan
        // pribadi dan pull lama (tanpa `scope`) tetap memeriksa Sync peminta sendiri di sini, tanpa berubah.
        if ($request->query('scope') !== 'r' && ($denied = $this->denyWithoutSync($request))) {
            return $denied;
        }

        // device_id WAJIB (Tahap 9, pengerasan sebelum rilis): kursor per perangkat di `sync_devices` adalah
        // yang menahan kompaksi tombstone (SyncRetention). Perangkat yang pull tanpa device_id tidak
        // tercatat, jadi tombstone bisa dihapus fisik sebelum perangkat itu melewatinya, dan ia tidak
        // pernah tahu rekaman itu sudah dihapus. Klien Android sudah selalu mengirimnya (SyncRemote.pull).
        // Jawaban 422 punya `code` sendiri supaya bisa dibedakan dari galat validasi lain.
        if (! is_string($request->query('device_id')) || trim($request->query('device_id')) === '') {
            return response()->json([
                'message' => 'Parameter device_id wajib diisi untuk menarik data sinkron. Perbarui aplikasi Rizqflow.',
                'code' => 'device_id_required',
                'errors' => ['device_id' => ['Parameter device_id wajib diisi.']],
            ], 422);
        }

        $validated = $request->validate([
            'after' => ['integer', 'min:0'],
            'limit' => ['integer', 'min:1', 'max:'.self::MAX_PULL_LIMIT],
            'device_id' => ['required', 'string', 'max:100'],
        ]);
        // Laravel tidak mengubah tipe nilai yang sudah lolos validasi 'integer'; query string selalu
        // string, dan `$entries->count() === $limit` di bawah butuh int, bukan '2' !== 2.
        $after = (int) ($validated['after'] ?? 0);
        $limit = (int) ($validated['limit'] ?? self::DEFAULT_PULL_LIMIT);
        $deviceId = $validated['device_id'];

        $userId = $request->user()->id;

        // `$scoped` false = pull lama tanpa `scope`. Bila true: `$roomId` null = cakupan pribadi (`a`),
        // selain itu id ruang bersama (`r`). `room_id` tanpa `scope` ditolak (ambigu), begitu juga
        // `scope=a` dengan `room_id`.
        $scoped = $request->query('scope') !== null || $request->query('room_id') !== null;
        $roomId = null;
        if ($scoped) {
            $scope = $request->query('scope');
            $roomParam = $request->query('room_id');
            $scopeRusak = ! in_array($scope, ['a', 'r'], true)
                || ($scope === 'a' && $roomParam !== null)
                || ($scope === 'r' && (! is_string($roomParam) || $roomParam === ''));
            if ($scopeRusak) {
                return response()->json([
                    'message' => 'Parameter scope harus "a" (pribadi, tanpa room_id) atau "r" (ruang bersama, dengan room_id).',
                    'code' => 'invalid_scope',
                    'errors' => ['scope' => ['scope tidak sah.']],
                ], 422);
            }
            if ($scope === 'r') {
                if (mb_strlen($roomParam) > self::MAX_ROOM_ID_LENGTH) {
                    return response()->json([
                        'message' => 'Id ruang tidak sah (1 sampai '.self::MAX_ROOM_ID_LENGTH.' karakter).',
                        'code' => 'invalid_room_id',
                    ], 422);
                }
                $roomId = $roomParam;

                // Hanya anggota aktif. Server menghapus baris keanggotaan saat anggota keluar atau dikeluarkan,
                // jadi keduanya tampak sama: 403 `not_room_member` (kode `removed_from_room` di sync-desain.md
                // bagian G baru bisa dibedakan setelah status `removed` ada, yang menunggu keputusan pemilik).
                // Sengaja TIDAK memanggil RoomMember::tambahAnggota: pull tidak boleh mendaftarkan siapa pun.
                if (! RoomMember::where('room_id', $roomId)->where('user_id', $userId)->exists()) {
                    return response()->json([
                        'message' => 'Kamu bukan anggota ruang ini.',
                        'code' => 'not_room_member',
                    ], 403);
                }

                // Gerbang Sync untuk ruang ini (keputusan pemilik I-8, lihat catatan di atas method ini):
                // dibuka oleh Sync pemilik ruang, bukan Sync peminta sendiri.
                $allowed = $request->user()->entitlements()->allowsSharedRoom(SharedRoomOwner::entitlementsOf($roomId));
                if (! $allowed) {
                    return response()->json(['message' => 'Butuh langganan Sync.', 'code' => 'sync_required'], 403);
                }
            }
        }

        // Kursor di bawah watermark kompaksi: sebagian tombstone sudah dihapus fisik setelah kursor ini,
        // jadi klien harus pull ulang dari 0 dan menggabung (sync-desain.md bagian J #6). after=0 selalu
        // diizinkan: itu memang sinkron ulang penuh. Pull per cakupan memakai watermark cakupannya sendiri
        // (pribadi akun ini, atau ruang itu); pull lama memakai watermark global.
        $watermark = $scoped
            ? SyncScopeWatermark::untuk($userId, $roomId)
            : SyncCompactionState::tombstoneWatermark();
        if ($after > 0 && $after < $watermark) {
            return response()->json([
                'message' => 'Kursor sinkron sudah basi karena riwayat dirapikan. Sinkron ulang dari awal (after=0).',
                'code' => 'cursor_expired',
            ], 410);
        }

        // Hanya pull lama yang membutuhkan daftar ruang yang diikuti (aliran gabungan).
        $sharedRoomIds = $scoped ? collect() : RoomMember::where('user_id', $userId)->pluck('room_id');

        if (! $scoped) {
            $query = SyncLogEntry::where(function ($query) use ($userId, $sharedRoomIds) {
                $query->where('user_id', $userId)
                    ->orWhere(function ($query) use ($sharedRoomIds) {
                        $query->whereNotNull('room_id')->whereIn('room_id', $sharedRoomIds);
                    });
            });
        } elseif ($roomId === null) {
            $query = SyncLogEntry::where('user_id', $userId)->whereNull('room_id');
        } else {
            $query = SyncLogEntry::where('room_id', $roomId);
        }

        $entries = $query
            ->where('id', '>', $after)
            ->orderBy('id')
            ->limit($limit)
            ->get(['id', 'room_id', 'record_type', 'record_id', 'device_id', 'payload', 'deleted', 'changed_at', 'changed_at_ms', 'user_id']);

        $hasMore = $entries->count() === $limit;
        $nextAfter = $entries->last()?->id ?? $after;

        // Halaman terakhir: seluruh sisa baris yang terlihat sudah dibaca, dan tombstone di bawah watermark
        // sudah tidak ada. Kursor dimajukan ke watermark; tanpa ini cakupan yang baris terakhirnya lebih kecil
        // dari watermark (mis. tombstone terakhirnya baru dikompaksi) terjebak: kursornya selalu di bawah
        // watermark, jadi tiap pull dijawab 410 dan tarik ulang dari 0 tidak pernah menyelesaikannya.
        if (! $hasMore) {
            $nextAfter = max($nextAfter, $watermark);
        }

        if ($scoped) {
            SyncDeviceCursor::catat($userId, $deviceId, $roomId, $nextAfter);
            SyncScopeActivity::catatPull($userId, $deviceId, [$roomId]);
        } else {
            SyncDevice::updateOrCreate(
                ['user_id' => $userId, 'device_id' => $deviceId],
                ['after_id' => $nextAfter, 'last_seen_at' => now()],
            );

            // Waktu pull per cakupan untuk Riwayat sinkron: pull tanpa `scope` mencakup data pribadi dan
            // semua ruang yang diikuti, jadi semuanya dicatat (klien lama terisi tanpa perubahan klien).
            SyncScopeActivity::catatPull($userId, $deviceId, [null, ...$sharedRoomIds->all()]);
        }

        $jawaban = [
            'entries' => $entries->map(fn (SyncLogEntry $entry) => [
                'id' => $entry->id,
                'room_id' => $entry->room_id,
                'record_type' => $entry->record_type,
                'record_id' => $entry->record_id,
                'device_id' => $entry->device_id,
                'payload' => $entry->payload,
                'deleted' => $entry->deleted,
                'changed_at' => $entry->changed_at,
                'changed_at_ms' => $entry->changed_at_ms,
                // Penulis buram (Tahap 2 bagian B R3): id anggota (user_id) tidak pernah dikirim mentah;
                // hash per ruang supaya tidak bisa dikorelasikan ke akun lain tanpa tahu room_id-nya.
                'author' => MemberId::dari($entry->room_id, $entry->user_id),
            ]),
            'next_after' => $nextAfter,
            'has_more' => $hasMore,
            'server_time_ms' => now()->getTimestampMs(),
        ];

        // Gema cakupan yang DILAYANI. Server lama mengabaikan `scope` dan menjawab aliran gabungan tanpa
        // bidang ini, jadi klien yang meminta `scope` WAJIB memeriksa bidang ini dan menganggap
        // jawaban tanpa `scope` sebagai server lama (jangan menerapkan baris cakupan lain).
        if ($scoped) {
            $jawaban['scope'] = $roomId === null ? 'a' : 'r';
            $jawaban['room_id'] = $roomId;
        }

        return response()->json($jawaban);
    }
}
