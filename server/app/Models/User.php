<?php

namespace App\Models;

// use Illuminate\Contracts\Auth\MustVerifyEmail;
use App\Support\Entitlements;
use App\Support\Plan;
use Database\Factories\UserFactory;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Foundation\Auth\User as Authenticatable;
use Illuminate\Notifications\Notifiable;
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
    ];

    /**
     * The attributes that should be hidden for serialization.
     *
     * @var list<string>
     */
    protected $hidden = [
        'password',
        'remember_token',
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

    public function monthlySummaries(): HasMany
    {
        return $this->hasMany(MonthlySummary::class);
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
}
