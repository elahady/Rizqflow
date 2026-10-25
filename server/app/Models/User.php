<?php

namespace App\Models;

// use Illuminate\Contracts\Auth\MustVerifyEmail;
use App\Support\Entitlements;
use App\Support\Plan;
use App\Support\ThemePalette;
use Database\Factories\UserFactory;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Database\Eloquent\Relations\HasOne;
use Illuminate\Foundation\Auth\User as Authenticatable;
use Illuminate\Notifications\Notifiable;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Laravel\Sanctum\HasApiTokens;

class User extends Authenticatable
{
    /** @use HasFactory<UserFactory> */
    use HasApiTokens, HasFactory, Notifiable;

    /**
     * The attributes that are mass assignable.
     *
     * @var list<string>
     */
    protected $fillable = [
        'name',
        'email',
        'google_sub',
        'password',
        'plans',
        'allocation_mode',
        'theme_palette',
    ];

    /**
     * The attributes that should be hidden for serialization.
     *
     * @var list<string>
     */
    protected $hidden = [
        'password',
        'remember_token',
        // Relasi salinan kunci pemulihan tidak pernah ikut serialisasi User walau sudah dimuat.
        'recoveryKey',
    ];

    /**
     * Get the attributes that should be cast.
     *
     * @return array<string, string>
     */
    protected function casts(): array
    {
        return [
            'email_verified_at' => 'datetime',
            'password' => 'hashed',
            'plans' => 'array',
        ];
    }

    /** Satu-satunya tempat yang menjawab "boleh atau tidak" — lihat App\Support\Entitlements. */
    public function entitlements(): Entitlements
    {
        $plans = array_map(fn (string $value) => Plan::from($value), $this->plans ?? []);

        return new Entitlements($plans);
    }

    /** Palet yang dipilih akun (bisa palet Pro yang sudah tidak berlaku; lihat [effectiveThemePalette]). */
    public function themePalette(): ThemePalette
    {
        return ThemePalette::tryFrom((string) $this->theme_palette) ?? ThemePalette::Sage;
    }

    /** Palet yang tampil sekarang: pilihan tersimpan, kembali ke Sage bila paket tidak membukanya. */
    public function effectiveThemePalette(): ThemePalette
    {
        return $this->themePalette()->effective($this->entitlements());
    }

    /** Akun yang dibuat lewat Google belum punya kata sandi sampai menetapkannya di Profil. */
    public function hasPassword(): bool
    {
        return $this->password !== null;
    }

    public function hasVerifiedEmail(): bool
    {
        return $this->email_verified_at !== null;
    }

    /**
     * Putus sesi web akun ini (dipakai saat kata sandi diatur ulang atau diubah): kunci "ingat saya"
     * diganti dan baris sesi di database dihapus. $exceptSessionId menjaga sesi yang sedang dipakai.
     */
    public function revokeWebSessions(?string $exceptSessionId = null): void
    {
        $this->forceFill(['remember_token' => Str::random(60)])->save();

        if (config('session.driver') === 'database') {
            DB::table(config('session.table', 'sessions'))
                ->where('user_id', $this->id)
                ->when($exceptSessionId, fn ($q) => $q->where('id', '!=', $exceptSessionId))
                ->delete();
        }
    }

    /** Salinan kunci akun terbungkus kode pemulihan (blob buram, Tahap 13); lihat RecoveryKey. */
    public function recoveryKey(): HasOne
    {
        return $this->hasOne(RecoveryKey::class);
    }

    public function emailCodes(): HasMany
    {
        return $this->hasMany(EmailCode::class);
    }

    public function isPro(): bool
    {
        return in_array(Plan::Pro->value, $this->plans ?? [], true);
    }

    public function rooms(): HasMany
    {
        return $this->hasMany(Room::class)->orderBy('sort_order');
    }

    public function accounts(): HasMany
    {
        return $this->hasMany(Account::class)->orderBy('sort_order');
    }

    public function quickEntries(): HasMany
    {
        return $this->hasMany(QuickEntry::class)->orderBy('id');
    }

    public function transactions(): HasMany
    {
        return $this->hasMany(Transaction::class)->orderByDesc('occurred_on');
    }

    public function recurringRules(): HasMany
    {
        return $this->hasMany(RecurringRule::class);
    }

    public function bills(): HasMany
    {
        return $this->hasMany(Bill::class);
    }

    public function debts(): HasMany
    {
        return $this->hasMany(Debt::class);
    }

    public function zakatProfiles(): HasMany
    {
        return $this->hasMany(ZakatProfile::class);
    }

    public function netWorthSnapshots(): HasMany
    {
        return $this->hasMany(NetWorthSnapshot::class);
    }

    public function syncLogEntries(): HasMany
    {
        return $this->hasMany(SyncLogEntry::class);
    }

    public function roomMemberships(): HasMany
    {
        return $this->hasMany(RoomMember::class);
    }

    /** Pembelian Google Play yang sudah diverifikasi dan diklaim akun ini (sumber users.plans untuk Pro/Sync). */
    public function playPurchases(): HasMany
    {
        return $this->hasMany(PlayPurchase::class);
    }
}
