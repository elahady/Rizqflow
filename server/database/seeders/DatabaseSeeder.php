<?php

namespace Database\Seeders;

use App\Models\MonthlySummary;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Models\ZakatProfile;
use Illuminate\Database\Console\Seeds\WithoutModelEvents;
use Illuminate\Database\Seeder;
use Illuminate\Support\Facades\Hash;

/**
 * Data contoh untuk mockup web Rizqflow (scaffold tahap awal, bukan data sungguhan).
 * Nomor-nomor di sini sengaja mengikuti contoh di docs/ui-flow.md (S05, S07, S11, S16)
 * supaya konsisten dengan dokumen produk — bukan saran keuangan.
 */
class DatabaseSeeder extends Seeder
{
    use WithoutModelEvents;

    public function run(): void
    {
        $user = User::factory()->create([
            'name' => 'Roziq',
            'email' => 'demo@rizqflow.test',
            'password' => Hash::make('password'),
        ]);

        $memberi = Room::create([
            'user_id' => $user->id,
            'name' => 'Memberi',
            'icon' => 'memberi',
            'type' => 'menunaikan',
            'slot' => 1,
            'allocation_percent' => 10,
            'budget_minor' => 500_000,
            'used_minor' => 200_000,
            'sort_order' => 1,
        ]);

        Room::create([
            'user_id' => $user->id,
            'name' => 'Diri',
            'icon' => 'diri',
            'type' => 'menumbuhkan',
            'slot' => 2,
            'allocation_percent' => 30,
            'budget_minor' => 2_550_000,
            'used_minor' => 2_550_000,
            'sort_order' => 2,
        ]);

        $keluarga = Room::create([
            'user_id' => $user->id,
            'name' => 'Keluarga',
            'icon' => 'keluarga',
            'type' => 'mencukupi',
            'slot' => 3,
            'allocation_percent' => 60,
            'budget_minor' => 3_000_000,
            'used_minor' => 2_550_000,
            'sort_order' => 3,
        ]);

        MonthlySummary::create([
            'user_id' => $user->id,
            'month' => now()->startOfMonth(),
            'income_minor' => 8_500_000,
            'unallocated_minor' => 1_200_000,
        ]);

        Transaction::create([
            'user_id' => $user->id,
            'room_id' => null,
            'type' => 'pemasukan',
            'amount_minor' => 8_500_000,
            'category' => 'Gaji',
            'account' => 'Bank Jago',
            'note' => 'Gaji September',
            'occurred_on' => now()->startOfMonth(),
        ]);

        Transaction::create([
            'user_id' => $user->id,
            'room_id' => $keluarga->id,
            'type' => 'pengeluaran',
            'amount_minor' => 150_000,
            'category' => 'Belanja bulanan',
            'account' => 'Bank Jago',
            'note' => 'Belanja pasar',
            'occurred_on' => now()->startOfMonth()->addDays(17),
        ]);

        Transaction::create([
            'user_id' => $user->id,
            'room_id' => $keluarga->id,
            'type' => 'pengeluaran',
            'amount_minor' => 200_000,
            'category' => 'Listrik dan air',
            'account' => 'Tunai',
            'note' => 'Token listrik',
            'occurred_on' => now()->startOfMonth()->addDays(16),
        ]);

        ZakatProfile::create([
            'user_id' => $user->id,
            'name' => 'Utama',
            'net_wealth_minor' => 152_000_000,
            'nisab_minor' => 141_100_000,
            'haul_started_label' => '1 Muharram 1448',
            'haul_due_label' => '1 Muharram 1449',
            'haul_day' => 117,
            'haul_total_days' => 354,
            'status' => 'haul_berjalan',
        ]);
    }
}
