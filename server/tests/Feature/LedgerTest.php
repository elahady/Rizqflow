<?php

namespace Tests\Feature;

use App\Models\Account;
use App\Models\AllocationEntry;
use App\Models\Bill;
use App\Models\Category;
use App\Models\RecurringRule;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Support\Ledger;
use App\Support\LedgerException;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Tests\TestCase;

/**
 * Ledger = porting LedgerService (domain/.../ledger/LedgerService.kt) untuk web: potret alokasi per
 * pemasukan, saldo akun, dan penolakan data milik pengguna lain.
 */
class LedgerTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Account $account;

    private Room $memberi;

    private Room $diri;

    private Room $keluarga;

    private Category $sedekah;

    private Category $belanja;

    private Category $investasi;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create();
        $this->account = Account::factory()->create(['user_id' => $this->user->id, 'balance_minor' => 1_000_000]);

        $this->memberi = Room::factory()->percent(10)->type(Room::TYPE_MENUNAIKAN)->create(['user_id' => $this->user->id, 'name' => 'Memberi', 'slot' => 1, 'sort_order' => 1]);
        $this->diri = Room::factory()->percent(30)->type(Room::TYPE_MENUMBUHKAN)->create(['user_id' => $this->user->id, 'name' => 'Diri', 'slot' => 2, 'sort_order' => 2]);
        $this->keluarga = Room::factory()->percent(60)->create(['user_id' => $this->user->id, 'name' => 'Keluarga', 'slot' => 3, 'sort_order' => 3]);

        $this->sedekah = Category::factory()->create(['room_id' => $this->memberi->id, 'name' => 'Sedekah']);
        $this->investasi = Category::factory()->create(['room_id' => $this->diri->id, 'name' => 'Investasi']);
        $this->belanja = Category::factory()->create(['room_id' => $this->keluarga->id, 'name' => 'Belanja']);
    }

    private function income(int $amount, ?Carbon $date = null): Transaction
    {
        return Ledger::recordIncome($this->user, $amount, $this->account, $date ?? Carbon::today(), 'Gaji', null);
    }

    private function balance(?Account $account = null): int
    {
        return (int) ($account ?? $this->account)->fresh()->balance_minor;
    }

    private function assertRejected(string $reason, callable $action): void
    {
        try {
            $action();
        } catch (LedgerException $e) {
            $this->assertSame($reason, $e->reason);

            return;
        }

        $this->fail("LedgerException {$reason} tidak dilempar.");
    }

    // --- previewIncome ---

    public function test_preview_dalam_mode_persentase_membagi_tanpa_menyimpan(): void
    {
        $preview = Ledger::previewIncome($this->user, 1_234_567);

        $this->assertSame(Ledger::MODE_PERCENTAGE, $preview['mode']);
        $this->assertSame('10 / 30 / 60', $preview['rule_label']);
        $this->assertSame(1_234_567, $preview['amount_minor']);
        $this->assertSame(['Memberi', 'Diri', 'Keluarga'], array_map(fn ($s) => $s['room']->name, $preview['rooms']));
        $this->assertSame([1000, 3000, 6000], array_column($preview['rooms'], 'share_bp'));
        $this->assertSame([123_457, 370_370, 740_740], array_column($preview['rooms'], 'amount_minor'));
        $this->assertSame(1_234_567, $preview['allocated_minor']);
        $this->assertSame(0, $preview['unallocated_minor']);
        $this->assertSame(1, $preview['rounding_minor']);

        $this->assertSame(0, Transaction::count());
        $this->assertSame(0, AllocationEntry::count());
        $this->assertSame(1_000_000, $this->balance());
    }

    public function test_preview_tanpa_aturan_menyisakan_seluruh_nominal_dan_label_kosong(): void
    {
        Room::query()->update(['allocation_percent' => null]);

        $preview = Ledger::previewIncome($this->user, 500_000);

        $this->assertNull($preview['rule_label']);
        $this->assertSame([], $preview['rooms']);
        $this->assertSame(500_000, $preview['unallocated_minor']);
        $this->assertSame(0, $preview['allocated_minor']);
    }

    public function test_preview_nominal_nol_boleh_dan_nominal_negatif_ditolak(): void
    {
        $this->assertSame([0, 0, 0], array_column(Ledger::previewIncome($this->user, 0)['rooms'], 'amount_minor'));

        $this->assertRejected(LedgerException::AMOUNT_NOT_POSITIVE, fn () => Ledger::previewIncome($this->user, -1));
        $this->assertRejected(LedgerException::AMOUNT_TOO_LARGE, fn () => Ledger::previewIncome($this->user, Ledger::MAX_AMOUNT_MINOR + 1));
    }

    public function test_aturan_persentase_di_atas_100_ditolak_dengan_pesan_ui(): void
    {
        $this->keluarga->update(['allocation_percent' => 80]);

        $this->assertRejected(LedgerException::RULES_INVALID, fn () => Ledger::previewIncome($this->user, 1_000_000));
    }

    public function test_preview_mode_lanjutan_memakai_batas_atas_dan_label_prioritas(): void
    {
        $this->user->update(['plans' => ['pro'], 'allocation_mode' => 'lanjutan']);
        $this->memberi->update(['allocation_cap_minor' => 500_000]);
        $this->diri->update(['allocation_cap_minor' => 2_000_000]);

        $preview = Ledger::previewIncome($this->user, 5_000_000);

        $this->assertSame(Ledger::MODE_ADVANCED, $preview['mode']);
        $this->assertSame('Prioritas', $preview['rule_label']);
        $this->assertSame([500_000, 2_000_000, 2_500_000], array_column($preview['rooms'], 'amount_minor'));
        $this->assertSame([500_000, 2_000_000, null], array_column($preview['rooms'], 'cap_minor'));
        $this->assertSame([1000, 4000, 5000], array_column($preview['rooms'], 'share_bp'));
        $this->assertSame(0, $preview['unallocated_minor']);
        $this->assertSame(0, $preview['rounding_minor']);
    }

    public function test_mode_lanjutan_tanpa_paket_pro_kembali_ke_persentase(): void
    {
        $this->user->update(['allocation_mode' => 'lanjutan']);

        $this->assertSame(Ledger::MODE_PERCENTAGE, Ledger::previewIncome($this->user, 1_000_000)['mode']);
    }

    // --- recordIncome ---

    public function test_pemasukan_menyimpan_potret_per_ruang_dan_menambah_saldo(): void
    {
        $tx = Ledger::recordIncome($this->user, 18_000_000, $this->account, Carbon::parse('2026-10-22'), 'Gaji', 'Gaji Oktober');

        $this->assertSame(Transaction::TYPE_INCOME, $tx->type);
        $this->assertSame(18_000_000, $tx->amount_minor);
        $this->assertSame('Gaji', $tx->category);
        $this->assertSame('Gaji Oktober', $tx->note);
        $this->assertNull($tx->room_id);
        $this->assertSame('2026-10-22', $tx->fresh()->occurred_on->toDateString());

        $entries = $tx->sortedAllocationEntries();
        $this->assertSame([$this->memberi->id, $this->diri->id, $this->keluarga->id], $entries->pluck('room_id')->all());
        $this->assertSame([1000, 3000, 6000], $entries->pluck('share_bp')->all());
        $this->assertSame([1_800_000, 5_400_000, 10_800_000], $entries->pluck('amount_minor')->all());
        $this->assertSame('10 / 30 / 60', $tx->fresh()->allocationLabel());
        $this->assertSame(0, $tx->fresh()->unallocatedMinor());
        $this->assertSame(19_000_000, $this->balance());
    }

    public function test_ruang_dengan_bagian_nol_rupiah_tetap_punya_baris_potret(): void
    {
        $tx = $this->income(1);

        $entries = $tx->sortedAllocationEntries();
        $this->assertCount(3, $entries);
        $this->assertSame([0, 0, 1], $entries->pluck('amount_minor')->all());
        $this->assertSame([1000, 3000, 6000], $entries->pluck('share_bp')->all());
    }

    public function test_ruang_nol_persen_dan_terarsip_tidak_masuk_potret(): void
    {
        $this->diri->update(['allocation_percent' => 0]);
        $this->memberi->update(['archived' => true]);

        $tx = $this->income(1_000_000);

        $this->assertSame([$this->keluarga->id], $tx->allocationEntries->pluck('room_id')->all());
        $this->assertSame(600_000, $tx->allocatedMinor());
        $this->assertSame(400_000, $tx->unallocatedMinor());
    }

    public function test_potret_tidak_berubah_saat_aturan_diubah_sesudahnya(): void
    {
        $tx = $this->income(10_000_000);
        $this->memberi->update(['allocation_percent' => 50]);
        $this->keluarga->update(['allocation_percent' => 20]);

        $this->assertSame([1_000_000, 3_000_000, 6_000_000], $tx->fresh()->sortedAllocationEntries()->pluck('amount_minor')->all());
    }

    public function test_pemasukan_mode_lanjutan_menyimpan_persentase_turunan(): void
    {
        $this->user->update(['plans' => ['pro'], 'allocation_mode' => 'lanjutan']);
        $this->memberi->update(['allocation_cap_minor' => 500_000]);
        $this->diri->update(['allocation_cap_minor' => 2_000_000]);

        $tx = $this->income(5_000_000);

        $entries = $tx->sortedAllocationEntries();
        $this->assertSame([500_000, 2_000_000, 2_500_000], $entries->pluck('amount_minor')->all());
        $this->assertSame([1000, 4000, 5000], $entries->pluck('share_bp')->all());
    }

    public function test_pemasukan_ditolak_bila_nominal_akun_atau_catatan_tidak_sah(): void
    {
        $this->assertRejected(LedgerException::AMOUNT_NOT_POSITIVE, fn () => $this->income(0));
        $this->assertRejected(LedgerException::AMOUNT_TOO_LARGE, fn () => $this->income(Ledger::MAX_AMOUNT_MINOR + 1));
        $this->assertRejected(LedgerException::NOTE_TOO_LONG, fn () => Ledger::recordIncome($this->user, 1000, $this->account, Carbon::today(), null, str_repeat('a', 201)));
        $this->assertRejected(LedgerException::SOURCE_TOO_LONG, fn () => Ledger::recordIncome($this->user, 1000, $this->account, Carbon::today(), str_repeat('a', 41), null));

        $this->account->update(['archived' => true]);
        $this->assertRejected(LedgerException::ACCOUNT_ARCHIVED, fn () => $this->income(1000));

        $this->assertSame(0, Transaction::count());
    }

    public function test_pemasukan_menolak_akun_dan_aturan_berulang_milik_pengguna_lain(): void
    {
        $other = User::factory()->create();
        $foreignAccount = Account::factory()->create(['user_id' => $other->id]);
        $foreignRule = RecurringRule::create([
            'user_id' => $other->id, 'type' => 'pemasukan', 'amount_minor' => 1000, 'account_id' => $foreignAccount->id,
            'frequency' => 'bulanan', 'start_date' => '2026-10-01', 'next_due' => '2026-11-01',
        ]);

        $this->assertRejected(LedgerException::ACCOUNT_NOT_FOUND, fn () => Ledger::recordIncome($this->user, 1000, $foreignAccount, Carbon::today(), null, null));
        $this->assertRejected(LedgerException::RECURRING_NOT_FOUND, fn () => Ledger::recordIncome($this->user, 1000, $this->account, Carbon::today(), null, null, $foreignRule->id));

        $this->assertSame(0, Transaction::count());
        $this->assertSame(0, (int) $foreignAccount->fresh()->balance_minor);
    }

    // --- recordExpense ---

    public function test_pengeluaran_menurunkan_ruang_dari_pos_dan_mengurangi_saldo(): void
    {
        $tx = Ledger::recordExpense($this->user, 187_500, $this->belanja, $this->account, Carbon::parse('2026-10-24'), 'Belanja pasar');

        $this->assertSame(Transaction::TYPE_EXPENSE, $tx->type);
        $this->assertSame($this->keluarga->id, $tx->room_id);
        $this->assertSame($this->belanja->id, $tx->category_id);
        $this->assertSame('Belanja', $tx->category);
        $this->assertSame(812_500, $this->balance());
        $this->assertSame(0, AllocationEntry::count());
    }

    public function test_pengeluaran_boleh_melebihi_saldo_dan_saldo_menjadi_negatif(): void
    {
        Ledger::recordExpense($this->user, 1_500_000, $this->belanja, $this->account, Carbon::today(), null);

        $this->assertSame(-500_000, $this->balance());
    }

    public function test_pengeluaran_menyimpan_kolom_tambahan_zakat(): void
    {
        $zakat = Category::factory()->create(['room_id' => $this->memberi->id, 'name' => Category::ZAKAT, 'is_system' => true]);

        $tx = Ledger::recordExpense($this->user, 3_560_000, $zakat, $this->account, Carbon::today(), null, [
            'recipient_institution' => ' BAZNAS ',
            'receipt_number' => 'BZ-001',
        ]);

        $tx = $tx->fresh();
        $this->assertSame('BAZNAS', $tx->recipient_institution);
        $this->assertSame('BZ-001', $tx->receipt_number);
    }

    public function test_pengeluaran_menautkan_tagihan_dan_aturan_berulang_milik_sendiri(): void
    {
        $bill = Bill::create([
            'user_id' => $this->user->id, 'name' => 'Cicilan', 'amount_minor' => 1000, 'account_id' => $this->account->id,
            'room_id' => $this->keluarga->id, 'category_id' => $this->belanja->id, 'frequency' => 'bulanan',
            'start_date' => '2026-10-01', 'next_due' => '2026-11-01',
        ]);

        $tx = Ledger::recordExpense($this->user, 1000, $this->belanja, $this->account, Carbon::today(), null, ['bill_id' => $bill->id]);

        $this->assertSame($bill->id, $tx->fresh()->bill_id);
    }

    public function test_pengeluaran_menolak_kolom_tambahan_tak_dikenal_dan_tagihan_milik_orang_lain(): void
    {
        $other = User::factory()->create();
        $otherAccount = Account::factory()->create(['user_id' => $other->id]);
        $otherRoom = Room::factory()->create(['user_id' => $other->id]);
        $otherCategory = Category::factory()->create(['room_id' => $otherRoom->id]);
        $foreignBill = Bill::create([
            'user_id' => $other->id, 'name' => 'Cicilan', 'amount_minor' => 1000, 'account_id' => $otherAccount->id,
            'room_id' => $otherRoom->id, 'category_id' => $otherCategory->id, 'frequency' => 'bulanan',
            'start_date' => '2026-10-01', 'next_due' => '2026-11-01',
        ]);

        $this->assertRejected(LedgerException::BILL_NOT_FOUND, fn () => Ledger::recordExpense($this->user, 1000, $this->belanja, $this->account, Carbon::today(), null, ['bill_id' => $foreignBill->id]));

        $this->expectException(\InvalidArgumentException::class);
        Ledger::recordExpense($this->user, 1000, $this->belanja, $this->account, Carbon::today(), null, ['user_id' => 99]);
    }

    public function test_pengeluaran_menolak_pos_dan_akun_milik_pengguna_lain(): void
    {
        $other = User::factory()->create();
        $otherAccount = Account::factory()->create(['user_id' => $other->id]);
        $otherRoom = Room::factory()->create(['user_id' => $other->id]);
        $otherCategory = Category::factory()->create(['room_id' => $otherRoom->id]);

        $this->assertRejected(LedgerException::CATEGORY_NOT_FOUND, fn () => Ledger::recordExpense($this->user, 1000, $otherCategory, $this->account, Carbon::today(), null));
        $this->assertRejected(LedgerException::ACCOUNT_NOT_FOUND, fn () => Ledger::recordExpense($this->user, 1000, $this->belanja, $otherAccount, Carbon::today(), null));

        $this->assertSame(0, Transaction::count());
        $this->assertSame(1_000_000, $this->balance());
        $this->assertSame(0, (int) $otherAccount->fresh()->balance_minor);
    }

    public function test_pengeluaran_menolak_pos_atau_ruang_terarsip_dan_nominal_nol(): void
    {
        $this->assertRejected(LedgerException::AMOUNT_NOT_POSITIVE, fn () => Ledger::recordExpense($this->user, 0, $this->belanja, $this->account, Carbon::today(), null));

        $this->belanja->update(['archived' => true]);
        $this->assertRejected(LedgerException::CATEGORY_ARCHIVED, fn () => Ledger::recordExpense($this->user, 1000, $this->belanja->fresh(), $this->account, Carbon::today(), null));

        $this->belanja->update(['archived' => false]);
        $this->keluarga->update(['archived' => true]);
        $this->assertRejected(LedgerException::ROOM_ARCHIVED, fn () => Ledger::recordExpense($this->user, 1000, $this->belanja->fresh(), $this->account, Carbon::today(), null));
    }

    // --- changeIncomeAmount ---

    public function test_ubah_nominal_pemasukan_memakai_persentase_potret_bukan_aturan_sekarang(): void
    {
        $tx = $this->income(10_000_000);
        $this->memberi->update(['allocation_percent' => 50]);
        $this->keluarga->update(['allocation_percent' => 20]);

        $updated = Ledger::changeIncomeAmount($tx->fresh(), 20_000_001);

        $this->assertSame(20_000_001, $updated->amount_minor);
        $entries = $tx->fresh()->sortedAllocationEntries();
        $this->assertSame([1000, 3000, 6000], $entries->pluck('share_bp')->all());
        $this->assertSame([2_000_000, 6_000_000, 12_000_001], $entries->pluck('amount_minor')->all());
        $this->assertSame(21_000_001, $this->balance());
    }

    public function test_ubah_nominal_pemasukan_ke_nominal_lebih_kecil_menurunkan_saldo(): void
    {
        $tx = $this->income(10_000_000);

        Ledger::changeIncomeAmount($tx->fresh(), 4_000_000);

        $this->assertSame(5_000_000, $this->balance());
        $this->assertSame(4_000_000, $tx->fresh()->allocatedMinor());
    }

    public function test_ubah_nominal_pemasukan_mode_lanjutan_dihitung_ulang_lewat_persentase_potret(): void
    {
        $this->user->update(['plans' => ['pro'], 'allocation_mode' => 'lanjutan']);
        $this->memberi->update(['allocation_cap_minor' => 500_000]);
        $this->diri->update(['allocation_cap_minor' => 2_000_000]);
        $tx = $this->income(5_000_000);

        Ledger::changeIncomeAmount($tx->fresh(), 10_000_000);

        // Persentase potret (10/40/50), bukan batas atas: batas atas hanya berlaku saat dicatat.
        $this->assertSame([1_000_000, 4_000_000, 5_000_000], $tx->fresh()->sortedAllocationEntries()->pluck('amount_minor')->all());
    }

    public function test_ubah_nominal_menolak_transaksi_yang_bukan_pemasukan_dan_nominal_tidak_sah(): void
    {
        $expense = Ledger::recordExpense($this->user, 1000, $this->belanja, $this->account, Carbon::today(), null);
        $income = $this->income(1_000_000);

        $this->assertRejected(LedgerException::NOT_AN_INCOME, fn () => Ledger::changeIncomeAmount($expense, 2000));
        $this->assertRejected(LedgerException::NOT_AN_EXPENSE, fn () => Ledger::changeExpenseAmount($income, 2000));
        $this->assertRejected(LedgerException::AMOUNT_NOT_POSITIVE, fn () => Ledger::changeIncomeAmount($income, 0));
    }

    public function test_ubah_nominal_pemasukan_tanpa_potret_tetap_tanpa_potret(): void
    {
        Room::query()->update(['allocation_percent' => null]);
        $tx = $this->income(1_000_000);

        Ledger::changeIncomeAmount($tx->fresh(), 2_000_000);

        $this->assertSame(0, AllocationEntry::count());
        $this->assertSame(3_000_000, $this->balance());
    }

    // --- pengeluaran: nominal, pos, akun ---

    public function test_ubah_nominal_pengeluaran_menggeser_saldo_sebesar_selisih(): void
    {
        $tx = Ledger::recordExpense($this->user, 200_000, $this->belanja, $this->account, Carbon::today(), null);

        Ledger::changeExpenseAmount($tx, 350_000);

        $this->assertSame(650_000, $this->balance());
        $this->assertSame(350_000, $tx->fresh()->amount_minor);
    }

    public function test_pindah_pos_mengganti_ruang_dan_nama_pos_tanpa_mengubah_saldo(): void
    {
        $tx = Ledger::recordExpense($this->user, 200_000, $this->belanja, $this->account, Carbon::today(), null);

        Ledger::changeExpenseCategory($tx, $this->investasi);

        $tx = $tx->fresh();
        $this->assertSame($this->diri->id, $tx->room_id);
        $this->assertSame($this->investasi->id, $tx->category_id);
        $this->assertSame('Investasi', $tx->category);
        $this->assertSame(800_000, $this->balance());
    }

    public function test_pindah_pos_menolak_pos_milik_orang_lain_atau_terarsip(): void
    {
        $tx = Ledger::recordExpense($this->user, 200_000, $this->belanja, $this->account, Carbon::today(), null);
        $otherRoom = Room::factory()->create(['user_id' => User::factory()->create()->id]);
        $otherCategory = Category::factory()->create(['room_id' => $otherRoom->id]);

        $this->assertRejected(LedgerException::CATEGORY_NOT_FOUND, fn () => Ledger::changeExpenseCategory($tx, $otherCategory));

        $this->investasi->update(['archived' => true]);
        $this->assertRejected(LedgerException::CATEGORY_ARCHIVED, fn () => Ledger::changeExpenseCategory($tx, $this->investasi->fresh()));

        $this->assertSame($this->belanja->id, $tx->fresh()->category_id);
    }

    public function test_pindah_akun_mengembalikan_akun_lama_dan_membebani_akun_baru(): void
    {
        $gopay = Account::factory()->create(['user_id' => $this->user->id, 'name' => 'GoPay', 'balance_minor' => 50_000, 'sort_order' => 2]);
        $expense = Ledger::recordExpense($this->user, 200_000, $this->belanja, $this->account, Carbon::today(), null);
        $income = $this->income(1_000_000);

        Ledger::changeAccount($expense, $gopay);
        $this->assertSame(1_000_000 + 1_000_000, $this->balance());
        $this->assertSame(-150_000, $this->balance($gopay));

        Ledger::changeAccount($income, $gopay);
        $this->assertSame(1_000_000, $this->balance());
        $this->assertSame(850_000, $this->balance($gopay));
        $this->assertSame($gopay->id, $income->fresh()->account_id);
    }

    public function test_pindah_akun_menolak_akun_milik_orang_lain_atau_terarsip(): void
    {
        $tx = Ledger::recordExpense($this->user, 200_000, $this->belanja, $this->account, Carbon::today(), null);
        $foreign = Account::factory()->create(['user_id' => User::factory()->create()->id]);
        $archived = Account::factory()->create(['user_id' => $this->user->id, 'name' => 'Lama', 'archived' => true, 'sort_order' => 2]);

        $this->assertRejected(LedgerException::ACCOUNT_NOT_FOUND, fn () => Ledger::changeAccount($tx, $foreign));
        $this->assertRejected(LedgerException::ACCOUNT_ARCHIVED, fn () => Ledger::changeAccount($tx, $archived));

        $this->assertSame($this->account->id, $tx->fresh()->account_id);
        $this->assertSame(800_000, $this->balance());
    }

    // --- delete ---

    public function test_hapus_pemasukan_membalik_saldo_dan_menghapus_potret(): void
    {
        $tx = $this->income(10_000_000);
        $this->assertSame(3, AllocationEntry::count());

        Ledger::delete($tx->fresh());

        $this->assertSame(1_000_000, $this->balance());
        $this->assertSame(0, Transaction::count());
        $this->assertSame(0, AllocationEntry::count());
    }

    public function test_hapus_pengeluaran_mengembalikan_uangnya(): void
    {
        $tx = Ledger::recordExpense($this->user, 300_000, $this->belanja, $this->account, Carbon::today(), null);
        $this->assertSame(700_000, $this->balance());

        Ledger::delete($tx);

        $this->assertSame(1_000_000, $this->balance());
        $this->assertSame(0, Transaction::count());
    }

    // --- label ---

    public function test_label_pembagian_untuk_pil_aturan(): void
    {
        $this->assertSame('10 / 30 / 60', Ledger::shareLabel([1000, 3000, 6000]));
        $this->assertSame('2,5 / 97,5', Ledger::shareLabel([250, 9750]));
        $this->assertSame('0,05', Ledger::shareLabel([5]));
        $this->assertSame('100', Ledger::shareLabel([10_000]));
    }
}
