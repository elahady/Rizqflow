<?php

namespace Tests\Feature;

use App\Livewire\Zakat;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Support\ZakatRekap;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Livewire\Livewire;
use Tests\TestCase;

class ZakatRekapTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Room $memberi;

    private Category $zakatMal;

    private Category $sedekah;

    private Account $account;

    protected function setUp(): void
    {
        parent::setUp();

        Carbon::setTestNow('2026-10-24 09:00:00');

        $this->user = User::factory()->create(['name' => 'Ayu']);
        [$this->memberi, $this->zakatMal, $this->sedekah, $this->account] = $this->seedFor($this->user);
    }

    /** @return array{0: Room, 1: Category, 2: Category, 3: Account} */
    private function seedFor(User $user): array
    {
        $room = Room::create(['user_id' => $user->id, 'name' => 'Memberi', 'type' => 'menunaikan', 'slot' => 1, 'sort_order' => 1]);
        $zakat = Category::create(['room_id' => $room->id, 'name' => 'Zakat mal', 'is_system' => true, 'sort_order' => 1]);
        $sedekah = Category::create(['room_id' => $room->id, 'name' => 'Sedekah', 'sort_order' => 2]);
        $account = Account::create(['user_id' => $user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'balance_minor' => 10_000_000, 'sort_order' => 1]);

        return [$room, $zakat, $sedekah, $account];
    }

    private function zakatTx(string $date, int $amount, array $extra = [], ?User $user = null): Transaction
    {
        $user ??= $this->user;
        $category = $user->is($this->user) ? $this->zakatMal : Category::where('is_system', true)->whereHas('room', fn ($q) => $q->where('user_id', $user->id))->firstOrFail();
        $account = $user->accounts()->firstOrFail();

        return Transaction::create([
            'user_id' => $user->id, 'room_id' => $category->room_id, 'category_id' => $category->id, 'account_id' => $account->id,
            'type' => 'pengeluaran', 'amount_minor' => $amount, 'category' => $category->name, 'occurred_on' => $date,
        ] + $extra);
    }

    // --- Tabel rekap di halaman ---

    public function test_the_recap_lists_only_the_selected_years_zakat_payments_with_the_total(): void
    {
        $this->zakatTx('2025-11-21', 3_560_000, ['recipient_institution' => 'BAZNAS', 'receipt_number' => 'BS-001']);
        $this->zakatTx('2025-03-02', 1_000_000);
        $this->zakatTx('2026-04-10', 2_000_000);
        Transaction::create([
            'user_id' => $this->user->id, 'room_id' => $this->memberi->id, 'category_id' => $this->sedekah->id, 'account_id' => $this->account->id,
            'type' => 'pengeluaran', 'amount_minor' => 777_000, 'category' => 'Sedekah', 'occurred_on' => '2025-05-05',
        ]);

        $this->actingAs($this->user)
            ->get(route('zakat', ['tahun' => 2025]))
            ->assertOk()
            ->assertSee('Total zakat 2025')
            ->assertSee('Rp 4.560.000')
            ->assertSee('BS-001')
            ->assertDontSee('Rp 2.000.000')
            ->assertDontSee('Rp 777.000');
    }

    public function test_year_chips_cover_years_with_payments_plus_the_current_year_newest_first(): void
    {
        $this->zakatTx('2024-06-01', 100_000);
        $this->zakatTx('2022-06-01', 100_000);

        $this->assertSame([2026, 2024, 2022], ZakatRekap::years($this->user, 2026));
    }

    public function test_the_default_year_is_the_current_year_and_an_invalid_year_falls_back(): void
    {
        $this->zakatTx('2026-04-10', 2_000_000);

        Livewire::actingAs($this->user)->test(Zakat::class)->assertSee('Total zakat 2026');
        Livewire::actingAs($this->user)->test(Zakat::class)->set('tahun', 'abc')->assertSee('Total zakat 2026');
        Livewire::actingAs($this->user)->test(Zakat::class)->call('selectYear', 2025)->assertSet('tahun', '2025')->assertSee('Total zakat 2025');
    }

    public function test_a_year_without_payments_shows_an_empty_table(): void
    {
        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('selectYear', 2019)
            ->assertSee('Belum ada zakat yang dicatat pada 2019')
            ->assertSee('Rp 0');
    }

    // --- Lengkapi / Ubah ---

    public function test_lengkapi_saves_institution_and_receipt_on_the_users_zakat_transaction(): void
    {
        $tx = $this->zakatTx('2026-04-10', 2_000_000);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startEditRekap', $tx->id)
            ->assertSet('editingRekapId', $tx->id)
            ->set('rekapInstitution', '  LAZ Contoh ')
            ->set('rekapReceipt', 'NB-77')
            ->call('saveRekap')
            ->assertSet('rekapError', null)
            ->assertSet('editingRekapId', null);

        $tx->refresh();
        $this->assertSame('LAZ Contoh', $tx->recipient_institution);
        $this->assertSame('NB-77', $tx->receipt_number);
        $this->assertSame(2_000_000, $tx->amount_minor);
    }

    public function test_clearing_the_fields_stores_null(): void
    {
        $tx = $this->zakatTx('2026-04-10', 2_000_000, ['recipient_institution' => 'BAZNAS', 'receipt_number' => 'X']);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startEditRekap', $tx->id)
            ->assertSet('rekapInstitution', 'BAZNAS')
            ->set('rekapInstitution', '')
            ->set('rekapReceipt', '')
            ->call('saveRekap');

        $tx->refresh();
        $this->assertNull($tx->recipient_institution);
        $this->assertNull($tx->receipt_number);
    }

    public function test_lengkapi_rejects_values_that_are_too_long(): void
    {
        $tx = $this->zakatTx('2026-04-10', 2_000_000);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startEditRekap', $tx->id)
            ->set('rekapInstitution', str_repeat('a', 101))
            ->call('saveRekap')
            ->assertSet('rekapError', 'Lembaga penerima paling banyak 100 karakter.')
            ->set('rekapInstitution', 'BAZNAS')
            ->set('rekapReceipt', str_repeat('1', 61))
            ->call('saveRekap')
            ->assertSet('rekapError', 'Nomor bukti setor paling banyak 60 karakter.');

        $this->assertNull($tx->refresh()->recipient_institution);
    }

    public function test_lengkapi_refuses_another_users_transaction(): void
    {
        $other = User::factory()->create();
        $this->seedFor($other);
        $theirs = $this->zakatTx('2026-04-10', 2_000_000, ['recipient_institution' => 'Asli'], $other);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startEditRekap', $theirs->id)
            ->assertSet('editingRekapId', null)
            ->assertSet('rekapError', 'Transaksi zakat tidak ditemukan.')
            ->set('editingRekapId', $theirs->id)
            ->set('rekapInstitution', 'Dibajak')
            ->call('saveRekap')
            ->assertSet('rekapError', 'Transaksi zakat tidak ditemukan.');

        $this->assertSame('Asli', $theirs->refresh()->recipient_institution);
    }

    public function test_lengkapi_refuses_a_transaction_that_is_not_zakat(): void
    {
        $sedekah = Transaction::create([
            'user_id' => $this->user->id, 'room_id' => $this->memberi->id, 'category_id' => $this->sedekah->id, 'account_id' => $this->account->id,
            'type' => 'pengeluaran', 'amount_minor' => 50_000, 'category' => 'Sedekah', 'occurred_on' => '2026-04-10',
        ]);
        $income = Transaction::create([
            'user_id' => $this->user->id, 'account_id' => $this->account->id, 'type' => 'pemasukan', 'amount_minor' => 5_000_000, 'category' => 'Gaji', 'occurred_on' => '2026-04-01',
        ]);

        foreach ([$sedekah, $income] as $tx) {
            Livewire::actingAs($this->user)
                ->test(Zakat::class)
                ->call('startEditRekap', $tx->id)
                ->assertSet('editingRekapId', null)
                ->set('editingRekapId', $tx->id)
                ->set('rekapInstitution', 'Dibajak')
                ->call('saveRekap')
                ->assertSet('rekapError', 'Transaksi zakat tidak ditemukan.');

            $this->assertNull($tx->refresh()->recipient_institution);
        }
    }

    // --- PDF ---

    public function test_the_pdf_is_a_real_pdf_with_the_right_filename(): void
    {
        $this->zakatTx('2025-11-21', 3_560_000, ['recipient_institution' => 'BAZNAS']);

        $response = $this->actingAs($this->user)->get(route('zakat.rekap.pdf', ['year' => 2025]));

        $response->assertOk();
        $response->assertHeader('content-type', 'application/pdf');
        $this->assertStringContainsString('rekap-zakat-2025.pdf', $response->headers->get('content-disposition'));
        $this->assertStringStartsWith('%PDF-', $response->getContent());
    }

    public function test_the_pdf_for_a_year_without_data_is_still_a_pdf(): void
    {
        $response = $this->actingAs($this->user)->get(route('zakat.rekap.pdf', ['year' => 2001]));

        $response->assertOk();
        $this->assertStringStartsWith('%PDF-', $response->getContent());
    }

    public function test_the_pdf_view_shows_title_name_rows_total_and_disclaimer(): void
    {
        $this->zakatTx('2025-11-21', 3_560_000, ['recipient_institution' => 'BAZNAS']);
        $transactions = ZakatRekap::transactions($this->user, 2025);

        $html = view('pdf.zakat-rekap', [
            'year' => 2025, 'userName' => 'Ayu', 'transactions' => $transactions, 'totalMinor' => ZakatRekap::totalMinor($transactions),
        ])->render();

        $this->assertStringContainsString('Rekap zakat 2025', $html);
        $this->assertStringContainsString('Ayu', $html);
        $this->assertStringContainsString('BAZNAS', $html);
        $this->assertStringContainsString('Belum diisi', $html);
        $this->assertStringContainsString('Rp 3.560.000', $html);
        $this->assertStringContainsString('UU 23/2011 Pasal 22', $html);
    }

    // --- CSV ---

    public function test_the_csv_has_header_rows_and_only_that_years_zakat(): void
    {
        $this->zakatTx('2025-11-21', 3_560_000, ['recipient_institution' => 'BAZNAS', 'receipt_number' => 'BS-001']);
        $this->zakatTx('2025-03-02', 1_000_000);
        $this->zakatTx('2026-04-10', 2_000_000);

        $response = $this->actingAs($this->user)->get(route('zakat.rekap.csv', ['year' => 2025]));

        $response->assertOk();
        $this->assertStringContainsString('text/csv', $response->headers->get('content-type'));
        $this->assertStringContainsString('rekap-zakat-2025.csv', $response->headers->get('content-disposition'));

        $lines = preg_split('/\r?\n/', trim($response->getContent()));
        $this->assertSame('Tanggal,"Jenis zakat","Lembaga penerima","No. bukti setor",Nominal', $lines[0]);
        $this->assertSame('2025-03-02,"Zakat mal",,,1000000', $lines[1]);
        $this->assertSame('2025-11-21,"Zakat mal",BAZNAS,BS-001,3560000', $lines[2]);
        $this->assertCount(3, $lines);
    }

    public function test_the_csv_neutralises_formula_injection(): void
    {
        foreach (['=HYPERLINK("http://x")', '+1', '-2', '@SUM(A1)', "\tTab", "\rCR"] as $i => $payload) {
            $this->zakatTx('2025-01-'.sprintf('%02d', $i + 1), 1000, ['recipient_institution' => $payload, 'receipt_number' => $payload]);
        }

        $csv = $this->actingAs($this->user)->get(route('zakat.rekap.csv', ['year' => 2025]))->getContent();

        foreach (['"\'=HYPERLINK', "'+1", "'-2", "'@SUM(A1)", "'\tTab", "'\rCR"] as $neutralised) {
            $this->assertStringContainsString($neutralised, $csv);
        }
        $this->assertStringNotContainsString(',=HYPERLINK', $csv);
        $this->assertStringNotContainsString(',+1,', $csv);
        $this->assertStringNotContainsString(',@SUM', $csv);
    }

    public function test_csv_cell_only_prefixes_dangerous_leading_characters(): void
    {
        $this->assertSame("'=x", ZakatRekap::csvCell('=x'));
        $this->assertSame("'+1", ZakatRekap::csvCell('+1'));
        $this->assertSame("'-2", ZakatRekap::csvCell('-2'));
        $this->assertSame("'@SUM(A1)", ZakatRekap::csvCell('@SUM(A1)'));
        $this->assertSame("'\tTab", ZakatRekap::csvCell("\tTab"));
        $this->assertSame("'\rCR", ZakatRekap::csvCell("\rCR"));
        $this->assertSame('BAZNAS', ZakatRekap::csvCell('BAZNAS'));
        $this->assertSame('A=B', ZakatRekap::csvCell('A=B'));
        $this->assertSame('', ZakatRekap::csvCell(''));
    }

    public function test_the_csv_for_a_year_without_data_is_just_the_header(): void
    {
        $response = $this->actingAs($this->user)->get(route('zakat.rekap.csv', ['year' => 2001]));

        $response->assertOk();
        $this->assertSame('Tanggal,"Jenis zakat","Lembaga penerima","No. bukti setor",Nominal', trim($response->getContent()));
    }

    // --- Isolasi data dan akses ---

    public function test_downloads_never_include_another_users_zakat(): void
    {
        $other = User::factory()->create();
        $this->seedFor($other);
        $this->zakatTx('2025-11-21', 9_990_000, ['recipient_institution' => 'LembagaOrangLain'], $other);
        $this->zakatTx('2025-11-22', 1_000_000, ['recipient_institution' => 'Punyaku']);

        $csv = $this->actingAs($this->user)->get(route('zakat.rekap.csv', ['year' => 2025]))->getContent();

        $this->assertStringContainsString('Punyaku', $csv);
        $this->assertStringNotContainsString('LembagaOrangLain', $csv);
        $this->assertStringNotContainsString('9990000', $csv);

        $this->actingAs($this->user)->get(route('zakat', ['tahun' => 2025]))->assertDontSee('LembagaOrangLain');
    }

    public function test_guests_cannot_download_the_recap(): void
    {
        $this->get(route('zakat.rekap.pdf', ['year' => 2025]))->assertRedirect();
        $this->get(route('zakat.rekap.csv', ['year' => 2025]))->assertRedirect();
    }

    public function test_a_non_numeric_year_is_not_routed(): void
    {
        $this->actingAs($this->user)->get('/zakat/rekap/abcd/pdf')->assertNotFound();
        $this->actingAs($this->user)->get('/zakat/rekap/20x5/csv')->assertNotFound();
    }

    public function test_the_page_links_to_both_downloads_and_offers_the_copy_button(): void
    {
        $this->actingAs($this->user)
            ->get(route('zakat', ['tahun' => 2025]))
            ->assertSee(route('zakat.rekap.pdf', ['year' => 2025]), false)
            ->assertSee(route('zakat.rekap.csv', ['year' => 2025]), false)
            ->assertSee('Salin sebagai CSV')
            ->assertSee('Bukti setor tidak disimpan di server');
    }
}
