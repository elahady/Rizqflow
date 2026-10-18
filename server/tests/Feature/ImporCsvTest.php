<?php

namespace Tests\Feature;

use App\Livewire\ImporCsv;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Livewire\Livewire;
use Tests\TestCase;

class ImporCsvTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Room $room;

    private Category $category;

    private Account $account;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create(['plans' => ['pro']]);
        $this->room = Room::create(['user_id' => $this->user->id, 'name' => 'Keluarga', 'type' => 'mencukupi', 'slot' => 1, 'sort_order' => 1]);
        $this->category = Category::create(['room_id' => $this->room->id, 'name' => 'Belanja bulanan', 'sort_order' => 1]);
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'balance_minor' => 1_000_000, 'sort_order' => 1]);
    }

    private function csv(string $content): UploadedFile
    {
        return UploadedFile::fake()->createWithContent('mutasi.csv', $content);
    }

    public function test_free_user_sees_an_upsell(): void
    {
        $this->user->update(['plans' => []]);

        $this->actingAs($this->user)
            ->get(route('laporan.impor-csv'))
            ->assertOk()
            ->assertSee('Impor CSV massal adalah fitur Pro.');
    }

    public function test_uploading_a_csv_shows_a_preview_without_importing_yet(): void
    {
        Livewire::actingAs($this->user)
            ->test(ImporCsv::class)
            ->set('file', $this->csv("2026-10-01,-150000,Belanja pasar\n2026-10-02,2000000,Gaji"))
            ->assertSet('skipped', 0)
            ->assertCount('rows', 2);

        $this->assertSame(0, Transaction::count());
    }

    public function test_confirming_creates_expense_and_income_rows_and_moves_the_balance(): void
    {
        Livewire::actingAs($this->user)
            ->test(ImporCsv::class)
            ->set('file', $this->csv("2026-10-01,-150000,Belanja pasar\n2026-10-02,2000000,Gaji"))
            ->set('accountId', $this->account->id)
            ->set('roomId', $this->room->id)
            ->set('categoryId', $this->category->id)
            ->call('confirmImport')
            ->assertSet('error', null);

        $this->assertSame(2, Transaction::count());
        $expense = Transaction::where('type', 'pengeluaran')->first();
        $this->assertSame(150_000, $expense->amount_minor);
        $this->assertSame($this->room->id, $expense->room_id);
        $this->assertSame($this->category->id, $expense->category_id);
        $income = Transaction::where('type', 'pemasukan')->first();
        $this->assertNull($income->room_id);
        $this->assertSame(1_000_000 - 150_000 + 2_000_000, $this->account->refresh()->balance_minor);
    }

    public function test_income_only_file_does_not_require_a_room_or_category(): void
    {
        Livewire::actingAs($this->user)
            ->test(ImporCsv::class)
            ->set('file', $this->csv('2026-10-02,2000000,Gaji'))
            ->set('accountId', $this->account->id)
            ->call('confirmImport')
            ->assertSet('error', null);

        $this->assertSame(1, Transaction::count());
    }

    public function test_confirming_an_expense_row_without_a_room_shows_an_error_and_imports_nothing(): void
    {
        Livewire::actingAs($this->user)
            ->test(ImporCsv::class)
            ->set('file', $this->csv('2026-10-01,-150000,Belanja pasar'))
            ->set('accountId', $this->account->id)
            ->call('confirmImport')
            ->assertSet('error', 'Pilih ruang dan kategori untuk baris pengeluaran.');

        $this->assertSame(0, Transaction::count());
    }

    public function test_confirming_without_an_account_shows_an_error(): void
    {
        Livewire::actingAs($this->user)
            ->test(ImporCsv::class)
            ->set('file', $this->csv('2026-10-01,-150000,Belanja pasar'))
            ->call('confirmImport')
            ->assertSet('error', 'Pilih akun tujuan.');

        $this->assertSame(0, Transaction::count());
    }

    public function test_a_non_csv_file_is_rejected(): void
    {
        Livewire::actingAs($this->user)
            ->test(ImporCsv::class)
            ->set('file', UploadedFile::fake()->create('data.pdf', 10))
            ->assertSet('error', 'Berkas harus CSV (.csv atau .txt).')
            ->assertSet('rows', []);
    }

    public function test_switching_rooms_resets_the_category_selection(): void
    {
        Livewire::actingAs($this->user)
            ->test(ImporCsv::class)
            ->set('categoryId', $this->category->id)
            ->set('roomId', $this->room->id)
            ->assertSet('categoryId', null);
    }

    public function test_successful_import_resets_the_form_for_another_batch(): void
    {
        $component = Livewire::actingAs($this->user)
            ->test(ImporCsv::class)
            ->set('file', $this->csv('2026-10-02,2000000,Gaji'))
            ->set('accountId', $this->account->id)
            ->call('confirmImport');

        $component->assertSet('rows', [])->assertSet('accountId', null);
        $this->assertStringContainsString('1 transaksi berhasil diimpor', $component->get('status'));
    }
}
