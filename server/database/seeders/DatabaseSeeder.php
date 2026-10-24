<?php

namespace Database\Seeders;

use App\Models\Account;
use App\Models\Bill;
use App\Models\Category;
use App\Models\QuickEntry;
use App\Models\RecurringRule;
use App\Models\Room;
use App\Models\User;
use App\Models\ZakatProfile;
use App\Support\Ledger;
use Carbon\CarbonImmutable;
use Illuminate\Database\Console\Seeds\WithoutModelEvents;
use Illuminate\Database\Seeder;
use Illuminate\Support\Facades\Hash;

/**
 * Data contoh untuk mockup web Rizqflow (persona Ayu, bukan data sungguhan): angkanya mengikuti
 * docs/design/web-mockup/project/{Main,Transaksi,Ruang,Analisa,Zakat}.dc.html supaya layar lokal
 * bisa dibandingkan langsung dengan mockup. Mockup berlatar Sabtu 24 Oktober 2026 ("hari ke-24");
 * tanggal di sini tetap tanggal-dalam-bulan relatif ke bulan berjalan (bukan "hari ini"), jadi
 * angka bulan berjalan persis mockup bila seed dijalankan pada atau sesudah tanggal 24.
 *
 * Semua pemasukan dan pengeluaran dicatat lewat App\Support\Ledger, sama seperti saat pengguna
 * mencatat, jadi potret alokasi pemasukan dan saldo akun terbentuk dengan jalur yang sama. Saldo
 * akun lalu ditetapkan ke angka akhir yang wajar (web belum punya saldo pembuka terpisah).
 */
class DatabaseSeeder extends Seeder
{
    use WithoutModelEvents;

    /** Tanggal gajian tiap bulan. */
    private const PAYDAY = 22;

    private const SALARY = 18_000_000;

    private User $user;

    /** @var array<string, Account> */
    private array $accounts = [];

    /** @var array<string, Category> pos menurut "Ruang/Pos" */
    private array $pos = [];

    public function run(): void
    {
        $this->user = User::factory()->create([
            'name' => 'Ayu',
            'email' => 'demo@rizqflow.test',
            'password' => Hash::make('password'),
        ]);

        $this->seedAccounts();
        $this->seedRooms();

        // Enam bulan: lima bulan lalu sampai bulan berjalan, tiap bulan satu gaji lalu pengeluarannya.
        $this->seedHistory();
        $this->seedCurrentMonth();

        $this->seedSchedules();
        $this->seedZakat();

        QuickEntry::create([
            'user_id' => $this->user->id,
            'name' => 'Kopi susu',
            'amount_minor' => 28_000,
            'category_id' => $this->pos['Diri/Jajan dan hobi']->id,
            'account_id' => $this->accounts['GoPay']->id,
        ]);

        // Saldo akhir (web belum punya saldo pembuka): ditetapkan setelah semua transaksi tercatat.
        $this->accounts['BCA']->update(['balance_minor' => 24_850_000]);
        $this->accounts['GoPay']->update(['balance_minor' => 385_000]);
        $this->accounts['Tunai']->update(['balance_minor' => 640_000]);
    }

    private function seedAccounts(): void
    {
        foreach ([['BCA', 'bank'], ['GoPay', 'e_wallet'], ['Tunai', 'tunai']] as $i => [$name, $type]) {
            $this->accounts[$name] = Account::create([
                'user_id' => $this->user->id,
                'name' => $name,
                'type' => $type,
                'balance_minor' => 0,
                'sort_order' => $i + 1,
            ]);
        }
    }

