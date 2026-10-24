<?php

namespace App\Auth;

use App\Models\User;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\DB;
use Laravel\Sanctum\NewAccessToken;
use Laravel\Sanctum\PersonalAccessToken;

/**
 * Siklus hidup token API Android (Tahap 12: token kedaluwarsa dan bisa dicabut, tidak meniru token
 * permanen Al-Kaukaba). SATU-SATUNYA tempat yang menghitung masa berlaku token dan menerbitkannya,
 * dipakai masuk (`Api\AccountController::login`) dan refresh (`::refresh`).
 *
 * Masa berlaku = `API_TOKEN_TTL_DAYS` hari (bawaan 90), dicatat di kolom `expires_at` tiap token.
 * `config/sanctum.php` memakai [parseTtlDays] yang sama untuk `expiration` (menit) sebagai jaring
 * pengaman bagi token tanpa `expires_at` (token terbitan sebelum fitur ini): Guard Sanctum menolak token
 * yang `created_at`-nya lebih tua dari itu.
 */
class ApiTokenService
{
    public const DEFAULT_TTL_DAYS = 90;

    /** Plafon token aktif per akun (satu per perangkat); masuk dari perangkat ke-21 membuang token terlama. */
    public const MAX_TOKENS_PER_ACCOUNT = 20;

    /** Percobaan transaksi `issueForDevice` bila MySQL membatalkannya karena deadlock. */
    public const TRANSACTION_ATTEMPTS = 3;

    /**
     * Batas atas yang masuk akal. Selain menangkap salah ketik, kolom `expires_at` bertipe TIMESTAMP di
     * MySQL tidak menampung tanggal setelah 2038, jadi nilai yang jauh melewatinya menggagalkan
     * penerbitan token. Nilai di atas batas dianggap tak sah dan jatuh ke bawaan.
     *
     * UTANG: 1825 hari (5 tahun) hanya aman sampai sekitar 2033-01-20; sesudah itu hari ini + 1825
     * hari melewati batas TIMESTAMP MySQL (2038-01-19 03:14:07 UTC) dan batas ini ikut usang. Perbaikan:
     * batas dinamis (mis. min(1825, hari tersisa sampai 2038-01-19)) atau migrasi `expires_at`
     * menjadi DATETIME. Dicatat juga di DEPLOY.md.
     */
    public const MAX_TTL_DAYS = 1825;

    /**
     * Ubah nilai mentah (mis. env `API_TOKEN_TTL_DAYS`) jadi jumlah hari. Hanya bilangan bulat positif
     * 1..[MAX_TTL_DAYS] yang sah; selain itu (kosong, huruf, pecahan, nol, negatif, terlalu besar)
     * jatuh ke [DEFAULT_TTL_DAYS]. Murni, tanpa membaca env/config, supaya aman dipanggil dari berkas config.
     */
    public static function parseTtlDays(mixed $raw): int
    {
        if (! is_int($raw) && ! is_string($raw)) {
            return self::DEFAULT_TTL_DAYS;
        }

        $days = filter_var($raw, FILTER_VALIDATE_INT, ['options' => ['min_range' => 1, 'max_range' => self::MAX_TTL_DAYS]]);

        return $days === false ? self::DEFAULT_TTL_DAYS : $days;
    }

    /** Masa berlaku token baru, dalam hari (dibaca dari `rizqflow.api_token_ttl_days`). */
    public function ttlDays(): int
    {
        return self::parseTtlDays(config('rizqflow.api_token_ttl_days'));
    }

    /**
     * Saat token yang diterbitkan sekarang kedaluwarsa. Dibulatkan ke detik penuh supaya nilai yang
     * dijawab ke klien sama persis dengan yang tersimpan di kolom `expires_at` (tanpa pecahan detik).
     */
    public function expiresAt(): Carbon
    {
        return now()->addDays($this->ttlDays())->startOfSecond();
    }

    /** Terbitkan token baru dengan masa berlaku penuh. */
    public function issue(User $user, string $name, array $abilities = ['*']): NewAccessToken
    {
        return $user->createToken($name, $abilities, $this->expiresAt());
    }

