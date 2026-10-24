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
    private function csvRows($response): array
    {
        return array_map('str_getcsv', explode("\n", trim($response->streamedContent())));
    }

    public function test_exporting_a_selection_only_includes_the_requested_ids_owned_by_the_user(): void
    {
        $user = User::factory()->create();
        $other = User::factory()->create();
        $account = Account::create(['user_id' => $user->id, 'name' => 'BCA', 'type' => 'bank', 'sort_order' => 1]);
        $otherAccount = Account::create(['user_id' => $other->id, 'name' => 'Punya lain', 'type' => 'tunai', 'sort_order' => 1]);
        $make = fn (User $u, Account $a, string $note) => Transaction::create([
            'user_id' => $u->id, 'account_id' => $a->id, 'type' => 'pemasukan', 'amount_minor' => 1000, 'note' => $note, 'occurred_on' => '2026-10-01',
        ]);
        $a = $make($user, $account, 'dipilih');
        $b = $make($user, $account, 'tidak dipilih');
        $foreign = $make($other, $otherAccount, 'milik orang lain');

        $rows = $this->csvRows($this->actingAs($user)->get(route('transaksi.ekspor', ['ids' => "{$a->id},{$foreign->id},abc,0,-5"])));
        $this->assertCount(2, $rows); // header + satu baris milik sendiri
        $this->assertSame('dipilih', $rows[1][6]);

        // ids kosong atau tak sah = tidak ada baris (bukan ekspor semua).
        $this->assertCount(1, $this->csvRows($this->actingAs($user)->get(route('transaksi.ekspor', ['ids' => '']))));
        $this->assertCount(1, $this->csvRows($this->actingAs($user)->get(route('transaksi.ekspor', ['ids' => 'abc']))));
        $this->assertCount(1, $this->csvRows($this->actingAs($user)->get(route('transaksi.ekspor', ['ids' => [$a->id]]))));

        $this->assertCount(3, $this->csvRows($this->actingAs($user)->get(route('transaksi.ekspor', ['ids' => "{$a->id},{$b->id}"]))));
    }

    public function test_text_cells_that_look_like_formulas_are_neutralised(): void
    {
        $user = User::factory()->create();
        $room = Room::create(['user_id' => $user->id, 'name' => '=Ruang', 'type' => 'mencukupi', 'slot' => 1, 'sort_order' => 1]);
        $category = Category::create(['room_id' => $room->id, 'name' => '+Pos', 'sort_order' => 1]);
        $account = Account::create(['user_id' => $user->id, 'name' => '@Akun', 'type' => 'bank', 'sort_order' => 1]);

        foreach (['=HYPERLINK("http://x","klik")', '+1+1', '-2+3', '@SUM(A1)', "\tTab", "\rCR"] as $i => $note) {
            Transaction::create([
                'user_id' => $user->id, 'room_id' => $room->id, 'category_id' => $category->id, 'account_id' => $account->id,
                'type' => 'pengeluaran', 'amount_minor' => 1000 + $i, 'category' => $category->name, 'note' => $note, 'occurred_on' => '2026-10-0'.($i + 1),
            ]);
        }
        Transaction::create(['user_id' => $user->id, 'account_id' => $account->id, 'type' => 'pemasukan', 'amount_minor' => 5, 'note' => 'aman - biasa', 'occurred_on' => '2026-10-09']);

        $content = $this->actingAs($user)->get(route('transaksi.ekspor'))->streamedContent();
        $rows = array_map('str_getcsv', preg_split('/\n(?=\d{4}-\d{2}-\d{2},|Tanggal)/', trim($content)));

        $this->assertSame("'=Ruang", $rows[1][2]);
        $this->assertSame("'+Pos", $rows[1][3]);
        $this->assertSame("'@Akun", $rows[1][4]);
        $this->assertSame('1000', $rows[1][5]); // nominal tetap angka polos
        $this->assertSame("'=HYPERLINK(\"http://x\",\"klik\")", $rows[1][6]);
        $this->assertSame("'+1+1", $rows[2][6]);
        $this->assertSame("'-2+3", $rows[3][6]);
        $this->assertSame("'@SUM(A1)", $rows[4][6]);
        $this->assertSame("'\tTab", $rows[5][6]);
        $this->assertSame("'\rCR", $rows[6][6]);
        $this->assertSame('aman - biasa', $rows[7][6]); // awalan hanya untuk karakter pertama
    }
}