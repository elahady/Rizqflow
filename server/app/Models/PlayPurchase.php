<?php

namespace App\Models;

use App\Play\PlayProduct;
use App\Play\PlayVerification;
use App\Play\PurchaseStatus;
use App\Support\Plan;
use Carbon\CarbonInterface;
use Illuminate\Contracts\Encryption\DecryptException;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Support\Facades\Crypt;

/**
 * Satu token pembelian Google Play yang sudah diverifikasi dan dikaitkan ke satu akun (Tahap 9).
 * Lihat migrasi `create_play_purchases_table` untuk aturan dan alasannya.
 *
 * @property-read string $purchase_token Token asli (terenkripsi di database); jangan pernah dicatat atau dikirim.
 */
class PlayPurchase extends Model
{
    /** Panjang maksimum token pembelian yang diterima (klaim dan notifikasi); token Play jauh lebih pendek. */
    public const MAX_TOKEN_LENGTH = 4096;

    /** Langganan dianggap basi (diverifikasi ulang walau expires_at masih jauh) setelah sekian hari. */
    public const SUBSCRIPTION_STALE_DAYS = 3;

    /** Produk sekali beli diverifikasi ulang tiap sekian hari, sebagai jaring pengaman bila notifikasi pengembalian dana terlewat. */
    public const ONE_TIME_STALE_DAYS = 30;

    protected $fillable = [
        'user_id',
        'plan',
        'product_id',
        'token_hash',
        'purchase_token',
        'status',
        'expires_at',
        'auto_renewing',
        'acknowledged',
        'last_verified_at',
        'last_checked_at',
    ];

    protected $hidden = [
        'token_hash',
        'purchase_token',
    ];

    protected function casts(): array
    {
        return [
            'plan' => Plan::class,
            'status' => PurchaseStatus::class,
            'purchase_token' => 'encrypted',
            'expires_at' => 'datetime',
            'auto_renewing' => 'boolean',
            'acknowledged' => 'boolean',
            'last_verified_at' => 'datetime',
            'last_checked_at' => 'datetime',
        ];
    }

    public static function hashToken(string $token): string
    {
        return hash('sha256', $token);
    }

    public static function findByToken(string $token): ?self
    {
        return self::where('token_hash', self::hashToken($token))->first();
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    /** Berhak pada waktu $now (bawaan: sekarang), berdasarkan data tersimpan, bukan panggilan baru ke Play. */
    public function entitles(?CarbonInterface $now = null): bool
    {
        return $this->status->entitles($this->expires_at, $now ?? now());
    }

    /**
     * Tulis ulang token terenkripsi dengan APP_KEY sekarang bila yang tersimpan berbeda atau tak
     * terbaca (mis. APP_KEY pernah berganti). Lewat query langsung, bukan save(): save() Eloquent
     * mendekripsi nilai asli untuk membandingkannya, dan itu melempar bila tak terbaca.
     */
    public function rewriteToken(string $token): void
    {
        try {
            if ($this->purchase_token === $token) {
                return;
            }
        } catch (DecryptException) {
            // tak terbaca: tulis ulang di bawah
        }

        self::whereKey($this->getKey())->update(['purchase_token' => Crypt::encryptString($token)]);
        $this->refresh();
    }

    /** Terapkan hasil verifikasi baru ke baris ini dan simpan. Pemanggil memanggil PlanSynchronizer sesudahnya. */
    public function applyVerification(PlayVerification $verification, ?CarbonInterface $now = null): void
    {
        $this->forceFill([
            'status' => $verification->status,
            'expires_at' => $verification->expiresAt,
            'auto_renewing' => $verification->autoRenewing,
            'acknowledged' => $verification->acknowledged,
            'last_verified_at' => $now ?? now(),
        ])->save();
    }

    /**
     * Catat bahwa token ini sudah diganti Play dengan token baru (ganti base plan, atau
     * berlangganan lagi dari Play Store setelah sepenuhnya kedaluwarsa; Tahap 9, "Ikuti token
     * langganan pengganti ke pemilik baris lama"). Murni jejak audit: PlayPurchaseChainResolver
     * tidak pernah membaca kolom ini untuk memutuskan pemilik mana pun, selalu lewat token_hash
     * baris baru yang ditemukan dari predecessorToken jawaban Play.
     */
    public function markSupersededBy(string $newTokenHash): void
    {
        self::whereKey($this->getKey())->update(['superseded_by_token_hash' => $newTokenHash]);
        $this->refresh();
    }

    /**
     * Catat bahwa `play:reconcile` baru saja mencoba memverifikasi ulang baris ini, apa pun hasilnya.
     * Lewat query langsung, bukan save(): tidak menyentuh token terenkripsi (yang bisa tak terbaca
     * setelah APP_KEY berganti) dan tidak mengubah updated_at.
     */
    public function markChecked(?CarbonInterface $now = null): void
    {
        self::whereKey($this->getKey())->update(['last_checked_at' => $now ?? now()]);
    }

    /**
     * Urutan giliran `play:reconcile`: baris yang belum pernah dicoba lebih dulu, lalu yang paling lama
     * tidak dicoba, lalu yang paling lama tidak terverifikasi. Tanpa ini, baris yang terus dilewati
     * (Play menjawab tak dikenal) tetap di depan antrean dan bisa menutup baris lain bila jumlahnya
     * mencapai batas per jalan.
     *
     * @param  Builder<self>  $query
     * @return Builder<self>
     */
    public function scopeInReconcileOrder(Builder $query): Builder
    {
        return $query
            ->orderByRaw('last_checked_at is not null')
            ->orderBy('last_checked_at')
            ->orderBy('last_verified_at');
    }

    /**
     * Baris yang perlu diverifikasi ulang oleh `play:reconcile` (hanya yang belum final):
     * - langganan yang sudah atau hampir (sehari lagi) lewat expires_at, belum punya expires_at,
     *   atau terakhir diverifikasi lebih dari SUBSCRIPTION_STALE_DAYS hari lalu;
     * - pembelian tertunda yang terakhir diperiksa lebih dari sehari lalu;
     * - produk sekali beli yang terakhir diverifikasi lebih dari ONE_TIME_STALE_DAYS hari lalu.
     *
     * @param  Builder<self>  $query
     * @return Builder<self>
     */
    public function scopeDueForReconcile(Builder $query, CarbonInterface $now): Builder
    {
        $subscriptionIds = PlayProduct::subscriptionIds();

        return $query
            ->whereNotIn('status', [PurchaseStatus::Expired->value, PurchaseStatus::Refunded->value])
            ->where(function (Builder $query) use ($now, $subscriptionIds) {
                $query
                    ->where(function (Builder $query) use ($now, $subscriptionIds) {
                        $query->whereIn('product_id', $subscriptionIds)
                            ->where(function (Builder $query) use ($now) {
                                $query->whereNull('expires_at')
                                    ->orWhere('expires_at', '<=', $now->copy()->addDay())
                                    ->orWhere('last_verified_at', '<=', $now->copy()->subDays(self::SUBSCRIPTION_STALE_DAYS));
                            });
                    })
                    ->orWhere(function (Builder $query) use ($now) {
                        $query->where('status', PurchaseStatus::Pending->value)
                            ->where('last_verified_at', '<=', $now->copy()->subDay());
                    })
                    ->orWhere(function (Builder $query) use ($now, $subscriptionIds) {
                        $query->whereNotIn('product_id', $subscriptionIds)
                            ->where('last_verified_at', '<=', $now->copy()->subDays(self::ONE_TIME_STALE_DAYS));
                    });
            });
    }
}
