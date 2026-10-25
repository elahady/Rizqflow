<?php

namespace Tests\Feature;

use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Tests\TestCase;

/**
 * Ringkasan bulanan (porting S36 Android, Gratis). Tanpa Pro: halaman tetap terbuka untuk akun gratis,
 * sedangkan Tren dan Bandingkan periode tetap dikunci. Angka dari transaksi, jadi tiap tes menanam
 * transaksi langsung seperti BandingkanPeriodeTest.
 */
class RingkasanTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Room $keluarga;

    private Room $diri;

    private Account $account;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create(['plans' => []]);
        $this->keluarga = Room::create(['user_id' => $this->user->id, 'name' => 'Keluarga', 'type' => 'mencukupi', 'slot' => 1, 'sort_order' => 1]);
        $this->diri = Room::create(['user_id' => $this->user->id, 'name' => 'Diri', 'type' => 'menumbuhkan', 'slot' => 2, 'sort_order' => 2]);
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'sort_order' => 1]);
    }

    private function category(Room $room, string $name): Category
    {
        return Category::create(['room_id' => $room->id, 'name' => $name, 'sort_order' => Category::where('room_id', $room->id)->count() + 1]);
    }

    private function tx(string $type, int $amount, string $date, ?Room $room = null, ?Category $category = null, ?User $owner = null): Transaction
    {
        return Transaction::create([
            'user_id' => ($owner ?? $this->user)->id,
            'account_id' => $this->account->id,
            'room_id' => $room?->id,
            'category_id' => $category?->id,
            'category' => $category?->name,
            'type' => $type,
            'amount_minor' => $amount,
            'occurred_on' => Carbon::parse($date),
        ]);
    }

    /** Oktober 2026: pemasukan 5 juta, pengeluaran 1,2 juta di dua ruang. */
    private function octoberData(): void
    {
        $this->tx(Transaction::TYPE_INCOME, 5_000_000, '2026-10-02');
        $this->tx(Transaction::TYPE_EXPENSE, 900_000, '2026-10-05', $this->keluarga, $this->category($this->keluarga, 'Belanja bulanan'));
        $this->tx(Transaction::TYPE_EXPENSE, 300_000, '2026-10-09', $this->diri, $this->category($this->diri, 'Kursus'));
    }

    public function test_a_free_account_can_open_the_summary(): void
    {
        $this->octoberData();

        $this->actingAs($this->user)
            ->get(route('ringkasan', ['bulan' => '2026-10']))
            ->assertOk()
            ->assertSee('Ringkasan')
            ->assertSee('Oktober 2026')
            ->assertSee('Pemasukan')
            ->assertSee('Pengeluaran')
            ->assertSee('Sisa bulan ini');
    }

    public function test_the_nav_links_to_the_summary_for_every_account(): void
    {
        $this->actingAs($this->user)->get(route('denah'))
            ->assertOk()
            ->assertSee('href="'.route('ringkasan').'"', false);
    }

    public function test_shows_income_expense_and_remainder_for_the_month(): void
    {
        $this->octoberData();

        $this->actingAs($this->user)
            ->get(route('ringkasan', ['bulan' => '2026-10']))
            ->assertOk()
            ->assertSee('Rp 5.000.000')
            ->assertSee('Rp 1.200.000')
            ->assertSee('Rp 3.800.000');
    }

    public function test_compares_with_last_month_as_percent_and_previous_amount(): void
    {
        $this->octoberData();
        // September: pemasukan 4 juta, pengeluaran 1 juta, sisa 3 juta.
        $this->tx(Transaction::TYPE_INCOME, 4_000_000, '2026-09-03');
        $this->tx(Transaction::TYPE_EXPENSE, 1_000_000, '2026-09-04', $this->keluarga, $this->category($this->keluarga, 'Belanja bulanan'));

        $this->actingAs($this->user)
            ->get(route('ringkasan', ['bulan' => '2026-10']))
            ->assertOk()
            // Pemasukan naik 25% (5 juta vs 4 juta). Pengeluaran naik 20% (1,2 juta vs 1 juta).
            ->assertSee('Naik 25% dari bulan lalu (Rp 4.000.000)')
            ->assertSee('Naik 20% dari bulan lalu (Rp 1.000.000)')
            // Sisa naik dari 3 juta ke 3,8 juta: 26,66% dengan pemotongan basis poin seperti Android.
            ->assertSee('Naik 26,66% dari bulan lalu (Rp 3.000.000)');
    }

    public function test_says_same_as_last_month_when_nothing_changed(): void
    {
        $this->tx(Transaction::TYPE_INCOME, 5_000_000, '2026-10-02');
        $this->tx(Transaction::TYPE_INCOME, 5_000_000, '2026-09-02');

        $this->actingAs($this->user)
            ->get(route('ringkasan', ['bulan' => '2026-10']))
            ->assertSee('Sama dengan bulan lalu (Rp 5.000.000)');
    }

    public function test_says_no_previous_data_when_last_month_is_empty(): void
    {
        $this->octoberData();

        $this->actingAs($this->user)
            ->get(route('ringkasan', ['bulan' => '2026-10']))
            ->assertOk()
            ->assertSee('Belum ada data bulan lalu')
            ->assertDontSee('dari bulan lalu');
    }

    public function test_breaks_down_spending_per_room_and_shows_only_the_top_five_categories(): void
    {
        $this->tx(Transaction::TYPE_INCOME, 5_000_000, '2026-10-02');
        foreach (['Pos A' => 600_000, 'Pos B' => 500_000, 'Pos C' => 400_000, 'Pos D' => 300_000, 'Pos E' => 200_000, 'Pos F' => 100_000] as $name => $amount) {
            $this->tx(Transaction::TYPE_EXPENSE, $amount, '2026-10-12', $this->keluarga, $this->category($this->keluarga, $name));
        }

        $response = $this->actingAs($this->user)
            ->get(route('ringkasan', ['bulan' => '2026-10']))
            ->assertOk()
            ->assertSee('Pengeluaran per ruang')
            ->assertSee('Kategori terbesar')
            ->assertSee('Pos A')
            ->assertSee('Pos E');

        // Pos F (terkecil dari enam) tidak ditampilkan: hanya lima teratas.
        $response->assertDontSee('Pos F');
    }

    public function test_top_categories_show_the_room_they_belong_to(): void
    {
        $this->tx(Transaction::TYPE_EXPENSE, 300_000, '2026-10-09', $this->diri, $this->category($this->diri, 'Kursus'));

        $this->actingAs($this->user)
            ->get(route('ringkasan', ['bulan' => '2026-10']))
            ->assertSee('Diri')
            ->assertSee('Kursus');
    }

    public function test_the_month_from_the_query_string_is_summed_for_that_month_only(): void
    {
        $this->octoberData();
        $this->tx(Transaction::TYPE_INCOME, 4_000_000, '2026-09-03');

        $this->actingAs($this->user)
            ->get(route('ringkasan', ['bulan' => '2026-09']))
            ->assertOk()
            ->assertSee('September 2026')
            ->assertSee('Rp 4.000.000')
            ->assertDontSee('Rp 5.000.000');
    }

    public function test_an_empty_month_shows_the_empty_state(): void
    {
        $this->octoberData();

        $this->actingAs($this->user)
            ->get(route('ringkasan', ['bulan' => '2026-01']))
            ->assertOk()
            ->assertSee('Belum ada transaksi bulan ini.')
            ->assertDontSee('Sisa bulan ini');
    }

    public function test_transactions_of_other_accounts_are_not_counted(): void
    {
        $this->octoberData();
        $other = User::factory()->create(['plans' => []]);
        $otherRoom = Room::create(['user_id' => $other->id, 'name' => 'Lain', 'type' => 'mencukupi', 'slot' => 1, 'sort_order' => 1]);
        $otherAccount = Account::create(['user_id' => $other->id, 'name' => 'Dompet', 'type' => 'tunai', 'sort_order' => 1]);
        Transaction::create([
            'user_id' => $other->id, 'account_id' => $otherAccount->id, 'room_id' => $otherRoom->id,
            'type' => Transaction::TYPE_EXPENSE, 'amount_minor' => 7_000_000, 'occurred_on' => Carbon::parse('2026-10-11'),
        ]);

        $this->actingAs($this->user)
            ->get(route('ringkasan', ['bulan' => '2026-10']))
            ->assertDontSee('Rp 7.000.000')
            ->assertSee('Rp 1.200.000');
    }

    public function test_trend_and_compare_stay_locked_for_a_free_account(): void
    {
        $this->actingAs($this->user)
            ->get(route('laporan.bandingkan'))
            ->assertOk()
            ->assertSee('Bandingkan periode adalah fitur Pro.');
    }
}
