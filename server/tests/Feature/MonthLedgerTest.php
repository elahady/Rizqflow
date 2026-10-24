<?php

namespace Tests\Feature;

use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Support\Ledger;
use App\Support\MonthLedger;
use App\Support\RoomMonth;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Tests\TestCase;

/**
 * MonthLedger/RoomMonth = porting DenahLoader, RoomStatusRules, SafeToSpend, dan AttentionItem
 * (domain/.../ledger/Denah.kt): semua angka bulan diturunkan dari transaksi dan potret alokasi.
 * "Hari ini" selalu dilewatkan eksplisit (24 Oktober 2026, seperti mockup) supaya tes stabil.
 */
class MonthLedgerTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Account $account;

    private Room $memberi;

    private Room $diri;

    private Room $keluarga;

    /** @var array<string, Category> */
    private array $pos = [];

    private CarbonImmutable $today;

    protected function setUp(): void
    {
        parent::setUp();

        $this->today = CarbonImmutable::parse('2026-10-24');
        $this->user = User::factory()->create();
        $this->account = Account::factory()->create(['user_id' => $this->user->id]);

        $this->memberi = Room::factory()->percent(10)->type(Room::TYPE_MENUNAIKAN)->create(['user_id' => $this->user->id, 'name' => 'Memberi', 'slot' => 1, 'sort_order' => 1]);
        $this->diri = Room::factory()->percent(30)->type(Room::TYPE_MENUMBUHKAN)->create(['user_id' => $this->user->id, 'name' => 'Diri', 'slot' => 2, 'sort_order' => 2]);
        $this->keluarga = Room::factory()->percent(60)->create(['user_id' => $this->user->id, 'name' => 'Keluarga', 'slot' => 3, 'sort_order' => 3]);

        foreach ([$this->memberi, $this->diri, $this->keluarga] as $room) {
            $this->pos[$room->name] = Category::factory()->create(['room_id' => $room->id, 'name' => 'Pos '.$room->name, 'sort_order' => 1]);
            $this->pos[$room->name.'/tak'] = Category::factory()->untracked()->create(['room_id' => $room->id, 'sort_order' => 2]);
        }
    }

    private function income(int $amount, string $date = '2026-10-22'): Transaction
    {
        return Ledger::recordIncome($this->user, $amount, $this->account, CarbonImmutable::parse($date), 'Gaji', null);
    }

    private function spend(string $pos, int $amount, string $date = '2026-10-10'): Transaction
    {
        return Ledger::recordExpense($this->user, $amount, $this->pos[$pos], $this->account, CarbonImmutable::parse($date), null);
    }

    private function ledger(string $month = '2026-10-01', ?string $today = null): MonthLedger
    {
        return MonthLedger::for($this->user, CarbonImmutable::parse($month), $today ? CarbonImmutable::parse($today) : $this->today);
    }

    // --- angka bulan ---

    public function test_pengguna_tanpa_data_semuanya_nol_dan_ruang_menunggu(): void
    {
        $empty = MonthLedger::for(User::factory()->create(), $this->today, $this->today);

        $this->assertSame(0, $empty->incomeMinor);
        $this->assertSame(0, $empty->allocatedMinor);
        $this->assertSame(0, $empty->unallocatedMinor);
        $this->assertSame(0, $empty->spentMinor);
        $this->assertTrue($empty->rooms()->isEmpty());
        $this->assertNull($empty->safeToSpend());
        $this->assertSame([], $empty->attention());

        $ledger = $this->ledger();
        foreach ($ledger->rooms() as $room) {
            $this->assertSame(RoomMonth::STATUS_MENUNGGU, $room->status());
            $this->assertNull($room->progressBp);
            $this->assertNull($room->percent());
        }
    }

    public function test_rezeki_jatah_dan_terpakai_mengikuti_mockup(): void
    {
        $this->income(18_000_000);
        $this->spend('Memberi', 1_800_000);
        $this->spend('Diri', 3_132_000);
        $this->spend('Keluarga', 9_396_000);

        $ledger = $this->ledger();

        $this->assertSame(18_000_000, $ledger->incomeMinor);
        $this->assertSame(18_000_000, $ledger->allocatedMinor);
        $this->assertSame(0, $ledger->unallocatedMinor);
        $this->assertSame(14_328_000, $ledger->spentMinor);

        [$memberi, $diri, $keluarga] = $ledger->rooms()->all();
        $this->assertSame([1_800_000, 5_400_000, 10_800_000], [$memberi->allocatedMinor, $diri->allocatedMinor, $keluarga->allocatedMinor]);
        $this->assertSame([1_800_000, 3_132_000, 9_396_000], [$memberi->spentMinor, $diri->spentMinor, $keluarga->spentMinor]);
        $this->assertSame([100, 58, 87], [$memberi->percent(), $diri->percent(), $keluarga->percent()]);
        $this->assertSame(1_404_000, $keluarga->remainingMinor());
        $this->assertSame(['terpenuhi', 'berjalan', 'perlu_perhatian'], [$memberi->status(), $diri->status(), $keluarga->status()]);
        $this->assertSame(['Terpenuhi', 'Berjalan', 'Perlu perhatian'], [$memberi->statusLabel(), $diri->statusLabel(), $keluarga->statusLabel()]);
        $this->assertSame(['good', 'neutral', 'warn'], [$memberi->statusTone(), $diri->statusTone(), $keluarga->statusTone()]);
        $this->assertSame(1000, $memberi->shareBp);
    }

    public function test_aturan_di_bawah_100_persen_menyisakan_belum_dialirkan(): void
    {
        $this->keluarga->update(['allocation_percent' => 30]);
        $this->income(10_000_000);

        $ledger = $this->ledger();

        $this->assertSame(10_000_000, $ledger->incomeMinor);
        $this->assertSame(7_000_000, $ledger->allocatedMinor);
        $this->assertSame(3_000_000, $ledger->unallocatedMinor);
    }

    public function test_hanya_transaksi_bulan_itu_yang_terhitung_termasuk_hari_terakhir(): void
    {
        $this->income(10_000_000, '2026-10-01');
        $this->income(10_000_000, '2026-09-30');
        $this->income(10_000_000, '2026-11-01');
        $this->spend('Keluarga', 100_000, '2026-10-31');
        $this->spend('Keluarga', 200_000, '2026-09-30');
        $this->spend('Keluarga', 400_000, '2026-11-01');

        $ledger = $this->ledger();

        $this->assertSame(10_000_000, $ledger->incomeMinor);
        $this->assertSame(100_000, $ledger->spentMinor);
        $this->assertSame(6_000_000, $ledger->room($this->keluarga)->allocatedMinor);
    }

    public function test_ruang_terarsip_tidak_tampil_tetapi_alokasinya_tetap_terhitung(): void
    {
        $this->income(10_000_000);
        $this->spend('Memberi', 300_000);
        $this->memberi->update(['archived' => true]);

        $ledger = $this->ledger();

        $this->assertSame(['Diri', 'Keluarga'], $ledger->rooms()->map(fn (RoomMonth $r) => $r->room->name)->all());
        $this->assertSame(10_000_000, $ledger->allocatedMinor);
        $this->assertSame(0, $ledger->unallocatedMinor);
        $this->assertSame(300_000, $ledger->spentMinor);
        // Riwayat ruang terarsip tetap bisa dibuka lewat room().
        $this->assertSame(1_000_000, $ledger->room($this->memberi)->allocatedMinor);
    }

    public function test_data_pengguna_lain_tidak_ikut_terhitung(): void
    {
        $other = User::factory()->create();
        $otherAccount = Account::factory()->create(['user_id' => $other->id]);
        $otherRoom = Room::factory()->percent(100)->create(['user_id' => $other->id]);
        Ledger::recordIncome($other, 99_000_000, $otherAccount, $this->today, null, null);

        $ledger = $this->ledger();

        $this->assertSame(0, $ledger->incomeMinor);
        $this->assertNull($ledger->room($otherRoom));
        $this->assertSame(0, $ledger->room($this->keluarga)->allocatedMinor);
    }

    // --- status ruang (RoomStatusRules) ---

    public function test_mencukupi_perlu_perhatian_tepat_di_85_persen(): void
    {
        $this->income(10_000_000); // Keluarga 6.000.000

        $this->spend('Keluarga', 5_099_999);
        $this->assertSame('berjalan', $this->ledger()->room($this->keluarga)->status());

        $this->spend('Keluarga', 1);
        $this->assertSame(8500, $this->ledger()->room($this->keluarga)->progressBp);
        $this->assertSame('perlu_perhatian', $this->ledger()->room($this->keluarga)->status());
    }

    public function test_menunaikan_dan_menumbuhkan_terpenuhi_tepat_di_jatah(): void
    {
        $this->income(10_000_000); // Memberi 1.000.000, Diri 3.000.000

        $this->spend('Memberi', 999_999);
        $this->spend('Diri', 3_000_000);

        $ledger = $this->ledger();
        $this->assertSame('berjalan', $ledger->room($this->memberi)->status());
        $this->assertSame('terpenuhi', $ledger->room($this->diri)->status());

        // Lewat jatah juga terpenuhi, bukan peringatan.
        $this->spend('Diri', 500_000);
        $this->assertSame('terpenuhi', $this->ledger()->room($this->diri)->status());
    }

    public function test_bulan_lampau_menunaikan_belum_tercapai_dan_mencukupi_menurut_jatah(): void
    {
        $this->income(10_000_000, '2026-09-22');
        $this->spend('Memberi', 400_000, '2026-09-10');
        $this->spend('Keluarga', 6_000_001, '2026-09-12'); // lebih Rp 1 dari jatah

        $ledger = $this->ledger('2026-09-01');

        $this->assertSame('belum_tercapai', $ledger->room($this->memberi)->status());
        $this->assertSame('Belum tercapai', $ledger->room($this->memberi)->statusLabel());
        $this->assertSame('neutral', $ledger->room($this->memberi)->statusTone());
        $this->assertSame('perlu_perhatian', $ledger->room($this->keluarga)->status());
    }

    public function test_mencukupi_bulan_lampau_yang_tidak_melebihi_jatah_terpenuhi(): void
    {
        $this->income(10_000_000, '2026-09-22');
        $this->spend('Keluarga', 5_999_999, '2026-09-12'); // 99,99% dari 6.000.000: di atas 85% tapi bulannya sudah berakhir

        $this->assertSame('terpenuhi', $this->ledger('2026-09-01')->room($this->keluarga)->status());
    }

    public function test_status_per_tipe_adalah_fungsi_murni(): void
    {
        $this->assertSame('menunggu', RoomMonth::statusOf('mencukupi', 0, 500, false));
        $this->assertSame('menunggu', RoomMonth::statusOf('menunaikan', 0, 0, true));
        $this->assertSame('berjalan', RoomMonth::statusOf('mencukupi', 1000, 849, false));
        $this->assertSame('perlu_perhatian', RoomMonth::statusOf('mencukupi', 1000, 850, false));
        $this->assertSame('perlu_perhatian', RoomMonth::statusOf('mencukupi', 1000, 5000, false));
        $this->assertSame('terpenuhi', RoomMonth::statusOf('mencukupi', 1000, 1000, true));
        $this->assertSame('perlu_perhatian', RoomMonth::statusOf('mencukupi', 1000, 1001, true));
        $this->assertSame('berjalan', RoomMonth::statusOf('menumbuhkan', 1000, 999, false));
        $this->assertSame('belum_tercapai', RoomMonth::statusOf('menumbuhkan', 1000, 999, true));
        $this->assertSame('terpenuhi', RoomMonth::statusOf('menunaikan', 1000, 1000, true));
        $this->assertSame('Menunggu rezeki', RoomMonth::labelFor('menunggu'));
        $this->assertSame('info', RoomMonth::toneFor('menunggu'));
    }

    public function test_progress_bp_dibulatkan_ke_bawah_dan_bisa_di_atas_100_persen(): void
    {
        $this->assertNull(RoomMonth::progressBpOf(0, 100));
        $this->assertSame(3333, RoomMonth::progressBpOf(3, 1));
        $this->assertSame(11_250, RoomMonth::progressBpOf(2_160_000, 2_430_000));
        $this->assertSame(RoomMonth::PROGRESS_CAP_BP, RoomMonth::progressBpOf(1, PHP_INT_MAX));
    }

    // --- sisa hari, aman per hari, sisa aman hari ini ---

    public function test_hari_dan_sisa_hari_untuk_bulan_berjalan_lampau_dan_depan(): void
    {
        $current = $this->ledger('2026-10-01');
        $this->assertTrue($current->isCurrentMonth());
        $this->assertSame(31, $current->daysInMonth());
        $this->assertSame(24, $current->dayOfMonth());
        $this->assertSame(8, $current->daysLeft());
        $this->assertSame(7741, $current->timeProgressBp()); // 24/31

        $past = $this->ledger('2026-09-01');
        $this->assertTrue($past->isPastMonth());
        $this->assertSame(30, $past->dayOfMonth());
        $this->assertSame(0, $past->daysLeft());
        $this->assertSame(10_000, $past->timeProgressBp());

        $future = $this->ledger('2026-11-01');
        $this->assertFalse($future->isPastMonth());
        $this->assertSame(0, $future->dayOfMonth());
        $this->assertSame(0, $future->daysLeft());
        $this->assertSame(0, $future->timeProgressBp());
    }

    public function test_hari_terakhir_bulan_menyisakan_satu_hari(): void
    {
        $this->assertSame(1, $this->ledger('2026-10-01', '2026-10-31')->daysLeft());
    }

    public function test_aman_per_hari_membagi_sisa_jatah_dengan_sisa_hari_termasuk_hari_ini(): void
    {
        $this->income(18_000_000);
        $this->spend('Keluarga', 9_396_000);
        $this->spend('Diri', 3_132_000);

        $ledger = $this->ledger();

        $this->assertSame(175_500, $ledger->room($this->keluarga)->safePerDayMinor()); // 1.404.000 / 8
        $this->assertSame(283_500, $ledger->room($this->diri)->safePerDayMinor()); // 2.268.000 / 8
        $this->assertNull($this->ledger('2026-09-01')->room($this->keluarga)->safePerDayMinor());
    }

    public function test_aman_per_hari_tidak_pernah_negatif_dan_null_tanpa_jatah(): void
    {
        $this->income(10_000_000);
        $this->spend('Keluarga', 7_000_000);

        $ledger = $this->ledger();
        $this->assertSame(0, $ledger->room($this->keluarga)->safePerDayMinor());
        $this->assertTrue($ledger->room($this->keluarga)->isOver());

        $this->assertNull($this->ledger('2026-11-01')->room($this->keluarga)->safePerDayMinor());
    }

    public function test_laju_belanja_dibanding_laju_waktu(): void
    {
        $this->income(18_000_000);
        $this->spend('Keluarga', 9_396_000); // 87% > 77%
        $this->spend('Diri', 3_132_000); // 58% < 77%

        $ledger = $this->ledger();

        $this->assertTrue($ledger->room($this->keluarga)->isAheadOfTime());
        $this->assertFalse($ledger->room($this->diri)->isAheadOfTime());
        $this->assertNull($this->ledger('2026-09-01')->room($this->diri)->isAheadOfTime());
    }

    public function test_sisa_aman_hari_ini_hanya_dari_ruang_mencukupi(): void
    {
        $this->keluarga->update(['allocation_percent' => 100]);
        $this->memberi->update(['allocation_percent' => 0]);
        $this->diri->update(['allocation_percent' => 0]);
        $this->income(800_000);
        $this->spend('Keluarga', 400_000, '2026-10-20');
        $this->spend('Keluarga', 10_000, '2026-10-24');

        $safe = $this->ledger()->safeToSpend();

        // Jatah tersisa sebelum hari ini = 800.000 - 400.000 = 400.000; dibagi 8 hari = 50.000.
        $this->assertSame(50_000, $safe['daily_budget_minor']);
        $this->assertSame(10_000, $safe['spent_today_minor']);
        $this->assertSame(8, $safe['days_left']);
        $this->assertSame(40_000, $safe['remaining_minor']);
        $this->assertFalse($safe['is_over']);
    }

    public function test_sisa_aman_hari_ini_melewati_jatah_harian_tampil_nol_tanpa_negatif(): void
    {
        $this->keluarga->update(['allocation_percent' => 100]);
        $this->memberi->update(['allocation_percent' => 0]);
        $this->diri->update(['allocation_percent' => 0]);
        $this->income(800_000);
        $this->spend('Keluarga', 400_000, '2026-10-20');
        $this->spend('Keluarga', 60_000, '2026-10-24');

        $safe = $this->ledger()->safeToSpend();

        $this->assertSame(50_000, $safe['daily_budget_minor']);
        $this->assertSame(0, $safe['remaining_minor']);
        $this->assertTrue($safe['is_over']);
    }

    public function test_sisa_aman_hari_ini_ruang_yang_lewat_jatah_dihitung_nol(): void
    {
        $this->income(10_000_000);
        $this->spend('Keluarga', 7_000_000, '2026-10-20'); // jatah 6.000.000: lewat
        // Satu-satunya ruang Mencukupi sudah lewat jatah: kolam 0.
        $safe = $this->ledger()->safeToSpend();
        $this->assertSame(0, $safe['daily_budget_minor']);
        $this->assertSame(0, $safe['remaining_minor']);
    }

    public function test_sisa_aman_hari_ini_null_bila_bukan_bulan_berjalan_atau_tanpa_jatah_mencukupi(): void
    {
        $this->income(10_000_000, '2026-09-22');
        $this->assertNull($this->ledger('2026-09-01')->safeToSpend());

        // Bulan berjalan tanpa pemasukan: Keluarga belum punya jatah.
        $this->assertNull($this->ledger('2026-10-01')->safeToSpend());

        // Hanya ruang Menunaikan/Menumbuhkan yang berjatah: bukan jatah belanja.
        $this->income(10_000_000);
        $this->keluarga->update(['type' => Room::TYPE_MENUMBUHKAN]);
        $this->assertNull($this->ledger('2026-10-01')->safeToSpend());
    }

    // --- Tak terlacak, tanggal yang sama, per hari ---

    public function test_tak_terlacak_menghitung_jumlah_dan_rupiah_dari_semua_ruang(): void
    {
        $this->income(18_000_000);
        $this->spend('Keluarga/tak', 300_000);
        $this->spend('Keluarga/tak', 275_000);
        $this->spend('Diri/tak', 289_000);
        $this->spend('Keluarga', 5_000_000);
        $this->spend('Keluarga/tak', 999_000, '2026-09-30');

        $ledger = $this->ledger();

        $this->assertSame(3, $ledger->untrackedCount());
        $this->assertSame(864_000, $ledger->untrackedMinor());
    }

    public function test_pengeluaran_sampai_tanggal_tertentu_untuk_dibandingkan_dengan_bulan_lalu(): void
    {
        $this->spend('Keluarga', 100_000, '2026-09-05');
        $this->spend('Diri', 200_000, '2026-09-24');
        $this->spend('Keluarga', 400_000, '2026-09-25');

        $september = $this->ledger('2026-09-01');

        $this->assertSame(300_000, $september->spentThroughDay(24));
        $this->assertSame(700_000, $september->spentThroughDay(31));
        $this->assertSame(700_000, $september->spentMinor);
        $this->assertSame(100_000, $september->spentByDay()[5]);
        $this->assertSame(0, $september->spentByDay()[6]);
        $this->assertSame(30, count($september->spentByDay()));
    }

    public function test_pengeluaran_ruang_per_hari_dan_hari_ini(): void
    {
        $this->spend('Keluarga', 100_000, '2026-10-24');
        $this->spend('Keluarga', 50_000, '2026-10-24');
        $this->spend('Keluarga', 70_000, '2026-10-23');
        $this->spend('Diri', 20_000, '2026-10-24');

        $keluarga = $this->ledger()->room($this->keluarga);

        $this->assertSame(150_000, $keluarga->spentTodayMinor());
        $this->assertSame(70_000, $keluarga->spentByDay()[23]);
        $this->assertSame(0, $this->ledger('2026-09-01')->room($this->keluarga)->spentTodayMinor());
    }

    // --- perlu perhatian ---

    public function test_perlu_perhatian_urut_lewat_jatah_mendekati_lalu_belum_dialirkan(): void
    {
        // Aturan 100% ke tiga ruang bertipe Mencukupi supaya tiap ruang bisa diatur sendiri.
        Room::query()->update(['type' => Room::TYPE_MENCUKUPI]);
        $this->keluarga->update(['allocation_percent' => 50]);
        $this->memberi->update(['allocation_percent' => 20]);
        $this->diri->update(['allocation_percent' => 20]);
        $this->income(10_000_000); // 5.000.000 / 2.000.000 / 2.000.000; sisa 1.000.000 belum dialirkan

        $this->spend('Memberi', 1_800_000); // 90%: mendekati
        $this->spend('Diri', 2_400_000); // 120%: lewat jatah
        $this->spend('Keluarga', 1_000_000); // 20%: aman

        $attention = $this->ledger()->attention();

        $this->assertSame(
            [MonthLedger::ATTENTION_ROOM_OVER, MonthLedger::ATTENTION_ROOM_NEAR, MonthLedger::ATTENTION_UNALLOCATED],
            array_column($attention, 'kind'),
        );
        $this->assertSame('Diri', $attention[0]['room']->name);
        $this->assertSame(12_000, $attention[0]['progress_bp']);
        $this->assertSame(2_000_000, $attention[0]['allocated_minor']);
        $this->assertSame(2_400_000, $attention[0]['spent_minor']);
        $this->assertSame('Memberi', $attention[1]['room']->name);
        $this->assertSame(9000, $attention[1]['progress_bp']);
        $this->assertSame(1_000_000, $attention[2]['amount_minor']);
    }

    public function test_ruang_menunaikan_atau_menumbuhkan_lewat_jatah_bukan_perlu_perhatian(): void
    {
        $this->income(10_000_000);
        $this->spend('Diri', 4_000_000); // jatah 3.000.000, tipe Menumbuhkan

        $this->assertSame([], $this->ledger()->attention());
    }

    public function test_perlu_perhatian_kosong_untuk_bulan_lampau(): void
    {
        $this->keluarga->update(['allocation_percent' => 40]);
        $this->income(10_000_000, '2026-09-22'); // 20% belum dialirkan

        $this->assertSame([], $this->ledger('2026-09-01')->attention());
    }

    // --- banyak bulan, jumlah kueri ---

    public function test_beberapa_bulan_sekaligus_memberi_riwayat_jatah_dan_terpakai_per_ruang(): void
    {
        $this->income(10_000_000, '2026-08-22');
        $this->income(12_000_000, '2026-09-22');
        $this->spend('Keluarga', 5_000_000, '2026-08-10');
        $this->spend('Keluarga', 6_000_000, '2026-09-10');

        $months = MonthLedger::months($this->user, CarbonImmutable::parse('2026-08-15'), CarbonImmutable::parse('2026-10-31'), $this->today);

        $this->assertSame(['2026-08', '2026-09', '2026-10'], array_keys($months));
        $this->assertSame([6_000_000, 7_200_000, 0], array_map(fn (MonthLedger $m) => $m->room($this->keluarga)->allocatedMinor, array_values($months)));
        $this->assertSame([5_000_000, 6_000_000, 0], array_map(fn (MonthLedger $m) => $m->room($this->keluarga)->spentMinor, array_values($months)));
        $this->assertSame([8333, 8333, null], array_map(fn (MonthLedger $m) => $m->room($this->keluarga)->progressBp, array_values($months)));
        $this->assertSame('2026-08-01', $months['2026-08']->month->toDateString());
    }

    public function test_bulan_akhir_sebelum_bulan_awal_ditolak(): void
    {
        $this->expectException(\InvalidArgumentException::class);

        MonthLedger::months($this->user, CarbonImmutable::parse('2026-10-01'), CarbonImmutable::parse('2026-09-01'), $this->today);
    }

    public function test_satu_bulan_penuh_cukup_dengan_tiga_kueri(): void
    {
        $this->income(18_000_000);
        $this->spend('Keluarga', 1_000_000);

        DB::enableQueryLog();
        $ledger = $this->ledger();
        foreach ($ledger->rooms() as $room) {
            $room->status();
            $room->percent();
            $room->safePerDayMinor();
        }
        $ledger->safeToSpend();
        $ledger->attention();
        $ledger->spentThroughDay(24);
        $queries = count(DB::getQueryLog());
        DB::disableQueryLog();

        $this->assertLessThanOrEqual(3, $queries);
    }

    public function test_enam_bulan_riwayat_juga_tiga_kueri(): void
    {
        $this->income(18_000_000, '2026-05-22');
        $this->spend('Keluarga', 1_000_000, '2026-07-10');

        DB::enableQueryLog();
        $months = MonthLedger::months($this->user, CarbonImmutable::parse('2026-05-01'), CarbonImmutable::parse('2026-10-01'), $this->today);
        foreach ($months as $month) {
            $month->room($this->keluarga)->progressBp;
        }
        $queries = count(DB::getQueryLog());
        DB::disableQueryLog();

        $this->assertCount(6, $months);
        $this->assertLessThanOrEqual(3, $queries);
    }
}
