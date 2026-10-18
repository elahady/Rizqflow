<?php

namespace Database\Seeders;

use App\Models\Account;
use App\Models\Category;
use App\Models\MonthlySummary;
use App\Models\QuickEntry;
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

        $diri = Room::create([
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

        $bankJago = Account::create(['user_id' => $user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'balance_minor' => 4_500_000, 'sort_order' => 1]);
        $tunai = Account::create(['user_id' => $user->id, 'name' => 'Tunai', 'type' => 'tunai', 'balance_minor' => 250_000, 'sort_order' => 2]);
        Account::create(['user_id' => $user->id, 'name' => 'GoPay', 'type' => 'e_wallet', 'balance_minor' => 180_000, 'sort_order' => 3]);

        Transaction::create([
            'user_id' => $user->id,
            'room_id' => null,
            'account_id' => $bankJago->id,
            'type' => 'pemasukan',
            'amount_minor' => 8_500_000,
            'category' => 'Gaji',
            'note' => 'Gaji September',
            'occurred_on' => now()->startOfMonth(),
        ]);

        // Pos per ruang. Bobot Keluarga 5:2:3 membagi jatah Rp 3.000.000 jadi 1,5 jt / 600 rb / 900 rb,
        // sehingga batangnya mendekati contoh S11 (80%, 55%, Sekolah lewat jatah). Ruang tanpa bobot
        // membandingkan tiap pos dengan seluruh jatah ruang.
        $pos = fn (Room $room, string $name, ?int $weight, int $order, bool $system = false) => Category::create([
            'room_id' => $room->id,
            'name' => $name,
            'weight' => $weight,
            'is_system' => $system,
            'sort_order' => $order,
        ]);

        $sedekah = $pos($memberi, 'Sedekah', null, 1);
        $pos($memberi, 'Zakat mal', null, 2, system: true);
        $investasi = $pos($diri, 'Investasi', null, 1);
        $tabungan = $pos($diri, 'Tabungan', null, 2);
        $belanja = $pos($keluarga, 'Belanja bulanan', 5, 1);
        $listrik = $pos($keluarga, 'Listrik dan air', 2, 2);
        $sekolah = $pos($keluarga, 'Sekolah', 3, 3);

        // Jumlah pengeluaran tiap ruang sama dengan used_minor-nya di atas.
        $spend = fn (Category $category, int $amount, string $note, Account $account, int $day) => Transaction::create([
            'user_id' => $user->id,
            'room_id' => $category->room_id,
            'category_id' => $category->id,
            'account_id' => $account->id,
            'type' => 'pengeluaran',
            'amount_minor' => $amount,
            'category' => $category->name,
            'note' => $note,
            'occurred_on' => now()->startOfMonth()->addDays($day),
        ]);

        $spend($sedekah, 200_000, 'Sedekah Jumat', $tunai, 3);
        $spend($investasi, 2_000_000, 'Reksa dana bulanan', $bankJago, 1);
        $spend($tabungan, 550_000, 'Tabungan darurat', $bankJago, 1);
        $spend($sekolah, 1_020_000, 'SPP dan buku', $bankJago, 4);
        $spend($belanja, 1_050_000, 'Belanja bulanan', $bankJago, 2);
        $spend($listrik, 130_000, 'Tagihan PDAM', $bankJago, 9);
        $spend($belanja, 150_000, 'Belanja pasar', $bankJago, 17);
        $spend($listrik, 200_000, 'Token listrik', $tunai, 16);

        QuickEntry::create(['user_id' => $user->id, 'name' => 'Kopi', 'amount_minor' => 15_000, 'category_id' => $belanja->id, 'account_id' => $tunai->id]);
        QuickEntry::create(['user_id' => $user->id, 'name' => 'Parkir', 'amount_minor' => 3_000, 'category_id' => $belanja->id, 'account_id' => $tunai->id]);

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
