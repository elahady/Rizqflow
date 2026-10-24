<?php

namespace Tests\Feature;

use App\Livewire\AlirkanRezeki;
use App\Models\Account;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Support\Rupiah;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

/** Halaman Catat rezeki masuk (mockup Alirkan.dc.html): pratinjau aliran dan simpan lewat Ledger. */
class AlirkanRezekiTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Account $account;

    private Room $memberi;

    private Room $diri;

    private Room $keluarga;

    protected function setUp(): void
    {
        parent::setUp();

        $this->travelTo(CarbonImmutable::parse('2026-10-24 10:00:00'));

        $this->user = User::factory()->create();
        $this->account = Account::factory()->create(['user_id' => $this->user->id, 'name' => 'BCA', 'balance_minor' => 500_000]);
        $this->memberi = Room::factory()->percent(10)->type(Room::TYPE_MENUNAIKAN)->create(['user_id' => $this->user->id, 'name' => 'Memberi', 'icon' => 'memberi', 'slot' => 1, 'sort_order' => 1]);
        $this->diri = Room::factory()->percent(30)->type(Room::TYPE_MENUMBUHKAN)->create(['user_id' => $this->user->id, 'name' => 'Diri', 'icon' => 'diri', 'slot' => 2, 'sort_order' => 2]);
        $this->keluarga = Room::factory()->percent(60)->create(['user_id' => $this->user->id, 'name' => 'Keluarga', 'slot' => 3, 'sort_order' => 3]);
    }

    private function page(array $query = [])
    {
        return Livewire::withQueryParams($query)->actingAs($this->user)->test(AlirkanRezeki::class);
    }

    public function test_halaman_dirender_dengan_pratinjau_kosong(): void
    {
        $this->actingAs($this->user)->get(route('transaksi.rezeki'))
            ->assertOk()
            ->assertSee('Catat rezeki masuk')
            ->assertSee('Pratinjau aliran')
            ->assertSee('Aturan 10 / 30 / 60')
            ->assertSee('Ketik nominal untuk melihat pembagiannya.')
            ->assertSee('Gaji')->assertSee('Usaha')->assertSee('Lainnya')
            ->assertDontSee('Bonus');
    }

    public function test_pratinjau_1234567_dengan_aturan_10_30_60_menunjukkan_pembulatan(): void
    {
        $this->page()
            ->set('amount', '1.234.567')
            ->assertSee(Rupiah::format(1_234_567))
            ->assertSee(Rupiah::format(123_457))
            ->assertSee(Rupiah::format(370_370))
            ->assertSee(Rupiah::format(740_740))
            ->assertSee('Jumlah bagian = nominal')
            ->assertSee('Rp 123.457 + Rp 370.370 + Rp 740.740 = Rp 1.234.567')
            ->assertSee('Pembulatan: sisa Rp 1 dibagikan satu-satu');
    }

    public function test_pratinjau_tanpa_sisa_pembulatan_tidak_menampilkan_catatannya(): void
    {
        $this->page()
            ->set('amount', '18000000')
            ->assertSee(Rupiah::format(1_800_000))
            ->assertSee(Rupiah::format(5_400_000))
            ->assertSee(Rupiah::format(10_800_000))
            ->assertDontSee('Pembulatan: sisa');
    }

    public function test_chip_contoh_mengisi_nominal(): void
    {
        $this->page()
            ->call('useAmount', 18_000_000)
            ->assertSet('amount', '18.000.000')
            ->call('useAmount', 1_234_567)
            ->assertSet('amount', '1.234.567');
    }

    public function test_pratinjau_menyebut_bagian_yang_belum_dialirkan(): void
    {
        $this->diri->update(['allocation_percent' => 20]);

        $this->page()
            ->set('amount', '10000000')
            ->assertSee('Belum dialirkan')
            ->assertSee('Sebagian belum dialirkan')
            ->assertSee(Rupiah::format(1_000_000));
    }

    public function test_simpan_membuat_potret_dan_menambah_saldo(): void
    {
        $this->page()
            ->set('amount', '1.234.567')
            ->set('source', 'Usaha')
            ->set('note', 'Proyek kecil')
            ->set('date', '2026-10-22')
            ->call('save')
            ->assertHasNoErrors()
            ->assertRedirect(route('transaksi'));

        $tx = Transaction::sole();
        $this->assertSame($this->user->id, $tx->user_id);
        $this->assertSame(1_234_567, $tx->amount_minor);
        $this->assertSame('Usaha', $tx->category);
        $this->assertSame('Proyek kecil', $tx->note);
        $this->assertSame('2026-10-22', $tx->occurred_on->toDateString());
        $this->assertSame('10 / 30 / 60', $tx->allocationLabel());
        $this->assertSame([123_457, 370_370, 740_740], $tx->sortedAllocationEntries()->pluck('amount_minor')->all());
        $this->assertSame(500_000 + 1_234_567, $this->account->fresh()->balance_minor);
        $this->assertSame('Rezeki Rp 1.234.567 dialirkan ke 3 ruang.', session('status'));
    }

    public function test_potret_tidak_berubah_saat_aturan_diubah_sesudahnya(): void
    {
        $this->page()->set('amount', '10000000')->call('save');
        $this->keluarga->update(['allocation_percent' => 50]);

        $this->assertSame('10 / 30 / 60', Transaction::sole()->allocationLabel());
    }

    public function test_nominal_kosong_atau_nol_ditolak(): void
    {
        $this->page()->call('save')->assertHasErrors(['amount']);
        $this->page()->set('amount', '0')->call('save')->assertHasErrors(['amount']);
        $this->page()->set('amount', 'abc')->call('save')->assertHasErrors(['amount']);

        $this->assertSame(0, Transaction::count());
        $this->assertSame(500_000, $this->account->fresh()->balance_minor);
    }

    public function test_nominal_terlalu_besar_tampil_sebagai_galat_ledger(): void
    {
        $this->page()
            ->set('amount', '99999999999999999999')
            ->call('save')
            ->assertNoRedirect();

        $this->assertSame(0, Transaction::count());
    }

    public function test_tanggal_sumber_dan_catatan_divalidasi(): void
    {
        $this->page()->set('amount', '1000')->set('date', 'kemarin')->call('save')->assertHasErrors(['date']);
        $this->page()->set('amount', '1000')->set('date', '2026-02-31')->call('save')->assertHasErrors(['date']);
        $this->page()->set('amount', '1000')->set('source', 'Bonus')->call('save')->assertHasErrors(['source']);
        $this->page()->set('amount', '1000')->set('note', str_repeat('a', 201))->call('save')->assertHasErrors(['note']);

        $this->assertSame(0, Transaction::count());
    }

    public function test_akun_milik_user_lain_ditolak(): void
    {
        $foreign = Account::factory()->create(['user_id' => User::factory()->create()->id, 'balance_minor' => 0]);

        $this->page()
            ->set('amount', '1000000')
            ->set('accountId', $foreign->id)
            ->call('save')
            ->assertHasErrors(['accountId'])
            ->assertNoRedirect();

        $this->assertSame(0, Transaction::count());
        $this->assertSame(0, $foreign->fresh()->balance_minor);
    }

    public function test_akun_terarsip_ditolak(): void
    {
        $this->account->update(['archived' => true]);

        $this->page()->set('amount', '1000000')->call('save')->assertHasErrors(['accountId']);
        $this->assertSame(0, Transaction::count());
    }

    public function test_prefill_dari_query_nominal_dan_catatan(): void
    {
        $this->page(['nominal' => '18000000', 'catatan' => 'Gaji Oktober'])
            ->assertSet('amount', '18.000.000')
            ->assertSet('note', 'Gaji Oktober')
            ->assertSet('source', 'Gaji')
            ->assertSet('date', '2026-10-24')
            ->assertSet('accountId', $this->account->id)
            ->assertSee(Rupiah::format(10_800_000));
    }

    public function test_akun_bawaan_adalah_yang_terakhir_dipakai_pemasukan(): void
    {
        $other = Account::factory()->create(['user_id' => $this->user->id, 'name' => 'GoPay', 'sort_order' => 2]);
        $this->page()->set('amount', '1000')->set('accountId', $other->id)->call('save');

        $this->page()->assertSet('accountId', $other->id);
    }

    public function test_tanpa_aturan_rezeki_tetap_tercatat_belum_dialirkan(): void
    {
        $this->user->rooms()->update(['allocation_percent' => null]);

        $this->page()
            ->set('amount', '2000000')
            ->assertSee('Belum ada aturan alokasi')
            ->assertSee(route('aturan-alokasi'), false)
            ->call('save')
            ->assertRedirect(route('transaksi'));

        $tx = Transaction::sole();
        $this->assertSame(0, $tx->allocationEntries()->count());
        $this->assertSame(2_000_000, $tx->unallocatedMinor());
        $this->assertSame('info', session('status_tone'));
    }

    public function test_pengguna_tanpa_ruang_diarahkan_ke_aturan_alokasi(): void
    {
        $lonely = User::factory()->create();
        Account::factory()->create(['user_id' => $lonely->id]);

        Livewire::actingAs($lonely)->test(AlirkanRezeki::class)
            ->assertSee('Belum ada aturan alokasi')
            ->assertSee(route('aturan-alokasi'), false);
    }

    public function test_aturan_melebihi_100_persen_tampil_sebagai_galat(): void
    {
        $this->keluarga->update(['allocation_percent' => 80]);

        $this->page()
            ->set('amount', '1000000')
            ->assertSee('Total aturan alokasi melebihi 100%')
            ->call('save')
            ->assertNoRedirect();

        $this->assertSame(0, Transaction::count());
    }

    public function test_mode_lanjutan_pro_menampilkan_batas_atas(): void
    {
        $this->user->update(['plans' => ['pro'], 'allocation_mode' => 'lanjutan']);
        $this->memberi->update(['allocation_cap_minor' => 500_000]);

        $this->page()
            ->set('amount', '2000000')
            ->assertSee('Aturan Prioritas · Pro')
            ->assertSee('batas atas '.Rupiah::format(500_000))
            ->assertSee('tanpa batas atas');
    }

    public function test_pratinjau_dan_simpan_memakai_ruang_user_sendiri(): void
    {
        $other = User::factory()->create();
        Room::factory()->percent(100)->create(['user_id' => $other->id, 'name' => 'Ruang Rahasia']);

        $this->page()->set('amount', '1000000')->assertDontSee('Ruang Rahasia');
    }
}
