<?php

namespace Tests\Feature;

use App\Models\Account;
use App\Models\AllocationEntry;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/**
 * Migrasi 2026_10_25_000002_backfill_allocation_entries: pemasukan scaffold yang dicatat sebelum
 * potret alokasi ada dialirkan dengan aturan pengguna sekarang.
 */
class AllocationBackfillTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Account $account;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create();
        $this->account = Account::factory()->create(['user_id' => $this->user->id]);
    }

    private function room(string $name, int $order, ?int $percent, array $extra = []): Room
    {
        return Room::factory()->create(['user_id' => $this->user->id, 'name' => $name, 'sort_order' => $order, 'allocation_percent' => $percent] + $extra);
    }

    /** Pemasukan lama: dicatat langsung tanpa Ledger, jadi tanpa potret. */
    private function legacyIncome(int $amount, ?User $user = null): Transaction
    {
        return Transaction::create([
            'user_id' => ($user ?? $this->user)->id,
            'account_id' => $this->account->id,
            'type' => Transaction::TYPE_INCOME,
            'amount_minor' => $amount,
            'category' => 'Gaji',
            'occurred_on' => '2026-10-01',
        ]);
    }

    private function runBackfill(): void
    {
        $migration = require database_path('migrations/2026_10_25_000002_backfill_allocation_entries.php');
        $migration->up();
    }

    public function test_pemasukan_lama_dialirkan_dengan_aturan_persentase_sekarang(): void
    {
        $memberi = $this->room('Memberi', 1, 10);
        $diri = $this->room('Diri', 2, 30);
        $keluarga = $this->room('Keluarga', 3, 60);
        $income = $this->legacyIncome(1_234_567);

        $this->runBackfill();

        $entries = $income->fresh()->sortedAllocationEntries();
        $this->assertSame([$memberi->id, $diri->id, $keluarga->id], $entries->pluck('room_id')->all());
        $this->assertSame([1000, 3000, 6000], $entries->pluck('share_bp')->all());
        $this->assertSame([123_457, 370_370, 740_740], $entries->pluck('amount_minor')->all());
    }

    public function test_ruang_terarsip_ruang_nol_persen_dan_pengeluaran_tidak_dapat_potret(): void
    {
        $this->room('Memberi', 1, 0);
        $this->room('Lama', 2, 40, ['archived' => true]);
        $keluarga = $this->room('Keluarga', 3, 60);
        $this->legacyIncome(1_000_000);
        Transaction::create([
            'user_id' => $this->user->id, 'account_id' => $this->account->id, 'type' => Transaction::TYPE_EXPENSE,
            'amount_minor' => 5000, 'occurred_on' => '2026-10-02',
        ]);

        $this->runBackfill();

        $this->assertSame([$keluarga->id], AllocationEntry::pluck('room_id')->all());
        $this->assertSame(600_000, (int) AllocationEntry::first()->amount_minor);
    }

    public function test_pemasukan_yang_sudah_punya_potret_tidak_disentuh_dan_migrasi_aman_diulang(): void
    {
        $memberi = $this->room('Memberi', 1, 100);
        $income = $this->legacyIncome(1_000_000);
        AllocationEntry::create(['transaction_id' => $income->id, 'room_id' => $memberi->id, 'share_bp' => 500, 'amount_minor' => 50_000]);

        $this->runBackfill();
        $this->runBackfill();

        $this->assertSame(1, AllocationEntry::count());
        $this->assertSame(50_000, (int) AllocationEntry::first()->amount_minor);

        $other = $this->legacyIncome(2_000_000);
        $this->runBackfill();
        $this->runBackfill();
        $this->assertSame(1, $other->allocationEntries()->count());
        $this->assertSame(2_000_000, (int) $other->allocationEntries()->first()->amount_minor);
    }

    public function test_aturan_yang_melebihi_100_persen_dilewati_dan_pemasukan_tetap_belum_dialirkan(): void
    {
        $this->room('Memberi', 1, 70);
        $this->room('Keluarga', 2, 60);
        $this->legacyIncome(1_000_000);

        $this->runBackfill();

        $this->assertSame(0, AllocationEntry::count());
    }

    public function test_tanpa_aturan_tidak_ada_potret(): void
    {
        $this->room('Keluarga', 1, null);
        $this->legacyIncome(1_000_000);

        $this->runBackfill();

        $this->assertSame(0, AllocationEntry::count());
    }

    public function test_mode_lanjutan_pro_memakai_batas_atas_dan_menyimpan_persentase_turunan(): void
    {
        $this->user->update(['plans' => ['pro'], 'allocation_mode' => 'lanjutan']);
        $memberi = $this->room('Memberi', 1, 10, ['allocation_cap_minor' => 500_000]);
        $diri = $this->room('Diri', 2, 30, ['allocation_cap_minor' => 2_000_000]);
        $keluarga = $this->room('Keluarga', 3, 60);
        $income = $this->legacyIncome(5_000_000);

        $this->runBackfill();

        $entries = $income->fresh()->sortedAllocationEntries();
        $this->assertSame([$memberi->id, $diri->id, $keluarga->id], $entries->pluck('room_id')->all());
        $this->assertSame([500_000, 2_000_000, 2_500_000], $entries->pluck('amount_minor')->all());
        $this->assertSame([1000, 4000, 5000], $entries->pluck('share_bp')->all());
    }

    public function test_mode_lanjutan_tanpa_paket_pro_dianggap_persentase(): void
    {
        $this->user->update(['allocation_mode' => 'lanjutan']);
        $this->room('Memberi', 1, 100, ['allocation_cap_minor' => 1]);
        $income = $this->legacyIncome(1_000_000);

        $this->runBackfill();

        $this->assertSame(1_000_000, (int) $income->allocationEntries()->first()->amount_minor);
    }

    public function test_pemasukan_pengguna_lain_dialirkan_dengan_aturan_pengguna_itu(): void
    {
        $this->room('Keluarga', 1, 100);
        $other = User::factory()->create();
        $otherRoom = Room::factory()->create(['user_id' => $other->id, 'allocation_percent' => 50]);
        $otherIncome = $this->legacyIncome(1_000_000, $other);

        $this->runBackfill();

        $entries = $otherIncome->allocationEntries;
        $this->assertSame([$otherRoom->id], $entries->pluck('room_id')->all());
        $this->assertSame(500_000, (int) $entries->first()->amount_minor);
        $this->assertSame(500_000, $otherIncome->fresh()->unallocatedMinor());
    }
}
