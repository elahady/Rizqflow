<?php

namespace Tests\Feature;

use App\Livewire\Aliran;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Support\Ledger;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

/**
 * Analisa › Aliran (diagram Sankey, Pro). "Hari ini" dibekukan ke 24 Oktober 2026 seperti mockup.
 */
class AliranTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Account $account;

    /** @var array<string, Room> */
    private array $rooms = [];

    /** @var array<string, Category> */
    private array $pos = [];

    protected function setUp(): void
    {
        parent::setUp();

        $this->travelTo(CarbonImmutable::parse('2026-10-24 10:00'));
        $this->user = $this->userWithRooms();
        $this->account = $this->user->accounts()->first();
        foreach ($this->user->rooms()->get() as $room) {
            $this->rooms[$room->name] = $room;
        }
        $this->pos = $this->posFor($this->user);
    }

    /** Pengguna dengan tiga ruang 10/30/60 beserta satu akun; Pro bila $pro. */
    private function userWithRooms(bool $pro = true): User
    {
        $user = User::factory()->create($pro ? ['plans' => ['pro']] : []);
        Account::factory()->create(['user_id' => $user->id, 'name' => 'BCA']);
        Room::factory()->percent(10)->type(Room::TYPE_MENUNAIKAN)->create(['user_id' => $user->id, 'name' => 'Memberi', 'slot' => 1, 'sort_order' => 1, 'icon' => 'memberi']);
        Room::factory()->percent(30)->type(Room::TYPE_MENUMBUHKAN)->create(['user_id' => $user->id, 'name' => 'Diri', 'slot' => 2, 'sort_order' => 2, 'icon' => 'diri']);
        Room::factory()->percent(60)->create(['user_id' => $user->id, 'name' => 'Keluarga', 'slot' => 3, 'sort_order' => 3, 'icon' => 'keluarga']);

        return $user;
    }

    /** @return array<string, Category> */
    private function posFor(User $user): array
    {
        $pos = [];
        foreach ($user->rooms()->get() as $room) {
            $pos[$room->name.'/utama'] = Category::factory()->create(['room_id' => $room->id, 'name' => 'Pos '.$room->name, 'sort_order' => 1]);
            $pos[$room->name.'/tak'] = Category::factory()->untracked()->create(['room_id' => $room->id, 'sort_order' => 2]);
        }

        return $pos;
    }

    private function income(int $amount, string $date = '2026-10-22', string $source = 'Gaji', ?string $note = null, ?User $user = null): Transaction
    {
        $user ??= $this->user;

        return Ledger::recordIncome($user, $amount, $user->accounts()->first(), CarbonImmutable::parse($date), $source, $note);
    }

    private function spend(string $pos, int $amount, string $date = '2026-10-10', ?User $user = null, ?array $categories = null): Transaction
    {
        $user ??= $this->user;

        return Ledger::recordExpense($user, $amount, ($categories ?? $this->pos)[$pos], $user->accounts()->first(), CarbonImmutable::parse($date), null);
    }

    private function mockupMonth(): void
    {
        $this->income(18_000_000, note: 'Gaji Oktober');
        $this->spend('Memberi/utama', 1_800_000);
        $this->spend('Diri/utama', 1_092_000);
        $this->spend('Keluarga/utama', 4_320_000);
        $this->spend('Keluarga/tak', 864_000);
    }

    private function aliran(array $query = [])
    {
        return Livewire::actingAs($this->user)->withQueryParams($query)->test(Aliran::class);
    }

    public function test_guest_is_sent_to_login(): void
    {
        $this->get(route('laporan'))->assertRedirect(route('login'));
    }

    public function test_free_user_sees_the_pro_upsell_and_no_diagram(): void
    {
        $free = $this->userWithRooms(pro: false);
        $this->income(18_000_000, user: $free);

        $this->actingAs($free)->get(route('laporan'))
            ->assertOk()
            ->assertSee('Aliran rezeki adalah fitur Pro.')
            ->assertSee('rf-upsell', false)
            ->assertDontSee('Rincian (tabel kembaran grafik)')
            ->assertDontSee('<svg class="al-svg"', false)
            ->assertDontSee('18.000.000');
    }

    public function test_free_user_component_computes_no_flow(): void
    {
        $free = $this->userWithRooms(pro: false);

        Livewire::actingAs($free)->test(Aliran::class)->assertViewHas('flow', null);
    }

    public function test_pro_user_sees_the_header_tabs_range_chips_and_diagram(): void
    {
        $this->mockupMonth();

        $this->actingAs($this->user)->get(route('laporan'))
            ->assertOk()
            ->assertSee('Oktober 2026')
            ->assertSee('Analisa')
            ->assertSee('Fitur Pro')
            ->assertSee('Jenis analisa')
            ->assertSee('Bulan ini')
            ->assertSee('3 bulan')
            ->assertSee('12 bulan')
            ->assertSee('Lebar pita sebanding dengan rupiah. Total Rp 18.000.000.')
            ->assertSee('<svg class="al-svg"', false)
            ->assertSee('SUMBER')
            ->assertSee('POS DAN SISA')
            ->assertSee('Sorot ruang')
            ->assertSee('Rincian (tabel kembaran grafik)')
            ->assertSee('Yang terlihat bulan ini');
    }

    public function test_table_matches_the_mockup_numbers_and_the_diagram(): void
    {
        $this->mockupMonth();

        $component = $this->aliran();
        $flow = $component->viewData('flow');

        // Tabel kembaran: angka dari pos, sisa, dan total harus sama dengan node diagram.
        $component
            ->assertSee('Pos Memberi')->assertSee('1.800.000')->assertSee('100,0%')->assertSee('10,0%')
            ->assertSee('Pos Diri')->assertSee('1.092.000')->assertSee('20,2%')->assertSee('6,1%')
            ->assertSee('4.308.000')->assertSee('79,8%')->assertSee('23,9%')
            ->assertSee('Pos Keluarga')->assertSee('4.320.000')->assertSee('40,0%')->assertSee('24,0%')
            ->assertSee('Tak terlacak')->assertSee('864.000')->assertSee('8,0%')->assertSee('4,8%')
            ->assertSee('Sisa belum terpakai')->assertSee('5.616.000')->assertSee('52,0%')->assertSee('31,2%')
            ->assertSee('Total rezeki')->assertSee('18.000.000')->assertSee('100%');

        $sum = 0;
        foreach ($flow['rows'] as $row) {
            $sum += $row['amount'];
        }
        $this->assertSame(18_000_000, $flow['layout']['total']);
        $this->assertSame(18_000_000, $sum, 'jumlah baris tabel = total rezeki bila tidak ada ruang melewati jatah');
        $this->assertSame(1_800_000 + 1_092_000 + 4_320_000 + 864_000, $flow['layout']['spent']);

        $nodes = 0;
        foreach ($flow['layout']['rooms'] as $room) {
            $nodes += array_sum(array_column($room['pos'], 'amount')) + $room['remaining'];
        }
        $this->assertSame($sum, $nodes);
    }

    public function test_readouts_are_computed_for_every_focus(): void
    {
        $this->mockupMonth();

        $readouts = $this->aliran()->viewData('flow')['readouts'];

        $this->assertSame(['semua', 'r'.$this->rooms['Memberi']->id, 'r'.$this->rooms['Diri']->id, 'r'.$this->rooms['Keluarga']->id], array_keys($readouts));
        $this->assertSame('Semua ruang: Rp 8.076.000 terpakai dari Rp 18.000.000 rezeki (44%); sisa belum terpakai Rp 9.924.000.', $readouts['semua']);
        $this->assertSame('Memberi: Rp 1.800.000 dari Rp 1.800.000 terpakai (100%), jatah terpenuhi. Pos terbesar: Pos Memberi, 100% dari yang terpakai.', $readouts['r'.$this->rooms['Memberi']->id]);
        $this->assertStringStartsWith('Diri: Rp 1.092.000 dari Rp 5.400.000 terpakai (20%); sisa Rp 4.308.000.', $readouts['r'.$this->rooms['Diri']->id]);
        $this->assertStringContainsString('Pos terbesar: Pos Keluarga, 83% dari yang terpakai.', $readouts['r'.$this->rooms['Keluarga']->id]);
    }

    public function test_insights_name_the_biggest_room_the_biggest_leftover_and_untracked_with_a_link(): void
    {
        $this->mockupMonth();

        $this->aliran()
            ->assertSee('Keluarga menyerap 60% rezeki.')
            ->assertSee('83% jatuh ke Pos Keluarga.')
            ->assertSee('Keluarga masih menyisakan Rp 5.616.000, yaitu 52% dari jatahnya.')
            ->assertSee('Rp 864.000 masih Tak terlacak (11% dari seluruh pengeluaran). Rapikan lewat', false)
            ->assertSeeHtml('transaksi?tab=tak&amp;bulan=2026-10');
    }

    public function test_income_that_was_not_flowed_to_a_room_gets_its_own_node_and_row(): void
    {
        $this->income(18_000_000);
        // Pemasukan kedua tanpa potret alokasi (mis. tercatat sebelum ruang ada).
        Transaction::create([
            'user_id' => $this->user->id, 'account_id' => $this->account->id, 'type' => Transaction::TYPE_INCOME,
            'amount_minor' => 2_000_000, 'category' => 'Usaha', 'occurred_on' => '2026-10-15',
        ]);

        $component = $this->aliran()
            ->assertSee('Belum dialirkan')
            ->assertSee('Lebar pita sebanding dengan rupiah. Total Rp 20.000.000.');
        $flow = $component->viewData('flow');

        $this->assertSame(2_000_000, $flow['layout']['unallocated']['amount']);
        $row = collect($flow['rows'])->firstWhere('room', 'Belum dialirkan');
        $this->assertSame(2_000_000, $row['amount']);
        $this->assertSame('10,0%', $row['ofTotal']);
        $this->assertSame(20_000_000, collect($flow['rows'])->sum('amount'));
    }

    public function test_room_over_its_allocation_is_shown_honestly(): void
    {
        $this->income(10_000_000);              // Memberi 1 jt
        $this->spend('Memberi/utama', 1_500_000);

        $component = $this->aliran()
            ->assertSee('Melewati jatah')
            ->assertSee('Memberi terpakai Rp 500.000 di atas jatahnya (Rp 1.000.000).')
            ->assertSee('ikut terhitung di baris pos', false);
        $flow = $component->viewData('flow');

        $memberi = collect($flow['layout']['rooms'])->firstWhere('label', 'Memberi');
        $this->assertSame([1_000_000, 1_500_000, 500_000, 0], [$memberi['allocated'], $memberi['spent'], $memberi['over'], $memberi['remaining']]);
        $this->assertNotNull($memberi['over_y']);
        // Tabel: pos tampil dengan angka penuh dan persen jatah lebih dari 100.
        $row = collect($flow['rows'])->firstWhere('pos', 'Pos Memberi');
        $this->assertSame(1_500_000, $row['amount']);
        $this->assertSame('150,0%', $row['ofRoom']);
        $this->assertSame(500_000, $flow['overNote']);
    }

    public function test_empty_range_shows_a_quiet_empty_state(): void
    {
        $this->spend('Diri/utama', 100_000);   // ada pengeluaran, tapi tidak ada pemasukan

        $this->aliran()
            ->assertSee('Belum ada rezeki yang dicatat pada Oktober 2026.')
            ->assertSee('rf-empty', false)
            ->assertSee(route('transaksi.rezeki'), false)
            ->assertDontSee('<svg class="al-svg"', false)
            ->assertDontSee('Rincian (tabel kembaran grafik)');
    }

    public function test_range_of_three_and_twelve_months_ends_at_the_selected_month(): void
    {
        $this->income(10_000_000, '2026-10-05');
        $this->income(20_000_000, '2026-08-05');
        $this->income(30_000_000, '2026-02-05');
        $this->income(40_000_000, '2025-11-05');      // tepat di luar 12 bulan (Nov 2025 .. Okt 2026 masih termasuk)
        $this->income(50_000_000, '2025-10-05');      // di luar 12 bulan

        $this->aliran()->assertSee('Total Rp 10.000.000.')->assertSee('Oktober 2026');

        $three = $this->aliran(['rentang' => '3']);
        $three->assertSee('Agustus 2026 – Oktober 2026')->assertSee('Total Rp 30.000.000.');

        $twelve = $this->aliran(['rentang' => '12']);
        $twelve->assertSee('November 2025 – Oktober 2026')->assertSee('Total Rp 100.000.000.');

        // Berakhir di bulan terpilih, bukan bulan berjalan.
        $this->aliran(['bulan' => '2026-08', 'rentang' => '3'])
            ->assertSee('Juni 2026 – Agustus 2026')->assertSee('Total Rp 20.000.000.');
    }

    public function test_range_chips_switch_the_range_and_reset_the_focus_key(): void
    {
        $this->income(10_000_000, '2026-10-05');
        $this->income(20_000_000, '2026-09-05');

        $this->aliran()
            ->assertSee('Total Rp 10.000.000.')
            ->call('setRentang', '3')
            ->assertSet('rentang', '3')
            ->assertSee('Total Rp 30.000.000.')
            ->call('setRentang', '12')
            ->assertSet('rentang', '12')
            ->call('setRentang', 'ngawur')
            ->assertSet('rentang', '1')
            ->assertSee('Total Rp 10.000.000.');
    }

    public function test_invalid_range_in_the_url_falls_back_to_one_month(): void
    {
        $this->income(10_000_000, '2026-10-05');
        $this->income(20_000_000, '2026-09-05');

        $this->aliran(['rentang' => '7'])->assertSee('Total Rp 10.000.000.');
    }

    public function test_many_sources_are_grouped_and_the_rest_become_lainnya(): void
    {
        foreach (['Gaji', 'Usaha', 'Hadiah', 'Bonus', 'Freelance', 'Dividen', 'Sewa', 'Jual barang'] as $i => $source) {
            $this->income(1_000_000 * ($i + 1), '2026-10-05', $source);
        }
        $this->income(500_000, '2026-10-06', 'Gaji');        // dikelompokkan dengan Gaji

        $flow = $this->aliran()->viewData('flow');
        $sources = $flow['layout']['sources'];

        $this->assertCount(6, $sources);
        $this->assertSame('Lainnya', end($sources)['label']);
        $this->assertSame(36_000_000 + 500_000, array_sum(array_column($sources, 'amount')));
        $this->assertSame(36_500_000, $flow['layout']['total']);
        $this->assertSame(36_500_000, $flow['layout']['allocated']);
    }

    public function test_a_single_income_uses_its_note_and_account_as_the_source_label(): void
    {
        $this->income(18_000_000, '2026-10-22', 'Gaji', 'Gaji Oktober');

        $sources = $this->aliran()->viewData('flow')['layout']['sources'];

        $this->assertSame('Gaji Oktober', $sources[0]['label']);
        $this->assertSame('22 Okt · BCA', $sources[0]['detail']);
    }

    public function test_pos_per_room_are_limited_and_the_rest_become_pos_lainnya(): void
    {
        $this->income(100_000_000);
        $room = $this->rooms['Keluarga'];
        foreach (range(1, 10) as $i) {
            $category = Category::factory()->create(['room_id' => $room->id, 'name' => "Pos tambahan $i", 'sort_order' => 10 + $i]);
            Ledger::recordExpense($this->user, $i * 100_000, $category, $this->account, CarbonImmutable::parse('2026-10-10'), null);
        }

        $flow = $this->aliran()->viewData('flow');
        $keluarga = collect($flow['layout']['rooms'])->firstWhere('label', 'Keluarga');

        $this->assertCount(7, $keluarga['pos']);
        $this->assertSame('Pos lainnya', end($keluarga['pos'])['label']);
        $this->assertSame(array_sum(range(1, 10)) * 100_000, $keluarga['spent']);
    }

    public function test_expenses_outside_the_range_do_not_count(): void
    {
        $this->income(10_000_000, '2026-10-05');
        $this->spend('Diri/utama', 100_000, '2026-10-31');
        $this->spend('Diri/utama', 200_000, '2026-09-30');   // bulan lalu: di luar rentang 1 bulan
        $this->spend('Diri/utama', 400_000, '2026-11-01');   // bulan depan

        $flow = $this->aliran()->viewData('flow');
        $this->assertSame(100_000, $flow['layout']['spent']);

        $this->assertSame(300_000, $this->aliran(['rentang' => '3'])->viewData('flow')['layout']['spent']);
    }

    public function test_archived_room_keeps_its_flow_visible_but_is_not_a_focus_option(): void
    {
        $this->income(10_000_000);
        $this->rooms['Memberi']->update(['archived' => true]);

        $component = $this->aliran()->assertSee('Memberi');
        $flow = $component->viewData('flow');

        $this->assertSame(10_000_000 - 1_000_000 + 1_000_000, $flow['layout']['allocated']);   // jatah arsip tetap terhitung
        $this->assertNotContains('Memberi', array_column($flow['focus'], 'name'));
    }

    public function test_it_does_not_leak_other_users_data(): void
    {
        $this->income(18_000_000);

        $other = $this->userWithRooms();
        $otherPos = $this->posFor($other);
        $this->income(99_000_000, '2026-10-20', 'Rahasia', 'Bonus orang lain', $other);
        $this->spend('Diri/utama', 7_700_000, '2026-10-21', $other, $otherPos);

        $component = $this->aliran()
            ->assertSee('Total Rp 18.000.000.')
            ->assertDontSee('Rahasia')
            ->assertDontSee('Bonus orang lain')
            ->assertDontSee('99.000.000')
            ->assertDontSee('7.700.000');

        $flow = $component->viewData('flow');
        $this->assertSame(18_000_000, $flow['layout']['total']);
        $this->assertSame(0, $flow['layout']['spent']);
        $this->assertCount(3, $flow['focus']);

        // Pengguna lain melihat datanya sendiri, bukan milik kita.
        $theirs = Livewire::actingAs($other)->test(Aliran::class)->viewData('flow');
        $this->assertSame(99_000_000, $theirs['layout']['total']);
        $this->assertSame(7_700_000, $theirs['layout']['spent']);
    }

    public function test_allocation_entries_pointing_at_another_users_room_are_ignored(): void
    {
        $income = $this->income(10_000_000);
        $other = $this->userWithRooms();
        $foreignRoom = $other->rooms()->first();
        // Data korup: potret menunjuk ruang milik pengguna lain. Tidak boleh bocor atau merusak diagram.
        $income->allocationEntries()->create(['room_id' => $foreignRoom->id, 'share_bp' => 0, 'amount_minor' => 123_456]);

        $flow = $this->aliran()->viewData('flow');

        $this->assertSame(10_000_000, $flow['layout']['allocated']);
        $this->assertNotContains($foreignRoom->name.'-x', array_column($flow['layout']['rooms'], 'label'));
        $this->assertCount(3, $flow['layout']['rooms']);
    }

    public function test_past_month_uses_a_neutral_heading_and_no_current_month_label(): void
    {
        $this->income(10_000_000, '2026-09-05');

        $this->aliran(['bulan' => '2026-09'])
            ->assertSee('September 2026')
            ->assertSee('Yang terlihat pada rentang ini')
            ->assertSee('1 bulan');
    }
}