    private function seedRooms(): void
    {
        $rooms = [
            ['Memberi', 'memberi', 'menunaikan', 1, 10, [
                ['Sedekah dan infak', null, false], ['Orang tua', null, false], [Category::ZAKAT, null, true], [Category::UNTRACKED, null, true],
            ]],
            ['Diri', 'diri', 'menumbuhkan', 2, 30, [
                ['Investasi', null, false], ['Dana darurat', null, false], ['Jajan dan hobi', null, false], ['Transportasi', null, false], [Category::UNTRACKED, null, true],
            ]],
            // Bobot Keluarga 5:2:3 membagi jatah (60% dari gaji = 10.800.000) jadi 5.400.000 / 2.160.000 / 3.240.000.
            ['Keluarga', 'keluarga', 'mencukupi', 3, 60, [
                ['Dapur dan belanja', 5, false], ['Anak dan sekolah', 2, false], ['Tagihan rumah', 3, false], [Category::UNTRACKED, null, true],
            ]],
        ];

        foreach ($rooms as $order => [$name, $icon, $type, $slot, $percent, $categories]) {
            $room = Room::create([
                'user_id' => $this->user->id,
                'name' => $name,
                'icon' => $icon,
                'type' => $type,
                'slot' => $slot,
                'allocation_percent' => $percent,
                'sort_order' => $order + 1,
            ]);

            foreach ($categories as $i => [$categoryName, $weight, $system]) {
                $this->pos["{$name}/{$categoryName}"] = Category::create([
                    'room_id' => $room->id,
                    'name' => $categoryName,
                    'weight' => $weight,
                    'is_system' => $system,
                    'sort_order' => $i + 1,
                ]);
            }
        }
    }

    /** Tanggal $day pada bulan $monthsAgo bulan sebelum bulan berjalan (dipotong ke akhir bulan itu). */
    private function date(int $monthsAgo, int $day): CarbonImmutable
    {
        $month = CarbonImmutable::today()->startOfMonth()->subMonthsNoOverflow($monthsAgo);

        return $month->day(min($day, $month->daysInMonth));
    }

    private function income(int $monthsAgo): void
    {
        $date = $this->date($monthsAgo, self::PAYDAY);
        $monthName = $date->locale('id')->translatedFormat('F');

        Ledger::recordIncome($this->user, self::SALARY, $this->accounts['BCA'], $date, 'Gaji', "Gaji {$monthName}");
    }

    /**
     * @param  string  $pos  "Ruang/Pos"
     * @param  array<string, mixed>  $extra
     */
    private function spend(int $monthsAgo, int $day, string $pos, int $amount, string $note, string $account = 'BCA', array $extra = []): void
    {
        Ledger::recordExpense($this->user, $amount, $this->pos[$pos], $this->accounts[$account], $this->date($monthsAgo, $day), $note, $extra);
    }