    /**
     * Terbitkan token untuk satu perangkat: token lama dengan nama yang sama dicabut dulu, supaya masuk
     * ulang di perangkat yang sama tidak menumpuk token. Jumlah token per akun dibatasi
     * [MAX_TOKENS_PER_ACCOUNT]: bila penuh, token yang paling lama tak dipakai dibuang (lihat
     * [evictOldest]). Satu transaksi: kalau penerbitan gagal, token lama tidak ikut hilang.
     *
     * Percobaan ulang ([TRANSACTION_ATTEMPTS]) dan kunci baris pengguna ada karena di InnoDB dua login
     * bersamaan satu akun bisa saling mengunci (gap lock pada DELETE lalu INSERT) dan salah satunya
     * dibatalkan MySQL dengan kode 1213 (deadlock); `DB::transaction` mengulang transaksi yang
     * kalah. Kunci `FOR UPDATE` pada baris pengguna membuat login bersamaan satu akun berurutan, jadi
     * kasus itu semestinya jarang. CATATAN: keduanya TIDAK bisa dibuktikan di SQLite (satu penulis,
     * tidak ada gap lock, `FOR UPDATE` diabaikan); tes hanya membuktikan bahwa kegagalan serupa
     * deadlock diulang, bukan bahwa MySQL tak lagi menemui deadlock. Belum diuji di MySQL sungguhan.
     */
    public function issueForDevice(User $user, string $name): NewAccessToken
    {
        return DB::transaction(function () use ($user, $name) {
            User::query()->whereKey($user->getKey())->lockForUpdate()->first();

            $user->tokens()->where('name', $name)->delete();
            $this->evictOldest($user, self::MAX_TOKENS_PER_ACCOUNT - 1);

            return $this->issue($user, $name);
        }, self::TRANSACTION_ATTEMPTS);
    }

    /**
     * Sisakan paling banyak $keep token milik $user dengan membuang yang paling lama tak dipakai
     * (`last_used_at`, atau `created_at` bila belum pernah dipakai; seri diputus id terkecil).
     */
    private function evictOldest(User $user, int $keep): void
    {
        $excess = $user->tokens()->count() - $keep;
        if ($excess <= 0) {
            return;
        }

        $ids = $user->tokens()
            ->orderByRaw('COALESCE(last_used_at, created_at) asc')
            ->orderBy('id')
            ->limit($excess)
            ->pluck('id');

        $user->tokens()->whereKey($ids)->delete();
    }

    /**
     * Putar token: $current dicabut dan token baru (nama dan abilities sama) diterbitkan dengan masa
     * berlaku penuh, dalam satu transaksi. Mengembalikan null bila $current sudah tidak ada lagi.
     *
     * Aman terhadap dua refresh bersamaan dengan token yang sama: pencabutan adalah DELETE atas id
     * token, dan hanya satu dari dua pemanggil yang melihat tepat satu baris terhapus. Pemanggil kalah
     * (0 baris, token sudah dicabut pemenang) tidak menerbitkan apa pun dan mendapat null. Penerbitan
     * satu transaksi dengan DELETE, jadi kalau penerbitan gagal, token lama tetap utuh.
     */
    public function rotate(User $user, PersonalAccessToken $current): ?NewAccessToken
    {
        return DB::transaction(function () use ($user, $current) {
            if ($user->tokens()->whereKey($current->getKey())->delete() === 0) {
                return null;
            }

            return $this->issue($user, $current->name, $current->abilities ?? ['*']);
        });
    }

    /**
     * Bentuk jawaban token ke klien: `token` dan `expires_at` (ISO-8601 UTC detik penuh, mis.
     * `2027-01-22T10:00:00Z`). Dipakai masuk dan refresh supaya formatnya satu.
     *
     * @return array{token: string, expires_at: string|null}
     */
    public static function payload(NewAccessToken $token): array
    {
        return [
            'token' => $token->plainTextToken,
            'expires_at' => $token->accessToken->expires_at?->copy()->utc()->format('Y-m-d\TH:i:s\Z'),
        ];
    }
}
