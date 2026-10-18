<?php

namespace Tests\Feature;

use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

class TransactionExportTest extends TestCase
{
    use RefreshDatabase;

    public function test_exporting_downloads_a_csv_with_a_header_row_and_one_row_per_transaction(): void
    {
        $user = User::factory()->create();
        $room = Room::create(['user_id' => $user->id, 'name' => 'Keluarga', 'type' => 'mencukupi', 'slot' => 1, 'sort_order' => 1]);
        $category = Category::create(['room_id' => $room->id, 'name' => 'Belanja bulanan', 'sort_order' => 1]);
        $account = Account::create(['user_id' => $user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'sort_order' => 1]);

        Transaction::create([
            'user_id' => $user->id, 'room_id' => $room->id, 'category_id' => $category->id, 'account_id' => $account->id,
            'type' => 'pengeluaran', 'amount_minor' => 150_000, 'category' => $category->name, 'note' => 'Belanja pasar',
            'occurred_on' => '2026-10-01',
        ]);
        Transaction::create([
            'user_id' => $user->id, 'account_id' => $account->id,
            'type' => 'pemasukan', 'amount_minor' => 8_500_000, 'category' => 'Gaji', 'occurred_on' => '2026-10-02',
        ]);

        $response = $this->actingAs($user)->get(route('transaksi.ekspor'));

        $response->assertOk();
        $response->assertHeader('content-type', 'text/csv; charset=UTF-8');

        $rows = array_map('str_getcsv', explode("\n", trim($response->streamedContent())));

        $this->assertSame(['Tanggal', 'Jenis', 'Ruang', 'Kategori', 'Akun', 'Nominal (Rp)', 'Catatan'], $rows[0]);
        $this->assertSame(['2026-10-01', 'pengeluaran', 'Keluarga', 'Belanja bulanan', 'Bank Jago', '150000', 'Belanja pasar'], $rows[1]);
        $this->assertSame(['2026-10-02', 'pemasukan', '', 'Gaji', 'Bank Jago', '8500000', ''], $rows[2]);
        $this->assertCount(3, $rows);
    }

    public function test_exporting_only_includes_the_current_users_transactions(): void
    {
        $user = User::factory()->create();
        $otherUser = User::factory()->create();
        $otherAccount = Account::create(['user_id' => $otherUser->id, 'name' => 'Tunai', 'type' => 'tunai', 'sort_order' => 1]);
        Transaction::create(['user_id' => $otherUser->id, 'account_id' => $otherAccount->id, 'type' => 'pemasukan', 'amount_minor' => 1000, 'occurred_on' => '2026-10-01']);

        $response = $this->actingAs($user)->get(route('transaksi.ekspor'));

        $rows = array_map('str_getcsv', explode("\n", trim($response->streamedContent())));
        $this->assertCount(1, $rows); // header saja, tanpa transaksi user lain
    }

    public function test_export_requires_authentication(): void
    {
        $this->get(route('transaksi.ekspor'))->assertRedirect(route('login'));
    }
}
