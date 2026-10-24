<?php

namespace Tests\Feature;

use App\Livewire\Jadwal;
use App\Models\Account;
use App\Models\Bill;
use App\Models\Category;
use App\Models\RecurringRule;
use App\Models\Room;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Livewire\Livewire;
use Tests\TestCase;

/**
 * Agenda Jadwal: kemunculan 30 hari ke depan dari tagihan (dibayar manual) dan transaksi berulang
 * (dicatat otomatis). Tanggal dibekukan di 24 Oktober 2026; jendela = 24 Okt sampai 22 Nov.
 */
class JadwalTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Room $room;

    private Category $category;

    private Account $account;

    protected function setUp(): void
    {
        parent::setUp();

        Carbon::setTestNow('2026-10-24 09:00:00');

        $this->user = User::factory()->create();
        [$this->room, $this->category, $this->account] = $this->world($this->user);
    }

    protected function tearDown(): void
    {
        Carbon::setTestNow();

        parent::tearDown();
    }

    /** @return array{0: Room, 1: Category, 2: Account} */
    private function world(User $user): array
    {
        $room = Room::create(['user_id' => $user->id, 'name' => 'Keluarga', 'type' => 'mencukupi', 'slot' => 3, 'sort_order' => 1]);
        $category = Category::create(['room_id' => $room->id, 'name' => 'Tagihan rumah', 'sort_order' => 1]);
        $account = Account::create(['user_id' => $user->id, 'name' => 'BCA', 'type' => 'bank', 'balance_minor' => 0, 'sort_order' => 1]);

        return [$room, $category, $account];
    }

    private function bill(string $name, string $due, int $amount = 100_000, array $extra = []): Bill
    {
        return Bill::create($extra + [
            'user_id' => $this->user->id, 'name' => $name, 'amount_minor' => $amount,
            'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'start_date' => $due, 'next_due' => $due,
        ]);
    }

    private function rule(string $type, string $next, int $amount, array $extra = []): RecurringRule
    {
        return RecurringRule::create($extra + [
            'user_id' => $this->user->id, 'type' => $type, 'amount_minor' => $amount, 'account_id' => $this->account->id,
            'room_id' => $type === 'pengeluaran' ? $this->room->id : null,
            'category_id' => $type === 'pengeluaran' ? $this->category->id : null,
            'frequency' => 'bulanan', 'start_date' => $next, 'next_due' => $next,
        ]);
    }

    private function items(?User $user = null)
    {
        return Livewire::actingAs($user ?? $this->user)->test(Jadwal::class)->viewData('items');
    }

    public function test_agenda_merges_bills_and_recurring_rules_in_date_order_with_bills_first_on_a_shared_date(): void
    {
        $this->bill('Listrik', '2026-10-27', 400_000);
        $this->rule('pemasukan', '2026-10-25', 5_000_000, ['source' => 'Gaji']);
        $this->rule('pengeluaran', '2026-10-27', 150_000, ['note' => 'Tabungan sekolah']);

        $items = $this->items();

        $this->assertSame(['Gaji', 'Listrik', 'Tabungan sekolah'], $items->pluck('title')->all());
        $this->assertSame(['berulang', 'tagihan', 'berulang'], $items->pluck('kind')->all());
        $this->assertSame(['masuk', 'keluar', 'keluar'], $items->pluck('direction')->all());
    }

    public function test_overdue_bills_are_listed_first_and_marked_late(): void
    {
        $this->bill('Terlambat', '2026-10-20', 250_000);
        $this->bill('Hari ini', '2026-10-24');
        $this->bill('Segera', '2026-10-26');
        $this->bill('Nanti', '2026-11-10');

        $items = $this->items();

        $this->assertSame(['Terlambat', 'Hari ini', 'Segera', 'Nanti'], $items->pluck('title')->all());
        $this->assertSame(['terlambat', 'hari_ini', 'segera', 'mendatang'], $items->pluck('status')->all());

        $this->actingAs($this->user)->get(route('jadwal'))
            ->assertOk()
            ->assertSee('Terlambat')
            ->assertSee('Lewat jatuh tempo')
            ->assertSee('Tagihan · dibayar manual')
            ->assertSee('Keluarga › Tagihan rumah');
    }

    public function test_window_is_thirty_days_from_today(): void
    {
        $this->bill('Hari terakhir', '2026-11-22');
        $this->bill('Terlalu jauh', '2026-11-23');

        $this->assertSame(['Hari terakhir'], $this->items()->pluck('title')->all());
    }

    public function test_paused_and_finished_bills_and_rules_are_left_out(): void
    {
        $this->bill('Dijeda', '2026-10-26', 100_000, ['active' => false]);
        $this->bill('Lunas', '2026-10-26', 100_000, ['paid_count' => 1]);
        $this->bill('Aktif', '2026-10-26');
        $this->rule('pengeluaran', '2026-10-26', 100_000, ['note' => 'Aturan dijeda', 'active' => false]);
        $this->rule('pengeluaran', '2026-10-26', 100_000, ['note' => 'Aturan selesai', 'end_date' => '2026-10-20']);
        $this->rule('pengeluaran', '2026-10-26', 100_000, ['note' => 'Aturan aktif']);

        $this->assertEqualsCanonicalizing(['Aktif', 'Aturan aktif'], $this->items()->pluck('title')->all());
    }

    public function test_recurring_bills_repeat_within_the_window_and_stop_at_the_last_installment(): void
    {
        // Mingguan: 25 Okt, 1, 8, 15, 22 Nov = 5 kemunculan.
        $this->bill('Mingguan', '2026-10-25', 50_000, ['frequency' => 'mingguan']);
        // Bulanan 3 cicilan, sudah bayar 2: tinggal satu (cicilan ke-3 dari 3).
        $this->bill('Cicilan motor', '2026-10-28', 900_000, ['frequency' => 'bulanan', 'total_installments' => 3, 'paid_count' => 2]);

        $items = $this->items();

        $this->assertCount(5, $items->where('title', 'Mingguan'));
        $this->assertSame(['Cicilan motor (3/3)'], $items->where('kind', 'tagihan')->pluck('title')->filter(fn ($t) => str_starts_with($t, 'Cicilan'))->values()->all());
    }

    public function test_a_rule_overdue_at_its_date_shows_today_as_waiting_to_be_recorded(): void
    {
        $this->rule('pengeluaran', '2026-10-22', 75_000, ['note' => 'Langganan', 'frequency' => 'bulanan']);

        $item = $this->items()->first();

        $this->assertSame('Langganan', $item['title']);
        $this->assertSame('2026-10-24', $item['date']->toDateString());
        $this->assertSame('menunggu', $item['status']);
        $this->assertStringContainsString('Seharusnya 22 Oktober', $item['note']);
    }

    public function test_totals_split_outflow_and_inflow_and_count_late_bills(): void
    {
        $this->bill('Terlambat', '2026-10-20', 250_000);
        $this->bill('Listrik', '2026-10-27', 400_000);
        $this->rule('pemasukan', '2026-10-25', 5_000_000, ['source' => 'Gaji']);

        Livewire::actingAs($this->user)->test(Jadwal::class)
            ->assertViewHas('outMinor', 650_000)
            ->assertViewHas('outCount', 2)
            ->assertViewHas('inMinor', 5_000_000)
            ->assertViewHas('inCount', 1)
            ->assertViewHas('lateCount', 1);

        $this->actingAs($this->user)->get(route('jadwal'))
            ->assertSee('Rp 650.000')
            ->assertSee('Rp 5.000.000')
            ->assertSee('Berulang · dicatat otomatis')
            ->assertSee('+Rp 5.000.000');
    }

    public function test_groups_are_per_date_with_a_weekday_and_day_number(): void
    {
        $this->bill('A', '2026-10-27');
        $this->rule('pengeluaran', '2026-10-27', 10_000, ['note' => 'B']);
        $this->bill('C', '2026-10-30');

        $groups = Livewire::actingAs($this->user)->test(Jadwal::class)->viewData('groups');

        $this->assertCount(2, $groups);
        $this->assertSame('Sel', $groups[0]['day_name']); // 27 Okt 2026 = Selasa
        $this->assertCount(2, $groups[0]['items']);
        $this->assertSame('27 Oktober', $groups[0]['label']);
    }

    public function test_empty_agenda_shows_a_state_with_add_links(): void
    {
        $this->actingAs($this->user)->get(route('jadwal'))
            ->assertOk()
            ->assertSee('Belum ada jadwal 30 hari ke depan')
            ->assertSee(route('tagihan'), false)
            ->assertSee(route('transaksi.berulang'), false);
    }

    public function test_agenda_never_leaks_another_users_items(): void
    {
        $other = User::factory()->create();
        [$room, $category, $account] = $this->world($other);
        Bill::create(['user_id' => $other->id, 'name' => 'Rahasia', 'amount_minor' => 1, 'account_id' => $account->id, 'room_id' => $room->id, 'category_id' => $category->id, 'start_date' => '2026-10-26', 'next_due' => '2026-10-26']);
        RecurringRule::create(['user_id' => $other->id, 'type' => 'pemasukan', 'amount_minor' => 1, 'account_id' => $account->id, 'frequency' => 'bulanan', 'start_date' => '2026-10-26', 'next_due' => '2026-10-26', 'source' => 'Rahasia juga']);
        $this->bill('Milikku', '2026-10-26');

        $this->assertSame(['Milikku'], $this->items()->pluck('title')->all());
        $this->actingAs($this->user)->get(route('jadwal'))->assertDontSee('Rahasia');
    }

    public function test_the_three_tabs_show_on_agenda_bills_and_recurring_pages(): void
    {
        $this->bill('Listrik', '2026-10-26');
        $this->rule('pengeluaran', '2026-10-26', 10_000);

        foreach ([route('jadwal'), route('tagihan'), route('transaksi.berulang')] as $url) {
            $this->actingAs($this->user)->get($url)
                ->assertOk()
                ->assertSee('Agenda')
                ->assertSee('Berulang')
                ->assertSee('href="'.route('tagihan').'"', false)
                ->assertSee('href="'.route('transaksi.berulang').'"', false)
                ->assertSee('aria-label="Jadwal"', false);
        }
    }
}
