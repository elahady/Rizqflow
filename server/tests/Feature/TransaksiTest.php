<?php

namespace Tests\Feature;

use App\Livewire\Transaksi;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Support\Ledger;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

/** Halaman Transaksi: baris catat cepat, tab, filter, pencarian lintas periode, dan aksi massal. */
class TransaksiTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Room $diri;

    private Room $keluarga;

    private Category $jajan;

    private Category $dapur;

    private Category $tak;

    private Account $bank;

    private Account $gopay;

    protected function setUp(): void
    {
        parent::setUp();

        $this->travelTo('2026-10-24 10:00:00');

        $this->user = User::factory()->create();
        $this->diri = Room::create(['user_id' => $this->user->id, 'name' => 'Diri', 'type' => 'menumbuhkan', 'slot' => 2, 'sort_order' => 1, 'allocation_percent' => 30]);
        $this->keluarga = Room::create(['user_id' => $this->user->id, 'name' => 'Keluarga', 'type' => 'mencukupi', 'slot' => 3, 'sort_order' => 2, 'allocation_percent' => 70]);
        $this->jajan = Category::create(['room_id' => $this->diri->id, 'name' => 'Jajan dan hobi', 'sort_order' => 1]);
        $this->dapur = Category::create(['room_id' => $this->keluarga->id, 'name' => 'Dapur dan belanja', 'sort_order' => 1]);
        $this->tak = Category::create(['room_id' => $this->keluarga->id, 'name' => Category::UNTRACKED, 'is_system' => true, 'sort_order' => 2]);
        $this->bank = Account::create(['user_id' => $this->user->id, 'name' => 'BCA', 'type' => 'bank', 'balance_minor' => 1_000_000, 'sort_order' => 1]);
        $this->gopay = Account::create(['user_id' => $this->user->id, 'name' => 'GoPay', 'type' => 'e_wallet', 'balance_minor' => 500_000, 'sort_order' => 2]);
    }

    private function expense(Category $category, int $amount, ?string $note, string $date = '2026-10-20', ?Account $account = null): Transaction
    {
        return Ledger::recordExpense($this->user, $amount, $category, $account ?? $this->bank, \Illuminate\Support\Carbon::parse($date), $note);
    }

    /** Riwayat supaya "kopi" dikenali sebagai Diri › Jajan dan hobi. */
    private function seedKopiHistory(): void
    {
        $this->expense($this->jajan, 28_000, 'kopi susu', '2026-10-10');
    }

    private function page(array $query = [])
    {
        return Livewire::withQueryParams($query)->actingAs($this->user)->test(Transaksi::class);
    }

    // --- Halaman ---

    public function test_page_requires_authentication_and_renders_the_quick_card(): void
    {
        $this->get(route('transaksi'))->assertRedirect(route('login'));

        $this->actingAs($this->user)->get(route('transaksi'))
            ->assertOk()
            ->assertSee('id="catat"', false)
            ->assertSee('id="catat-catatan"', false)
            ->assertSee('Catat pengeluaran')
            ->assertSee(route('laporan.impor-csv'), false)
            ->assertSee(route('transaksi.ekspor'), false)
            ->assertSee(route('transaksi.rezeki'), false)
            ->assertSee('Oktober 2026');
    }

    // --- Catat cepat ---

    public function test_quick_entry_reads_the_note_and_saves_an_expense(): void
    {
        $this->seedKopiHistory();

        $component = $this->page()
            ->set('quickNote', 'kopi 25rb gopay')
            ->assertSet('quickAmount', '25.000')
            ->assertSet('quickAccountId', $this->gopay->id)
            ->assertSet('quickCategoryId', $this->jajan->id)
            ->assertSee('Terbaca otomatis:')
            ->assertSee('Diri › Jajan dan hobi (dari catatan serupa)');

        $component->call('save')->assertSet('quickError', null)->assertSet('quickNote', '')->assertSet('quickAmount', '');

        $tx = Transaction::where('amount_minor', 25_000)->firstOrFail();
        $this->assertSame('kopi', $tx->note); // catatan bersih hasil parse
        $this->assertSame($this->gopay->id, $tx->account_id);
        $this->assertSame($this->jajan->id, $tx->category_id);
        $this->assertSame($this->diri->id, $tx->room_id);
        $this->assertSame('2026-10-24', $tx->occurred_on->toDateString());
        $this->assertSame(500_000 - 25_000, $this->gopay->refresh()->balance_minor);
        $component->assertSee('Tersimpan:');
    }

    public function test_quick_entry_defaults_to_the_last_account_and_pos_when_nothing_is_read(): void
    {
        $this->expense($this->dapur, 100_000, 'belanja', '2026-10-21', $this->gopay);

        $this->page()
            ->assertSet('quickAccountId', $this->gopay->id)
            ->assertSet('quickCategoryId', $this->dapur->id)
            ->assertSet('quickDate', '2026-10-24');
    }

    public function test_manual_choices_are_not_overwritten_by_the_parser(): void
    {
        $this->seedKopiHistory();

        $this->page()
            ->set('quickAmount', '30000')
            ->set('quickAccountId', $this->bank->id)
            ->set('quickCategoryId', $this->dapur->id)
            ->set('quickNote', 'kopi 25rb gopay')
            ->assertSet('quickAmount', '30000')
            ->assertSet('quickAccountId', $this->bank->id)
            ->assertSet('quickCategoryId', $this->dapur->id)
            ->call('save');

        $tx = Transaction::where('amount_minor', 30_000)->firstOrFail();
        $this->assertSame($this->bank->id, $tx->account_id);
        $this->assertSame($this->dapur->id, $tx->category_id);
        $this->assertSame('kopi', $tx->note);
    }

    public function test_plain_text_without_a_readable_amount_is_saved_as_is_with_the_typed_amount(): void
    {
        $this->page()
            ->set('quickNote', 'Parkir kantor')
            ->set('quickAmount', '5.000')
            ->call('save')
            ->assertSet('quickError', null);

        $this->assertSame('Parkir kantor', Transaction::firstOrFail()->note);
        $this->assertSame(5_000, Transaction::firstOrFail()->amount_minor);
    }

    public function test_shift_enter_keeps_choices_and_refocuses_while_enter_resets_them(): void
    {
        $this->page()
            ->set('quickDate', '2026-10-15')
            ->set('quickCategoryId', $this->dapur->id)
            ->set('quickAccountId', $this->gopay->id)
            ->set('quickNote', 'sayur')
            ->set('quickAmount', '12000')
            ->call('save', true)
            ->assertDispatched('catat-fokus')
            ->assertSet('quickNote', '')
            ->assertSet('quickAmount', '')
            ->assertSet('quickDate', '2026-10-15')
            ->assertSet('quickCategoryId', $this->dapur->id)
            ->assertSet('quickAccountId', $this->gopay->id)
            ->set('quickNote', 'tahu')
            ->set('quickAmount', '8000')
            ->call('save')
            ->assertNotDispatched('catat-fokus')
            ->assertSet('quickDate', '2026-10-24');

        $this->assertSame(2, Transaction::count());
        $this->assertSame('2026-10-15', Transaction::where('note', 'sayur')->firstOrFail()->occurred_on->toDateString());
    }

    public function test_prefill_from_the_command_box_reads_the_note_and_asks_for_focus(): void
    {
        $this->seedKopiHistory();

        $this->page(['catat' => 'kopi 25rb gopay'])
            ->assertSet('quickNote', 'kopi 25rb gopay')
            ->assertSet('quickAmount', '25.000')
            ->assertSet('quickAccountId', $this->gopay->id)
            ->assertSet('focusNote', true);

        $this->page()->assertSet('focusNote', false);
    }

    public function test_income_note_points_to_the_income_page_and_is_not_saved_as_an_expense(): void
    {
        $component = $this->page()
            ->set('quickNote', 'gaji 5jt')
            ->assertSee('terbaca sebagai pemasukan')
            ->assertSee(route('transaksi.rezeki', ['nominal' => 5_000_000, 'catatan' => 'gaji']));

        $component->call('save')->assertSee('Catatan ini terbaca sebagai pemasukan.');

        $this->assertSame(0, Transaction::count());
    }

    public function test_transfer_note_says_transfers_are_not_supported_on_the_web(): void
    {
        $this->page()
            ->set('quickNote', 'transfer 100rb dari bca ke gopay')
            ->assertSee('Transfer belum didukung di web')
            ->call('save')
            ->assertSee('Transfer antar akun belum didukung di web');

        $this->assertSame(0, Transaction::count());
    }

    public function test_save_shows_ledger_and_selection_errors_and_never_uses_other_users_data(): void
    {
        $this->page()->set('quickAmount', '0')->call('save')->assertSet('quickError', 'Nominal wajib lebih dari nol.');

        $other = User::factory()->create();
        $otherRoom = Room::create(['user_id' => $other->id, 'name' => 'Lain', 'type' => 'mencukupi', 'slot' => 1, 'sort_order' => 1]);
        $otherCategory = Category::create(['room_id' => $otherRoom->id, 'name' => 'Pos lain', 'sort_order' => 1]);
        $otherAccount = Account::create(['user_id' => $other->id, 'name' => 'Akun lain', 'type' => 'tunai', 'sort_order' => 1]);

        $this->page()->set('quickAmount', '1000')->set('quickCategoryId', $otherCategory->id)->call('save')
            ->assertSet('quickError', 'Pilih ruang dan pos.');
        $this->page()->set('quickAmount', '1000')->set('quickAccountId', $otherAccount->id)->call('save')
            ->assertSet('quickError', 'Pilih akun.');
        $this->page()->set('quickAmount', '1000')->set('quickDate', '2026-13-45')->call('save')
            ->assertSet('quickError', 'Tanggal tidak sah.');
        $this->page()->set('quickAmount', '1000')->set('quickNote', str_repeat('x', 201))->call('save')
            ->assertSet('quickError', 'Catatan paling banyak 200 karakter.');

        $this->assertSame(0, Transaction::count());
    }

    public function test_quick_card_explains_what_is_missing_without_rooms_or_accounts(): void
    {
        $fresh = User::factory()->create();

        Livewire::actingAs($fresh)->test(Transaksi::class)->assertSee('Belum ada ruang dan pos.');

        Room::create(['user_id' => $fresh->id, 'name' => 'Diri', 'type' => 'menumbuhkan', 'slot' => 2, 'sort_order' => 1]);
        Livewire::actingAs($fresh)->test(Transaksi::class)->assertSee('Belum ada akun.');
    }

    // --- Tab, periode, dan filter ---

    public function test_untracked_tab_lists_only_expenses_in_the_untracked_pos(): void
    {
        $this->expense($this->tak, 289_000, 'QRIS Indomaret');
        $this->expense($this->dapur, 187_500, 'Belanja pasar');

        $this->page()
            ->assertSee('QRIS Indomaret')->assertSee('Belanja pasar')
            ->call('setTab', 'tak')
            ->assertSet('tab', 'tak')
            ->assertSee('QRIS Indomaret')
            ->assertDontSee('Belanja pasar');

        $this->page(['tab' => 'tak'])->assertSee('QRIS Indomaret')->assertDontSee('Belanja pasar');
    }

    public function test_untracked_rows_are_marked_as_needing_attention(): void
    {
        $this->expense($this->tak, 289_000, 'QRIS Indomaret');

        $this->page()->assertSeeHtml('class="need"');
    }

    public function test_the_list_shows_one_month_and_the_period_query_switches_it(): void
    {
        $this->expense($this->dapur, 100_000, 'Belanja Oktober', '2026-10-05');
        $this->expense($this->dapur, 100_000, 'Belanja September', '2026-09-05');

        $this->page()->assertSee('Belanja Oktober')->assertDontSee('Belanja September');
        $this->page(['bulan' => '2026-09'])->assertSee('Belanja September')->assertDontSee('Belanja Oktober');
    }

    public function test_filters_by_room_pos_account_and_type(): void
    {
        $this->expense($this->jajan, 28_000, 'Kopi susu', '2026-10-20', $this->gopay);
        $this->expense($this->dapur, 187_500, 'Belanja pasar', '2026-10-21', $this->bank);
        Ledger::recordIncome($this->user, 1_000_000, $this->bank, now(), 'Gaji', 'Gaji Oktober');

        $this->page()->set('ruang', (string) $this->diri->id)
            ->assertSee('Kopi susu')->assertDontSee('Belanja pasar')->assertDontSee('Gaji Oktober');

        $this->page()->set('pos', (string) $this->dapur->id)
            ->assertSee('Belanja pasar')->assertDontSee('Kopi susu');

        $this->page()->set('akun', (string) $this->gopay->id)
            ->assertSee('Kopi susu')->assertDontSee('Belanja pasar');

        $this->page()->set('jenis', 'pemasukan')
            ->assertSee('Gaji Oktober')->assertDontSee('Kopi susu');
        $this->page()->set('jenis', 'pengeluaran')
            ->assertSee('Kopi susu')->assertDontSee('Gaji Oktober');
    }

    public function test_changing_the_room_clears_a_pos_that_belongs_to_another_room(): void
    {
        $this->page()
            ->set('pos', (string) $this->dapur->id)
            ->set('ruang', (string) $this->diri->id)
            ->assertSet('pos', '');
    }

    public function test_searching_ignores_the_month_and_says_so(): void
    {
        $this->expense($this->dapur, 100_000, 'Belanja lama', '2025-03-05');
        $this->expense($this->dapur, 100_000, 'Token listrik', '2026-10-05');

        $this->page(['cari' => 'belanja'])
            ->assertSee('Hasil pencarian di semua periode')
            ->assertSee('Belanja lama')
            ->assertSee('5 Mar 2025')
            ->assertDontSee('Token listrik');
    }

    public function test_income_rows_show_the_allocation_and_unallocated_income_says_so(): void
    {
        Ledger::recordIncome($this->user, 18_000_000, $this->bank, now(), 'Gaji', 'Gaji Oktober');
        $this->diri->update(['allocation_percent' => 0]);
        $this->keluarga->update(['allocation_percent' => 0]);
        Ledger::recordIncome($this->user, 500_000, $this->bank, now(), 'Usaha', 'Jual barang');

        $this->page()
            ->assertSee('Dialirkan 30 / 70')
            ->assertSee('+Rp 18.000.000')
            ->assertSee('Belum dialirkan');
    }

    public function test_recurring_and_bill_marks_show_under_the_note(): void
    {
        $tx = $this->expense($this->dapur, 385_000, 'Internet rumah');
        $rule = $this->user->recurringRules()->create([
            'type' => 'pengeluaran', 'amount_minor' => 385_000, 'frequency' => 'bulanan', 'start_date' => '2026-10-15', 'next_due' => '2026-11-15',
            'account_id' => $this->bank->id, 'category_id' => $this->dapur->id, 'room_id' => $this->keluarga->id, 'note' => 'Internet',
        ]);
        $tx->update(['recurring_rule_id' => $rule->id]);

        $this->page()->assertSee('Berulang');
    }

    public function test_show_more_raises_the_limit_by_fifty(): void
    {
        for ($i = 1; $i <= 55; $i++) {
            $this->expense($this->dapur, 1_000 + $i, 'Item '.$i, '2026-10-10');
        }

        $this->page()
            ->assertSee('Menampilkan 50 dari 55 transaksi')
            ->call('showMore')
            ->assertSee('Menampilkan 55 dari 55 transaksi');
    }

    public function test_footer_totals_cover_the_whole_filtered_set(): void
    {
        $this->expense($this->dapur, 100_000, 'A');
        $this->expense($this->dapur, 50_000, 'B');
        Ledger::recordIncome($this->user, 1_000_000, $this->bank, now(), 'Gaji', 'Gaji');

        $this->page()->assertSeeHtml('<b>Rp 150.000</b>')->assertSeeHtml('<b>Rp 1.000.000</b>');
    }

    // --- Pilihan dan aksi massal ---

    public function test_select_toggle_all_and_clear(): void
    {
        $a = $this->expense($this->dapur, 100_000, 'A');
        $b = $this->expense($this->dapur, 50_000, 'B');

        $this->page()
            ->call('toggleRow', $a->id)
            ->assertSet('selected', [$a->id])
            ->assertSee('1 dipilih')
            ->call('toggleAll')
            ->assertSet('selected', [$a->id, $b->id])
            ->assertSee('2 dipilih')
            ->assertSee('Keluar Rp 150.000')
            ->call('toggleAll')
            ->assertSet('selected', [])
            ->call('toggleRow', $b->id)
            ->call('clearSelection')
            ->assertSet('selected', []);
    }

    public function test_bulk_change_pos_moves_expenses_and_skips_income(): void
    {
        $a = $this->expense($this->tak, 100_000, 'A');
        $b = $this->expense($this->dapur, 50_000, 'B');
        $income = Ledger::recordIncome($this->user, 1_000_000, $this->bank, now(), 'Gaji', 'Gaji');

        $this->page()
            ->set('selected', [$a->id, $b->id, $income->id])
            ->set('bulkCategoryId', (string) $this->jajan->id)
            ->call('applyBulkCategory')
            ->assertSee('2 pengeluaran dipindahkan ke Diri › Jajan dan hobi.')
            ->assertSee('1 pemasukan dilewati')
            ->assertSet('selected', []);

        $this->assertSame($this->jajan->id, $a->refresh()->category_id);
        $this->assertSame($this->diri->id, $a->room_id);
        $this->assertSame('Jajan dan hobi', $b->refresh()->category);
        $this->assertNull($income->refresh()->room_id);
    }

    public function test_bulk_move_account_refunds_the_old_one(): void
    {
        $a = $this->expense($this->dapur, 100_000, 'A'); // BCA 1.000.000 -> 900.000
        $income = Ledger::recordIncome($this->user, 200_000, $this->bank, now(), 'Gaji', 'G'); // BCA -> 1.100.000

        $this->page()
            ->set('selected', [$a->id, $income->id])
            ->set('bulkAccountId', (string) $this->gopay->id)
            ->call('applyBulkAccount')
            ->assertSee('2 transaksi dipindahkan ke akun GoPay.');

        $this->assertSame(1_000_000, $this->bank->refresh()->balance_minor);
        $this->assertSame(500_000 - 100_000 + 200_000, $this->gopay->refresh()->balance_minor);
    }

    public function test_bulk_delete_reverses_balances(): void
    {
        $a = $this->expense($this->dapur, 100_000, 'A');
        $b = $this->expense($this->dapur, 50_000, 'B');

        $this->page()
            ->set('selected', [$a->id, $b->id])
            ->call('deleteSelected')
            ->assertSee('2 transaksi dihapus.')
            ->assertSet('selected', []);

        $this->assertSame(0, Transaction::count());
        $this->assertSame(1_000_000, $this->bank->refresh()->balance_minor);
    }

    public function test_bulk_actions_without_a_target_ask_for_one_and_change_nothing(): void
    {
        $a = $this->expense($this->dapur, 100_000, 'A');

        $this->page()->set('selected', [$a->id])->call('applyBulkCategory')->assertSee('Pilih ruang dan pos tujuan dulu.');
        $this->page()->set('selected', [$a->id])->call('applyBulkAccount')->assertSee('Pilih akun tujuan dulu.');

        $this->assertSame($this->dapur->id, $a->refresh()->category_id);
    }

    public function test_other_users_transactions_and_targets_are_never_touched_by_bulk_actions(): void
    {
        $other = User::factory()->create();
        $otherRoom = Room::create(['user_id' => $other->id, 'name' => 'Lain', 'type' => 'mencukupi', 'slot' => 1, 'sort_order' => 1]);
        $otherCategory = Category::create(['room_id' => $otherRoom->id, 'name' => 'Pos lain', 'sort_order' => 1]);
        $otherAccount = Account::create(['user_id' => $other->id, 'name' => 'Akun lain', 'type' => 'tunai', 'balance_minor' => 777, 'sort_order' => 1]);
        $foreign = Transaction::create([
            'user_id' => $other->id, 'room_id' => $otherRoom->id, 'category_id' => $otherCategory->id, 'account_id' => $otherAccount->id,
            'type' => 'pengeluaran', 'amount_minor' => 5_000, 'category' => 'Pos lain', 'note' => 'Punya orang lain', 'occurred_on' => '2026-10-20',
        ]);
        $mine = $this->expense($this->dapur, 100_000, 'Punyaku');

        // Tidak bisa memilih ID orang lain lewat aksi biasa.
        $this->page()->call('toggleRow', $foreign->id)->assertSet('selected', []);

        // ID orang lain yang diselundupkan ke state dibuang oleh semua aksi massal.
        $this->page()->set('selected', [$foreign->id, $mine->id])->call('deleteSelected');
        $this->assertDatabaseHas('transactions', ['id' => $foreign->id]);
        $this->assertDatabaseMissing('transactions', ['id' => $mine->id]);
        $this->assertSame(777, $otherAccount->refresh()->balance_minor);

        $mine = $this->expense($this->dapur, 100_000, 'Punyaku lagi');
        $this->page()->set('selected', [$foreign->id, $mine->id])->set('bulkCategoryId', (string) $this->jajan->id)->call('applyBulkCategory');
        $this->assertSame($otherCategory->id, $foreign->refresh()->category_id);
        $this->assertSame($this->jajan->id, $mine->refresh()->category_id);

        // Pos dan akun tujuan milik orang lain ditolak.
        $this->page()->set('selected', [$mine->id])->set('bulkCategoryId', (string) $otherCategory->id)->call('applyBulkCategory')
            ->assertSee('Pilih ruang dan pos tujuan dulu.');
        $this->page()->set('selected', [$mine->id])->set('bulkAccountId', (string) $otherAccount->id)->call('applyBulkAccount')
            ->assertSee('Pilih akun tujuan dulu.');
        $this->assertSame($this->jajan->id, $mine->refresh()->category_id);
        $this->assertSame($this->bank->id, $mine->account_id);
    }

    public function test_changing_a_filter_clears_the_selection(): void
    {
        $a = $this->expense($this->dapur, 100_000, 'A');

        $this->page()
            ->call('toggleRow', $a->id)
            ->set('jenis', 'pengeluaran')
            ->assertSet('selected', []);
    }

    public function test_export_link_for_the_selection_lists_only_selected_ids(): void
    {
        $a = $this->expense($this->dapur, 100_000, 'A');
        $this->expense($this->dapur, 50_000, 'B');

        $this->page()
            ->call('toggleRow', $a->id)
            ->assertSee('Ekspor pilihan (CSV)')
            ->assertSee(route('transaksi.ekspor', ['ids' => (string) $a->id]), false);
    }
}