    private function seedHistory(): void
    {
        // Pengeluaran Keluarga 5 bulan terakhir (Mei sampai September) dari yang terlama:
        // 92%, 102%, 94%, 92%, 90% dari jatah 10.800.000. September juga yang jadi pembanding
        // "sampai tanggal yang sama": sampai tanggal 24 total semua ruang 14.770.000.
        $history = [
            5 => ['keluarga' => 9_936_000, 'diri' => 4_800_000, 'late' => 0],
            4 => ['keluarga' => 11_016_000, 'diri' => 5_000_000, 'late' => 0],
            3 => ['keluarga' => 10_152_000, 'diri' => 4_700_000, 'late' => 0],
            2 => ['keluarga' => 9_936_000, 'diri' => 5_100_000, 'late' => 0],
            1 => ['keluarga' => 9_720_000, 'diri' => 4_650_000, 'late' => 1_400_000],
        ];

        foreach ($history as $ago => $plan) {
            $this->income($ago);

            // Memberi: 1.800.000 (jatahnya) tiap bulan.
            $this->spend($ago, 5, 'Memberi/Sedekah dan infak', 800_000, 'Sedekah dan infak bulanan', 'Tunai');
            $this->spend($ago, 14, 'Memberi/Sedekah dan infak', 500_000, 'Santunan yatim');
            $this->spend($ago, 20, 'Memberi/Orang tua', 500_000, 'Kiriman orang tua');

            // Diri: investasi, dana darurat, transportasi tetap; sisanya jajan dan hobi.
            $this->spend($ago, 17, 'Diri/Investasi', 1_000_000, 'DCA reksa dana');
            $this->spend($ago, 3, 'Diri/Dana darurat', 500_000, 'Setor dana darurat');
            $this->spend($ago, 9, 'Diri/Transportasi', 540_000, 'Bensin dan parkir', 'GoPay');
            $hobby = $plan['diri'] - 2_040_000;
            $this->spend($ago, 8, 'Diri/Jajan dan hobi', intdiv($hobby, 2), 'Jajan dan hobi', 'GoPay');
            $this->spend($ago, 19, 'Diri/Jajan dan hobi', $hobby - intdiv($hobby, 2), 'Hobi dan hiburan');

            // Keluarga: bagian sebelum tanggal 24 dipecah 42% / 26% / sisanya; yang "late" jatuh
            // sesudah tanggal 24 (September), supaya perbandingan tanggal yang sama bermakna.
            $early = $plan['keluarga'] - $plan['late'];
            $dapur = intdiv($early * 42, 100 * 1000) * 1000;
            $anak = intdiv($early * 26, 100 * 1000) * 1000;
            $tagihan = $early - $dapur - $anak;
            $this->spend($ago, 2, 'Keluarga/Dapur dan belanja', intdiv($dapur, 2), 'Belanja bulanan');
            $this->spend($ago, 16, 'Keluarga/Dapur dan belanja', $dapur - intdiv($dapur, 2), 'Belanja pasar dan makan');
            $this->spend($ago, 6, 'Keluarga/Anak dan sekolah', $anak, 'SPP dan keperluan anak');
            $this->spend($ago, 11, 'Keluarga/Tagihan rumah', intdiv($tagihan, 2), 'Listrik, air, dan internet');
            $this->spend($ago, 21, 'Keluarga/Tagihan rumah', $tagihan - intdiv($tagihan, 2), 'Iuran dan kebutuhan rumah');

            if ($plan['late'] > 0) {
                $this->spend($ago, 27, 'Keluarga/Dapur dan belanja', 900_000, 'Belanja bulanan');
                $this->spend($ago, 29, 'Keluarga/Tagihan rumah', $plan['late'] - 900_000, 'Token listrik');
            }
        }

        // September: Memberi 1.800.000 + Diri 4.650.000 + Keluarga sampai tanggal 24 (8.320.000)
        // = 14.770.000, tepat 442.000 di atas Oktober pada tanggal yang sama.
    }

