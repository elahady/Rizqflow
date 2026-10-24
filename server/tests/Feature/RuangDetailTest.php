<?php

namespace Tests\Feature;

use App\Livewire\Ruang;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Support\Ledger;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Livewire\Livewire;
use Tests\TestCase;

/**
 * Detail ruang (Ruang.dc.html): kepala, laju belanja (data slider), tabel pos, riwayat 6 bulan,
 * transaksi terbaru, dan akses. Tanggal dibekukan di 24 Oktober 2026 (hari ke-24 dari 31, 8 hari tersisa).
 */
class RuangDetailTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Room $room;

    private Account $account;

    private Category $dapur;

    private Category $anak;

    private Category $rumah;

    private Category $untracked;

    protected function setUp(): void
    {
        parent::setUp();

        Carbon::setTestNow('2026-10-24 09:00:00');

        $this->user = User::factory()->create();
        $this->room = Room::create(['user_id' => $this->user->id, 'name' => 'Keluarga', 'type' => 'mencukupi', 'slot' => 3, 'sort_order' => 1, 'allocation_percent' => 100]);
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'BCA', 'type' => 'bank', 'balance_minor' => 0, 'sort_order' => 1]);
        $this->dapur = Category::create(['room_id' => $this->room->id, 'name' => 'Dapur dan belanja', 'weight' => 5, 'sort_order' => 1]);
        $this->anak = Category::create(['room_id' => $this->room->id, 'name' => 'Anak dan sekolah', 'weight' => 2, 'sort_order' => 2]);
        $this->rumah = Category::create(['room_id' => $this->room->id, 'name' => 'Tagihan rumah', 'weight' => 3, 'sort_order' => 3]);
        $this->untracked = Category::create(['room_id' => $this->room->id, 'name' => Category::UNTRACKED, 'is_system' => true, 'sort_order' => 4]);
    }

    protected function tearDown(): void
    {
        Carbon::setTestNow();

        parent::tearDown();
    }

    /** Persona mockup: jatah Rp 10.800.000, terpakai Rp 9.396.000 sampai hari ke-24. */
    private function seedMockupMonth(): void
    {
        Ledger::recordIncome($this->user, 10_800_000, $this->account, Carbon::parse('2026-10-01'), 'Gaji', null);
        $this->spend($this->dapur, 4_320_000, '2026-10-03', 'Belanja pasar');
        $this->spend($this->anak, 2_430_000, '2026-10-10', 'SPP Oktober');
        $this->spend($this->rumah, 1_782_000, '2026-10-15', 'Token listrik');
        $this->spend($this->untracked, 864_000, '2026-10-24', 'QRIS');
    }

    private function spend(Category $category, int $amount, string $date, ?string $note = null): Transaction
    {
        return Transaction::create([
            'user_id' => $this->user->id,
            'room_id' => $this->room->id,
            'category_id' => $category->id,
            'category' => $category->name,
            'type' => 'pengeluaran',
            'amount_minor' => $amount,
            'note' => $note,
            'occurred_on' => $date,
        ]);
    }

    public function test_header_shows_spent_allowance_remaining_and_safe_per_day(): void
    {
        $this->seedMockupMonth();

        $this->actingAs($this->user)
            ->get(route('ruang.detail', $this->room))
            ->assertOk()
            ->assertSeeInOrder(['Keluarga', 'Perlu perhatian', 'Terpakai', 'Rp 9.396.000', 'dari jatah', 'Rp 10.800.000'])
            ->assertSee('Sisa Rp 1.404.000')
            ->assertSee('aman ≈ Rp 175.500 per hari untuk 8 hari tersisa')
            ->assertSee('Mencukupi')
            ->assertSee('100% dari alokasi')
            ->assertSee('Atur aturan')
            ->assertSee(route('aturan-alokasi'), false)
            ->assertSee(route('kelola', ['tab' => 'kategori']), false)
            ->assertSee('Oktober 2026');
    }

    public function test_slider_rows_carry_whole_rupiah_pace_and_difference(): void
    {
        $this->seedMockupMonth();

        $chart = Livewire::actingAs($this->user)->test(Ruang::class, ['room' => $this->room])->viewData('chart');

        $this->assertNull($chart['reason']);
        $this->assertSame(24, $chart['last']);
        $this->assertCount(24, $chart['rows']);

        // Hari ke-24: laju jatah = intdiv(10.800.000 x 24, 31) = 8.361.290; selisih 1.034.710 = 12% (dibulatkan ke bawah).
        $last = $chart['rows'][23];
        $this->assertSame('24 Okt', $last['label']);
        $this->assertSame('Rp 9.396.000', $last['spent']);
        $this->assertSame('Rp 8.361.290', $last['pace']);
        $this->assertSame('Rp 1.034.710 (12%) di atas laju jatah', $last['diff']);

        // Hari ke-1: belum ada pengeluaran, jadi di bawah laju (intdiv(10.800.000, 31) = 348.387).
        $first = $chart['rows'][0];
        $this->assertSame('Rp 0', $first['spent']);
        $this->assertSame('Rp 348.387', $first['pace']);
        $this->assertSame('Rp 348.387 (100%) di bawah laju jatah', $first['diff']);

        // Kumulatif: Rp 4.320.000 sudah terpakai di hari ke-3, dan koordinatnya naik monoton (y mengecil).
        $this->assertSame('Rp 4.320.000', $chart['rows'][2]['spent']);
        $this->assertLessThan((float) $chart['rows'][0]['cy'], (float) $chart['rows'][23]['cy']);
    }

    public function test_chart_markup_has_axis_allowance_line_slider_and_readout(): void
    {
        $this->seedMockupMonth();

        $this->actingAs($this->user)
            ->get(route('ruang.detail', $this->room))
            ->assertOk()
            ->assertSee('Laju belanja bulan ini')
            ->assertSee('3 jt', false)
            ->assertSee('12 jt', false)
            ->assertSee('Jatah Rp 10,8 jt', false)
            ->assertSee('Laju jatah', false)
            ->assertSee('type="range"', false)
            ->assertSee('max="24"', false)
            ->assertSee('24 Okt', false)
            ->assertSee('Rp 1.034.710 (12%) di atas laju jatah', false)
            ->assertSee('Grafik pengeluaran kumulatif Keluarga Oktober 2026. Hari ke-24 terpakai Rp 9,4 jt', false);
    }

    public function test_room_without_allowance_shows_a_neutral_message_instead_of_the_chart(): void
    {
        $this->spend($this->dapur, 50_000, '2026-10-03');

        $this->actingAs($this->user)
            ->get(route('ruang.detail', $this->room))
            ->assertOk()
            ->assertSee('belum punya jatah')
            ->assertDontSee('type="range"', false);
    }

    public function test_pos_table_shows_weights_slices_and_flags_a_pos_over_its_allowance(): void
    {
        $this->seedMockupMonth();

        $rows = Livewire::actingAs($this->user)->test(Ruang::class, ['room' => $this->room])->viewData('categories')
            ->keyBy(fn ($row) => $row['category']->name);

        $this->assertSame(5_400_000, $rows['Dapur dan belanja']['budget_minor']);
        $this->assertSame(80, $rows['Dapur dan belanja']['percent']);
        $this->assertSame(2_160_000, $rows['Anak dan sekolah']['budget_minor']);
        $this->assertSame(112, $rows['Anak dan sekolah']['percent']);

        $this->actingAs($this->user)
            ->get(route('ruang.detail', $this->room))
            ->assertSeeInOrder(['Anak dan sekolah', '2.160.000', '2.430.000', '112%'])
            ->assertSee('Tak terlacak')
            ->assertSee('Lihat 1 transaksi')
            ->assertSee(route('transaksi', ['tab' => 'tak', 'ruang' => $this->room->id]))
            ->assertSeeInOrder(['Total', '10.800.000', '9.396.000', '87%']);
    }

    public function test_six_month_history_lists_each_month_and_names_those_over_the_allowance(): void
    {
        $this->seedMockupMonth();
        // September: jatah Rp 5.000.000, terpakai Rp 5.100.000 (102%).
        Ledger::recordIncome($this->user, 5_000_000, $this->account, Carbon::parse('2026-09-01'), 'Gaji', null);
        $this->spend($this->dapur, 5_100_000, '2026-09-12');

        $history = Livewire::actingAs($this->user)->test(Ruang::class, ['room' => $this->room])->viewData('history');

        $this->assertCount(6, $history['bars']);
        $this->assertSame(['Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt'], array_column($history['bars'], 'name'));
        $this->assertSame(102, $history['bars'][4]['percent']);
        $this->assertTrue($history['bars'][4]['over']);
        $this->assertTrue($history['bars'][5]['running']);
        $this->assertSame(87, $history['bars'][5]['percent']);
        $this->assertSame('September melewati jatah 2%. Oktober masih berjalan (batang pudar).', $history['summary']);

        $this->actingAs($this->user)
            ->get(route('ruang.detail', $this->room))
            ->assertSee('Riwayat 6 bulan')
            ->assertSee('September melewati jatah 2%.');
    }

    public function test_history_says_so_when_no_month_went_over(): void
    {
        $this->seedMockupMonth();

        $history = Livewire::actingAs($this->user)->test(Ruang::class, ['room' => $this->room])->viewData('history');

        $this->assertSame('Tidak ada bulan yang melewati jatah. Oktober masih berjalan (batang pudar).', $history['summary']);
    }

    public function test_period_query_shows_a_past_month_in_full(): void
    {
        $this->seedMockupMonth();
        Ledger::recordIncome($this->user, 5_000_000, $this->account, Carbon::parse('2026-09-01'), 'Gaji', null);
        $this->spend($this->dapur, 2_000_000, '2026-09-30', 'Belanja akhir bulan');

        $component = Livewire::withQueryParams(['bulan' => '2026-09'])->actingAs($this->user)->test(Ruang::class, ['room' => $this->room]);
        $chart = $component->viewData('chart');

        // Bulan lampau digambar sebulan penuh (30 hari), tanpa "aman per hari".
        $this->assertSame(30, $chart['last']);
        $this->assertSame('Rp 2.000.000', $chart['rows'][29]['spent']);

        $this->actingAs($this->user)
            ->get(route('ruang.detail', ['room' => $this->room, 'bulan' => '2026-09']))
            ->assertOk()
            ->assertSee('September 2026')
            ->assertSee('Belanja akhir bulan')
            ->assertDontSee('Belanja pasar')
            ->assertDontSee('aman ≈');
    }

    public function test_latest_transactions_are_the_six_newest_of_that_month_with_detail_links(): void
    {
        $this->seedMockupMonth();
        $old = $this->spend($this->dapur, 10_000, '2026-09-30', 'Bulan lalu');
        for ($day = 4; $day <= 9; $day++) {
            $this->spend($this->dapur, 1_000 * $day, '2026-10-0'.$day, 'Kecil '.$day);
        }

        $transactions = Livewire::actingAs($this->user)->test(Ruang::class, ['room' => $this->room])->viewData('transactions');

        $this->assertCount(6, $transactions);
        $this->assertNotContains($old->id, $transactions->pluck('id')->all());
        $this->assertSame('QRIS', $transactions->first()->note);

        $this->actingAs($this->user)
            ->get(route('ruang.detail', $this->room))
            ->assertSee('Transaksi terbaru')
            ->assertSee(route('transaksi.detail', $transactions->first()), false)
            ->assertDontSee('Bulan lalu');
    }

    public function test_another_users_room_is_forbidden(): void
    {
        $this->actingAs(User::factory()->create())
            ->get(route('ruang.detail', $this->room))
            ->assertForbidden();
    }

    public function test_pace_chart_never_includes_another_users_spending(): void
    {
        $this->seedMockupMonth();
        $other = User::factory()->create();
        $otherRoom = Room::create(['user_id' => $other->id, 'name' => 'Keluarga', 'type' => 'mencukupi', 'slot' => 3, 'sort_order' => 1]);
        Transaction::create(['user_id' => $other->id, 'room_id' => $otherRoom->id, 'type' => 'pengeluaran', 'amount_minor' => 99_000_000, 'occurred_on' => '2026-10-05']);

        $chart = Livewire::actingAs($this->user)->test(Ruang::class, ['room' => $this->room])->viewData('chart');

        $this->assertSame('Rp 9.396.000', $chart['rows'][23]['spent']);
    }
}
