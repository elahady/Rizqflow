<?php

namespace Tests\Feature;

use App\Livewire\Denah;
use App\Models\Account;
use App\Models\Bill;
use App\Models\Category;
use App\Models\RecurringRule;
use App\Models\Room;
use App\Models\User;
use App\Models\ZakatProfile;
use App\Support\Ledger;
use App\Support\Rupiah;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

/**
 * Halaman Denah (mockup Main.dc.html). "Hari ini" dibekukan di 24 Oktober 2026 (seperti mockup).
 * Dataset: gaji 18.000.000 tanggal 22 Okt dengan aturan 10 / 30 / 60.
 */
class DenahTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Account $account;

    private Room $memberi;

    private Room $diri;

    private Room $keluarga;

    /** @var array<string, Category> */
    private array $pos = [];

    protected function setUp(): void
    {
        parent::setUp();

        $this->travelTo(CarbonImmutable::parse('2026-10-24 10:00:00'));

        $this->user = User::factory()->create(['name' => 'Ayu']);
        $this->account = Account::factory()->create(['user_id' => $this->user->id]);

        $this->memberi = Room::factory()->percent(10)->type(Room::TYPE_MENUNAIKAN)->create(['user_id' => $this->user->id, 'name' => 'Memberi', 'icon' => 'memberi', 'slot' => 1, 'sort_order' => 1]);
        $this->diri = Room::factory()->percent(30)->type(Room::TYPE_MENUMBUHKAN)->create(['user_id' => $this->user->id, 'name' => 'Diri', 'icon' => 'diri', 'slot' => 2, 'sort_order' => 2]);
        $this->keluarga = Room::factory()->percent(60)->create(['user_id' => $this->user->id, 'name' => 'Keluarga', 'slot' => 3, 'sort_order' => 3]);

        foreach ([$this->memberi, $this->diri, $this->keluarga] as $room) {
            $this->pos[$room->name] = Category::factory()->create(['room_id' => $room->id, 'name' => 'Pos '.$room->name, 'sort_order' => 1]);
            $this->pos[$room->name.'/tak'] = Category::factory()->untracked()->create(['room_id' => $room->id, 'sort_order' => 2]);
        }
    }

    private function income(int $amount = 18_000_000, string $date = '2026-10-22', string $note = 'Gaji Oktober'): void
    {
        Ledger::recordIncome($this->user, $amount, $this->account, CarbonImmutable::parse($date), 'Gaji', $note);
    }

    private function spend(string $pos, int $amount, string $date = '2026-10-10', ?string $note = null): void
    {
        Ledger::recordExpense($this->user, $amount, $this->pos[$pos], $this->account, CarbonImmutable::parse($date), $note);
    }

    private function denah(array $query = [])
    {
        return Livewire::withQueryParams($query)->actingAs($this->user)->test(Denah::class);
    }

    // --- kepala dan kartu rezeki ---

    public function test_kartu_rezeki_menampilkan_angka_bulan_ini(): void
    {
        $this->income();
        $this->spend('Memberi', 1_800_000);
        $this->spend('Diri', 3_132_000);
        $this->spend('Keluarga', 9_396_000);

        $this->denah()
            ->assertSee('Selamat datang, Ayu')
            ->assertSee('Sabtu, 24 Oktober 2026')
            ->assertSee(Rupiah::format(18_000_000))
            ->assertSee('Dialirkan 22 Okt dari Gaji Oktober · aturan 10 / 30 / 60', false)
            ->assertSee(Rupiah::format(14_328_000))
            ->assertSee(Rupiah::format(3_672_000))
            ->assertSee('8 hari lagi, tersebar di Diri dan Keluarga')
            ->assertSee('Semua rezeki sudah punya tujuan')
            ->assertSee('Rezeki Oktober sudah dialirkan penuh. Satu ruang perlu perhatian.')
            ->assertSee(route('transaksi.rezeki'), false)
            ->assertSee(route('laporan'), false);
    }

    public function test_lead_menyebut_transaksi_tanpa_pos(): void
    {
        $this->income();
        $this->spend('Memberi/tak', 100_000);
        $this->spend('Memberi/tak', 100_000);
        $this->spend('Memberi/tak', 100_000);
        $this->spend('Keluarga', 9_400_000);

        $this->denah()
            ->assertSee('Rezeki Oktober sudah dialirkan penuh. Satu ruang perlu perhatian dan tiga transaksi belum punya pos.')
            ->assertSee('3 transaksi di Tak terlacak ('.Rupiah::format(300_000).') belum punya pos.')
            ->assertSee(route('transaksi', ['tab' => 'tak']), false);
    }

    public function test_lead_tanpa_rezeki_dan_tanpa_masalah(): void
    {
        $this->denah()
            ->assertSee('Belum ada rezeki masuk di Oktober. Catat rezeki masuk supaya bisa dialirkan ke ruang.')
            ->assertSee('Belum ada rezeki yang perlu dialirkan')
            ->assertSee('Semuanya tenang');
    }

    public function test_lead_saat_sebagian_rezeki_belum_dialirkan(): void
    {
        $this->diri->update(['allocation_percent' => 20]); // total 90%: 10% belum dialirkan
        $this->income(10_000_000);

        $this->denah()
            ->assertSee('Sebagian rezeki Oktober belum dialirkan.')
            ->assertSee('Belum dialirkan')
            ->assertSee(Rupiah::format(1_000_000))
            ->assertSee(route('aturan-alokasi'), false);
    }

    public function test_bar_dan_legenda_memakai_jatah_aktual_bulan_itu(): void
    {
        $this->income(10_000_000);
        // Aturan berubah sesudah pemasukan dicatat: bar tetap memakai potret 10 / 30 / 60.
        $this->diri->update(['allocation_percent' => 10]);
        $this->keluarga->update(['allocation_percent' => 80]);

        $this->denah()
            ->assertSee('Memberi 10%')
            ->assertSee('Diri 30%')
            ->assertSee('Keluarga 60%');
    }

    public function test_terpakai_dibandingkan_dengan_bulan_lalu_pada_tanggal_yang_sama(): void
    {
        $this->income(18_000_000, '2026-09-25');
        $this->income();
        $this->spend('Keluarga', 1_000_000, '2026-09-10');
        $this->spend('Keluarga', 5_000_000, '2026-09-26'); // setelah tanggal 24: tidak dihitung
        $this->spend('Keluarga', 970_000, '2026-10-10');

        $this->denah()->assertSee(Rupiah::format(30_000).' (3%) di bawah September pada tanggal yang sama');
    }

    public function test_terpakai_lebih_besar_dari_bulan_lalu_disebut_di_atas(): void
    {
        $this->income();
        $this->spend('Keluarga', 1_000_000, '2026-09-10');
        $this->spend('Keluarga', 1_500_000, '2026-10-10');

        $this->denah()->assertSee(Rupiah::format(500_000).' (50%) di atas September pada tanggal yang sama');
    }

    // --- kartu ruang ---

    public function test_kartu_ruang_menampilkan_status_dan_laju(): void
    {
        $this->income();
        $this->spend('Memberi', 1_800_000);
        $this->spend('Diri', 3_132_000);
        $this->spend('Keluarga', 9_396_000);

        $this->denah()
            ->assertSee('Menunaikan · 10%')
            ->assertSee('Mencukupi · 60%')
            ->assertSee('Terpenuhi')
            ->assertSee('Seluruh jatah bulan ini sudah tersalurkan.')
            ->assertSee('Hari ke-24 dari 31 · laju di bawah waktu')
            ->assertSee('Hari ke-24 dari 31 · laju di atas waktu')
            ->assertSee(Rupiah::format(2_268_000))
            ->assertSee('aman ≈ '.Rupiah::format(283_500).' per hari')
            ->assertSee('aman ≈ '.Rupiah::format(175_500).' per hari')
            ->assertSee(route('ruang.detail', $this->keluarga), false);
    }

    public function test_ruang_tanpa_jatah_menunggu_rezeki(): void
    {
        $this->denah()->assertSee('Menunggu rezeki dialirkan ke ruang ini.')->assertSee('Menunggu rezeki');
    }

    public function test_pengguna_tanpa_ruang_melihat_keadaan_kosong(): void
    {
        $other = User::factory()->create();

        Livewire::actingAs($other)->test(Denah::class)
            ->assertOk()
            ->assertSee('Belum ada ruang')
            ->assertSee('Belum ada ruang dan belum ada rezeki.')
            ->assertSee('Tidak ada tagihan atau transaksi berulang dalam 7 hari ke depan.')
            ->assertSee('Belum ada transaksi di Oktober');
    }

    // --- jadwal 7 hari ---

    private function bill(string $name, int $amount, string $due, array $extra = [], ?User $owner = null): Bill
    {
        $owner ??= $this->user;
        if ($owner->is($this->user)) {
            $account = $this->account;
            $category = $this->pos['Keluarga'];
        } else {
            $account = Account::factory()->create(['user_id' => $owner->id]);
            $category = Category::factory()->create(['room_id' => Room::factory()->create(['user_id' => $owner->id])->id]);
        }

        return Bill::create($extra + [
            'user_id' => $owner->id, 'name' => $name, 'amount_minor' => $amount, 'account_id' => $account->id,
            'room_id' => $category->room_id, 'category_id' => $category->id,
            'start_date' => $due, 'next_due' => $due,
        ]);
    }

    public function test_jadwal_7_hari_memuat_tagihan_dan_transaksi_berulang(): void
    {
        $this->bill('Cicilan motor', 1_150_000, '2026-10-26');
        $this->bill('Premi asuransi', 640_000, '2026-10-31');
        $this->bill('Pajak tahunan', 900_000, '2026-11-20'); // di luar 7 hari
        $this->bill('Listrik', 400_000, '2026-10-22'); // terlambat
        $this->bill('Bayar hari ini', 50_000, '2026-10-24');
        $this->bill('Sudah lunas', 70_000, '2026-10-25', ['paid_count' => 1]);
        RecurringRule::create([
            'user_id' => $this->user->id, 'type' => 'pengeluaran', 'amount_minor' => 385_000, 'account_id' => $this->account->id,
            'room_id' => $this->keluarga->id, 'category_id' => $this->pos['Keluarga']->id, 'note' => 'Internet rumah',
            'frequency' => 'bulanan', 'start_date' => '2026-09-28', 'next_due' => '2026-10-28',
        ]);

        $this->denah()
            ->assertSee('Jadwal 7 hari')
            ->assertSee('Cicilan motor')
            ->assertSee('Tagihan · dibayar manual')
            ->assertSee(Rupiah::format(1_150_000))
            ->assertSee('Segera')
            ->assertSee('Internet rumah')
            ->assertSee('Berulang · dicatat otomatis')
            ->assertSee('Mendatang')
            ->assertSee('Premi asuransi')
            ->assertSee('Terlambat')
            ->assertSee('Listrik')
            ->assertSee('Bayar hari ini')
            ->assertSee('Hari ini')
            ->assertSee(route('jadwal'), false)
            ->assertDontSee('Pajak tahunan')
            ->assertDontSee('Sudah lunas');
    }

    public function test_jadwal_tidak_membocorkan_tagihan_user_lain(): void
    {
        $this->bill('Tagihan rahasia', 123_000, '2026-10-26', [], User::factory()->create());

        $this->denah()->assertDontSee('Tagihan rahasia')->assertSee('Tidak ada tagihan atau transaksi berulang dalam 7 hari ke depan.');
    }

    // --- perlu perhatian ---

    public function test_perlu_perhatian_memuat_ruang_pos_haul_dan_tak_terlacak(): void
    {
        $this->income();
        // Dua pos berbobot sama di Keluarga: jatah masing-masing 5.400.000; satu pos terpakai 6.480.000 (120%).
        $this->pos['Keluarga']->update(['weight' => 50]);
        $anak = Category::factory()->create(['room_id' => $this->keluarga->id, 'name' => 'Anak dan sekolah', 'weight' => 50, 'sort_order' => 3]);
        Ledger::recordExpense($this->user, 6_480_000, $anak, $this->account, CarbonImmutable::parse('2026-10-10'), null);
        $this->spend('Keluarga', 3_000_000);
        $this->spend('Memberi/tak', 200_000);
        ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama', 'status' => 'haul_berjalan', 'haul_day' => 337, 'haul_total_days' => 354]);

        $this->denah()
            ->assertSee('Keluarga terpakai 87%; sisa '.Rupiah::format(1_320_000).' untuk 8 hari')
            ->assertSee('Pos Anak dan sekolah melewati jatah 20%')
            ->assertSee('Melewati jatah')
            ->assertSee('Haul zakat jatuh tempo ≈ 10 Nov (17 hari lagi)')
            ->assertSee('1 transaksi di Tak terlacak ('.Rupiah::format(200_000).') belum punya pos.');
    }

    public function test_haul_yang_masih_jauh_tidak_ditampilkan(): void
    {
        ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama', 'status' => 'haul_berjalan', 'haul_day' => 100, 'haul_total_days' => 354]);

        $this->denah()->assertDontSee('Haul zakat');
    }

    public function test_ruang_yang_lewat_jatah_disebut_tenang(): void
    {
        $this->income(1_000_000);
        $this->spend('Keluarga', 700_000);

        $this->denah()
            ->assertSee('Keluarga melewati jatah bulan ini (116% terpakai)')
            ->assertSee('Melewati jatah <span class="dn-bold">'.Rupiah::format(100_000).'</span>', false);
    }

    // --- periode dan batas data ---

    public function test_bulan_lain_lewat_query_memakai_angka_bulan_itu_tanpa_jadwal(): void
    {
        $this->income(12_000_000, '2026-09-05', 'Gaji September');
        $this->spend('Keluarga', 2_000_000, '2026-09-12', 'Belanja bulanan');
        $this->income();
        $this->bill('Cicilan motor', 1_150_000, '2026-10-26');

        $this->denah(['bulan' => '2026-09'])
            ->assertSee('Rezeki September')
            ->assertSee(Rupiah::format(12_000_000))
            ->assertSee('Dialirkan 5 Sep dari Gaji September')
            ->assertSee('Rezeki September sudah dialirkan penuh.')
            ->assertSee('Belanja bulanan')
            ->assertDontSee('Jadwal 7 hari')
            ->assertDontSee('Cicilan motor')
            ->assertDontSee(Rupiah::format(18_000_000));
    }

    public function test_data_user_lain_tidak_bocor(): void
    {
        $other = User::factory()->create(['name' => 'Orang Lain']);
        $otherAccount = Account::factory()->create(['user_id' => $other->id]);
        $room = Room::factory()->percent(100)->create(['user_id' => $other->id, 'name' => 'Ruang Rahasia']);
        $category = Category::factory()->create(['room_id' => $room->id, 'name' => 'Pos Rahasia']);
        Ledger::recordIncome($other, 77_777_000, $otherAccount, CarbonImmutable::parse('2026-10-20'), 'Gaji', 'Gaji rahasia');
        Ledger::recordExpense($other, 1_234_000, $category, $otherAccount, CarbonImmutable::parse('2026-10-21'), 'Belanja rahasia');
        $this->income();

        $this->denah()
            ->assertSee(Rupiah::format(18_000_000))
            ->assertDontSee('Ruang Rahasia')
            ->assertDontSee('Gaji rahasia')
            ->assertDontSee('Belanja rahasia')
            ->assertDontSee(Rupiah::format(77_777_000));
    }

    public function test_transaksi_terbaru_lima_item_dengan_pemasukan_dialirkan(): void
    {
        $this->income();
        foreach ([1, 2, 3, 4, 5, 6] as $i) {
            $this->spend('Keluarga', $i * 1_000, '2026-10-0'.$i, 'Belanja ke-'.$i);
        }

        // Pemasukan 22 Okt dan lima belanja terbaru (tanggal 2..6): belanja ke-1 tersingkir.
        $this->denah()
            ->assertSee('Transaksi terbaru')
            ->assertSee('Belanja ke-6')
            ->assertSee('Belanja ke-3')
            ->assertDontSee('Belanja ke-2')
            ->assertDontSee('Belanja ke-1')
            ->assertSee('Gaji Oktober')
            ->assertSee('Dialirkan 10 / 30 / 60')
            ->assertSee('+'.Rupiah::format(18_000_000))
            ->assertSee("\u{2212}".Rupiah::format(6_000));
    }

    public function test_halaman_dirender_lewat_http_dengan_pemilih_periode(): void
    {
        $this->income();

        $this->actingAs($this->user)->get(route('denah'))
            ->assertOk()
            ->assertSee('Oktober 2026')
            ->assertSee('Selamat datang, Ayu');
        $this->actingAs($this->user)->get(route('denah', ['bulan' => 'ngawur']))->assertOk();
    }
}
