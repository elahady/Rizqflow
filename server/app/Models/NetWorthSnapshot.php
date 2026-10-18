<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class NetWorthSnapshot extends Model
{
    use HasFactory;

    protected $fillable = ['user_id', 'snapshot_date', 'accounts_minor', 'zakat_wealth_minor', 'total_minor'];

    protected $casts = [
        'snapshot_date' => 'date',
    ];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }
}