    private function seedCurrentMonth(): void
    {
        $this->income(0);

        // Memberi 1.800.000: sedekah dan infak 1.300.000 + orang tua 500.000.
        $this->spend(0, 2, 'Memberi/Sedekah dan infak', 100_000, 'Sedekah Jumat', 'Tunai');
        $this->spend(0, 9, 'Memberi/Sedekah dan infak', 200_000, 'Infak masjid');
        $this->spend(0, 12, 'Memberi/Sedekah dan infak', 500_000, 'Santunan yatim');
        $this->spend(0, 16, 'Memberi/Sedekah dan infak', 100_000, 'Sedekah Jumat', 'Tunai');
        $this->spend(0, 19, 'Memberi/Sedekah dan infak', 150_000, 'Sedekah untuk tetangga');
        $this->spend(0, 23, 'Memberi/Sedekah dan infak', 250_000, 'Infak pembangunan masjid');
        $this->spend(0, 20, 'Memberi/Orang tua', 500_000, 'Kiriman orang tua');

        // Diri 3.132.000.
        $this->spend(0, 17, 'Diri/Investasi', 1_000_000, 'DCA reksa dana', 'BCA', ['recurring_rule_id' => $this->dcaRuleId()]);
        $this->spend(0, 3, 'Diri/Dana darurat', 500_000, 'Setor dana darurat');
        $this->spend(0, 5, 'Diri/Jajan dan hobi', 120_000, 'Nonton bioskop', 'GoPay');
        $this->spend(0, 8, 'Diri/Jajan dan hobi', 185_000, 'Beli buku');
        $this->spend(0, 10, 'Diri/Jajan dan hobi', 28_000, 'Kopi susu', 'GoPay');
        $this->spend(0, 11, 'Diri/Jajan dan hobi', 172_000, 'Gunting rambut', 'Tunai');
        $this->spend(0, 13, 'Diri/Jajan dan hobi', 145_000, 'Makan siang bareng teman', 'GoPay');
        $this->spend(0, 18, 'Diri/Jajan dan hobi', 240_000, 'Cat model dan kuas');
        $this->spend(0, 19, 'Diri/Jajan dan hobi', 146_000, 'Jajan online', 'GoPay');
        $this->spend(0, 21, 'Diri/Jajan dan hobi', 28_000, 'Kopi susu', 'GoPay');
        $this->spend(0, 24, 'Diri/Jajan dan hobi', 28_000, 'Kopi susu', 'GoPay');
        $this->spend(0, 4, 'Diri/Transportasi', 100_000, 'Isi bensin', 'Tunai');
        $this->spend(0, 7, 'Diri/Transportasi', 65_000, 'Tol dan parkir', 'GoPay');
        $this->spend(0, 11, 'Diri/Transportasi', 100_000, 'Isi bensin', 'Tunai');
        $this->spend(0, 14, 'Diri/Transportasi', 175_000, 'Servis motor');
        $this->spend(0, 18, 'Diri/Transportasi', 100_000, 'Isi bensin', 'Tunai');

        // Keluarga 9.396.000.
        $this->spend(0, 2, 'Keluarga/Dapur dan belanja', 1_150_000, 'Belanja bulanan');
        $this->spend(0, 5, 'Keluarga/Dapur dan belanja', 212_500, 'Belanja pasar', 'Tunai');
        $this->spend(0, 9, 'Keluarga/Dapur dan belanja', 980_000, 'Belanja bulanan');
        $this->spend(0, 12, 'Keluarga/Dapur dan belanja', 195_000, 'Belanja pasar', 'Tunai');
        $this->spend(0, 16, 'Keluarga/Dapur dan belanja', 210_000, 'Makan malam keluarga');
        $this->spend(0, 19, 'Keluarga/Dapur dan belanja', 1_020_000, 'Belanja bulanan');
        $this->spend(0, 20, 'Keluarga/Dapur dan belanja', 365_000, 'Sayur, lauk, dan gas', 'Tunai');
        $this->spend(0, 24, 'Keluarga/Dapur dan belanja', 187_500, 'Belanja pasar', 'Tunai');

        $this->spend(0, 4, 'Keluarga/Anak dan sekolah', 500_000, 'Les tambahan');
        $this->spend(0, 10, 'Keluarga/Anak dan sekolah', 285_000, 'Perlengkapan sekolah');
        $this->spend(0, 13, 'Keluarga/Anak dan sekolah', 160_000, 'Buku cerita');
        $this->spend(0, 18, 'Keluarga/Anak dan sekolah', 235_000, 'Susu dan popok');
        $this->spend(0, 21, 'Keluarga/Anak dan sekolah', 1_250_000, 'SPP '.$this->date(0, 1)->locale('id')->translatedFormat('F'));

        $this->spend(0, 5, 'Keluarga/Tagihan rumah', 147_000, 'Air PDAM');
        $this->spend(0, 7, 'Keluarga/Tagihan rumah', 150_000, 'Iuran lingkungan');
        $this->spend(0, 12, 'Keluarga/Tagihan rumah', 200_000, 'Perbaikan keran', 'Tunai');
        $this->spend(0, 14, 'Keluarga/Tagihan rumah', 250_000, 'Pulsa dan paket data keluarga', 'GoPay');
        $this->spend(0, 15, 'Keluarga/Tagihan rumah', 385_000, 'Internet rumah', 'BCA', ['recurring_rule_id' => $this->internetRuleId()]);
        $this->spend(0, 20, 'Keluarga/Tagihan rumah', 250_000, 'Gas elpiji');
        $this->spend(0, 23, 'Keluarga/Tagihan rumah', 400_000, 'Token listrik');

        // Tak terlacak Keluarga 864.000: belum punya pos sungguhan.
        $this->spend(0, 21, 'Keluarga/Tak terlacak', 300_000, 'Tarik tunai');
        $this->spend(0, 22, 'Keluarga/Tak terlacak', 275_000, 'Koreksi saldo BCA', 'BCA');
        $this->spend(0, 23, 'Keluarga/Tak terlacak', 289_000, 'QRIS Indomaret');
    }

