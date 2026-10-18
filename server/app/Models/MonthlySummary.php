<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class MonthlySummary extends Model
{
    use HasFactory;

    protected $fillable = ['user_id', 'month', 'income_minor', 'unallocated_minor'];

    protected $casts = [
        'month' => 'date',
    ];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }
}