    private ?int $dcaRuleId = null;

    private ?int $internetRuleId = null;

    private function dcaRuleId(): int
    {
        return $this->dcaRuleId ??= RecurringRule::create([
            'user_id' => $this->user->id,
            'type' => 'pengeluaran',
            'amount_minor' => 1_000_000,
            'account_id' => $this->accounts['BCA']->id,
            'room_id' => $this->pos['Diri/Investasi']->room_id,
            'category_id' => $this->pos['Diri/Investasi']->id,
            'note' => 'DCA reksa dana',
            'frequency' => 'bulanan',
            'start_date' => $this->date(5, 17),
            // Oktober sudah tercatat (tanggal 17); jatuh tempo berikutnya bulan depan.
            'next_due' => $this->date(0, 17)->addMonthNoOverflow(),
        ])->id;
    }

    private function internetRuleId(): int
    {
        return $this->internetRuleId ??= RecurringRule::create([
            'user_id' => $this->user->id,
            'type' => 'pengeluaran',
            'amount_minor' => 385_000,
            'account_id' => $this->accounts['BCA']->id,
            'room_id' => $this->pos['Keluarga/Tagihan rumah']->room_id,
            'category_id' => $this->pos['Keluarga/Tagihan rumah']->id,
            'note' => 'Internet rumah',
            'frequency' => 'bulanan',
            'start_date' => $this->date(5, 15),
            // Jatuh tempo empat hari lagi (Rab 28 di mockup); tidak tercatat sampai harinya tiba.
            'next_due' => CarbonImmutable::today()->addDays(4),
        ])->id;
    }

    private function seedSchedules(): void
    {
        $today = CarbonImmutable::today();
        $tagihan = $this->pos['Keluarga/Tagihan rumah'];

        // Terbentuk di sini bila belum ada (rutenya sama dengan yang dipakai bulan berjalan).
        $this->dcaRuleId();
        $this->internetRuleId();

        Bill::create([
            'user_id' => $this->user->id,
            'name' => 'Cicilan motor',
            'amount_minor' => 1_150_000,
            'account_id' => $this->accounts['BCA']->id,
            'room_id' => $tagihan->room_id,
            'category_id' => $tagihan->id,
            'frequency' => 'bulanan',
            'start_date' => $today->subMonthsNoOverflow(10)->addDays(2),
            'next_due' => $today->addDays(2),
        ]);

        Bill::create([
            'user_id' => $this->user->id,
            'name' => 'Premi asuransi',
            'amount_minor' => 640_000,
            'account_id' => $this->accounts['BCA']->id,
            'room_id' => $tagihan->room_id,
            'category_id' => $tagihan->id,
            'frequency' => 'bulanan',
            'start_date' => $today->subMonthsNoOverflow(6)->addDays(6),
            'next_due' => $today->addDays(6),
        ]);
    }

    private function seedZakat(): void
    {
        ZakatProfile::create([
            'user_id' => $this->user->id,
            'name' => 'Utama',
            'net_wealth_minor' => 152_000_000,
            'nisab_minor' => 127_500_000,
            'haul_started_label' => '≈ 1 Jumadil Akhir 1447 H · 21 Nov 2025',
            'haul_due_label' => '≈ 1 Jumadil Akhir 1448 H · 10 Nov 2026',
            'haul_day' => 337,
            'haul_total_days' => 354,
            'status' => 'haul_berjalan',
        ]);

        // Zakat tahun lalu, sekitar 337 hari sebelum hari ini (awal haul).
        Ledger::recordExpense(
            $this->user,
            3_560_000,
            $this->pos['Memberi/'.Category::ZAKAT],
            $this->accounts['BCA'],
            CarbonImmutable::today()->subDays(337),
            'Zakat mal tahun lalu',
            ['recipient_institution' => 'BAZNAS'],
        );
    }
}
