<?php

namespace Tests\Feature;

use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Support\SmartInput\CatatContext;
use App\Support\SmartInput\HistoryHint;
use App\Support\SmartInput\SmartAccount;
use App\Support\SmartInput\SmartCategory;
use App\Support\SmartInput\SmartInputContext;
use App\Support\SmartInput\SmartInputParser;
use App\Support\SmartInput\SmartRoom;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/**
 * SmartInputContext::forUser dan CatatContext::forUser dari database. Empat tes pertama porting
 * SmartInputServiceTest di :domain (domain/src/test/kotlin/.../smartinput/SmartInputTest.kt);
 * sisanya menjaga batas data milik pengguna, arsip, dan urutan riwayat.
 */
class SmartInputContextTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private CarbonImmutable $today;

    private Account $dompet;

    private Room $keluarga;

    private Category $sekolah;

    protected function setUp(): void
    {
        parent::setUp();

        $this->today = CarbonImmutable::create(2026, 9, 21);
        $this->user = User::factory()->create();
        $this->dompet = $this->account($this->user, 'Dompet', 'tunai');
        $this->keluarga = $this->room($this->user, 'Keluarga', 'mencukupi', 3);
        $this->pos($this->keluarga, 'Belanja bulanan');
        $this->sekolah = $this->pos($this->keluarga, 'Sekolah', ['sort_order' => 1]);
    }

    private function account(User $user, string $name, string $type, array $extra = []): Account
    {
        return Account::create(['user_id' => $user->id, 'name' => $name, 'type' => $type] + $extra);
    }

    private function room(User $user, string $name, string $type, int $sortOrder = 0, array $extra = []): Room
    {
        return Room::create(['user_id' => $user->id, 'name' => $name, 'type' => $type, 'sort_order' => $sortOrder] + $extra);
    }

    private function pos(Room $room, string $name, array $extra = []): Category
    {
        return Category::create(['room_id' => $room->id, 'name' => $name] + $extra);
    }

    private function spend(Category $category, int $amount, CarbonImmutable $on, ?string $note = null, string $type = 'pengeluaran', ?User $user = null, ?Account $account = null): Transaction
    {
        return Transaction::create([
            'user_id' => ($user ?? $this->user)->id,
            'room_id' => $type === 'pengeluaran' ? $category->room_id : null,
            'category_id' => $type === 'pengeluaran' ? $category->id : null,
            'account_id' => ($account ?? $this->dompet)->id,
            'type' => $type,
            'amount_minor' => $amount,
            'note' => $note,
            'occurred_on' => $on->toDateString(),
        ]);
    }

    private function context(): SmartInputContext
    {
        return SmartInputContext::forUser($this->user, $this->today);
    }

    /** @return list<string> */
    private function historyNotes(SmartInputContext $context): array
    {
        return array_map(fn (HistoryHint $h) => $h->note, $context->history);
    }

    // ---------------------------------------------------------------------------------------------- porting SmartInputServiceTest

    public function test_riwayat_pengeluaran_menebak_kategori_lewat_layanan(): void
    {
        $this->spend($this->sekolah, 30_000, $this->today, 'fotokopi tugas');
        $context = $this->context();

        $entry = SmartInputParser::parse('fotokopi 5rb', $context);
        $this->assertNotNull($entry);
        $draft = SmartInputParser::toDraft($entry, CatatContext::forUser($this->user, $context), $this->today);

        $this->assertSame($this->sekolah->id, $entry->categoryId);
        $this->assertTrue($entry->fromHistory);
        $this->assertSame($this->sekolah->id, $draft->categoryId);
        $this->assertSame(5_000, $draft->amountMinor);
    }

    public function test_layanan_tidak_menyimpan_apa_pun(): void
    {
        $this->spend($this->sekolah, 30_000, $this->today, 'fotokopi tugas');
        $before = Transaction::count();
        $context = $this->context();

        $entry = SmartInputParser::parse('gojek 23rb dari dompet', $context);
        $this->assertNotNull($entry);
        SmartInputParser::toDraft($entry, CatatContext::forUser($this->user, $context), $this->today);

        $this->assertSame($this->dompet->id, $entry->accountId);
        $this->assertSame($before, Transaction::count());
    }

    public function test_tanpa_nominal_layanan_mengembalikan_null(): void
    {
        $this->assertNull(SmartInputParser::parse('gojek dari dompet', $this->context()));
    }

    public function test_riwayat_hanya_pengeluaran_bercatatan_dalam_sembilan_puluh_hari(): void
    {
        $this->spend($this->sekolah, 1_000, $this->today->subDays(120), 'lama sekali');
        $this->spend($this->sekolah, 2_000, $this->today);
        $this->spend($this->sekolah, 3_000, $this->today, 'baru');

        $this->assertSame(['baru'], $this->historyNotes($this->context()));
    }

    // ---------------------------------------------------------------------------------------------- tambahan web

    public function test_batas_riwayat_sembilan_puluh_hari_inklusif_dan_tanpa_tanggal_masa_depan(): void
    {
        $this->spend($this->sekolah, 1_000, $this->today->subDays(91), 'terlalu lama');
        $this->spend($this->sekolah, 1_000, $this->today->subDays(90), 'tepat sembilan puluh');
        $this->spend($this->sekolah, 1_000, $this->today->addDay(), 'besok');
        $this->spend($this->sekolah, 1_000, $this->today, '   ');
        $this->spend($this->sekolah, 1_000, $this->today, 'gaji juga bercatatan', type: 'pemasukan');

        $this->assertSame(['tepat sembilan puluh'], $this->historyNotes($this->context()));
    }

    public function test_riwayat_terbaru_dulu_dan_yang_dicatat_belakangan_lebih_dulu_bila_tanggalnya_sama(): void
    {
        $this->spend($this->sekolah, 1_000, $this->today->subDays(3), 'tiga hari lalu');
        $this->spend($this->sekolah, 1_000, $this->today, 'hari ini pertama');
        $this->spend($this->sekolah, 1_000, $this->today->subDay(), 'kemarin');
        $this->spend($this->sekolah, 1_000, $this->today, 'hari ini kedua');

        $context = $this->context();

        $this->assertSame(['hari ini kedua', 'hari ini pertama', 'kemarin', 'tiga hari lalu'], $this->historyNotes($context));
        $this->assertEquals(new HistoryHint('hari ini kedua', $this->keluarga->id, $this->sekolah->id), $context->history[0]);
    }

    public function test_riwayat_dibatasi_jumlahnya(): void
    {
        $rows = [];
        for ($i = 0; $i < SmartInputContext::HISTORY_LIMIT + 5; $i++) {
            $rows[] = [
                'user_id' => $this->user->id, 'room_id' => $this->keluarga->id, 'category_id' => $this->sekolah->id, 'account_id' => $this->dompet->id,
                'type' => 'pengeluaran', 'amount_minor' => 1_000, 'note' => "catatan {$i}", 'occurred_on' => $this->today->toDateString(),
            ];
        }
        Transaction::insert($rows);

        $history = $this->context()->history;

        $this->assertCount(SmartInputContext::HISTORY_LIMIT, $history);
        $this->assertSame('catatan '.(SmartInputContext::HISTORY_LIMIT + 4), $history[0]->note);
    }

    public function test_hanya_data_milik_pengguna_itu(): void
    {
        $other = User::factory()->create();
        $otherAccount = $this->account($other, 'Rekening Lain', 'bank');
        $otherRoom = $this->room($other, 'Ruang Lain', 'menumbuhkan');
        $otherPos = $this->pos($otherRoom, 'Bakso orang lain');
        $this->spend($otherPos, 25_000, $this->today, 'bakso enak', user: $other, account: $otherAccount);

        $context = $this->context();

        $this->assertSame([$this->dompet->id], array_map(fn (SmartAccount $a) => $a->id, $context->accounts));
        $this->assertSame([$this->keluarga->id], array_map(fn (SmartRoom $r) => $r->id, $context->rooms));
        $this->assertSame([$this->keluarga->id], array_keys($context->categories));
        $this->assertSame([], $context->history);
        $this->assertNull(SmartInputParser::parse('bakso 25rb', $context)->categoryId);
        $this->assertNull(SmartInputParser::parse('kopi 20rb dari rekening lain', $context)->accountId);
    }

    public function test_akun_ruang_dan_pos_terarsip_tidak_ikut_dan_urutannya_mengikuti_sort_order(): void
    {
        $this->account($this->user, 'BCA Lama', 'bank', ['archived' => true]);
        $gopay = $this->account($this->user, 'GoPay', 'e_wallet', ['sort_order' => 2]);
        $bca = $this->account($this->user, 'BCA', 'bank', ['sort_order' => 1]);
        $memberi = $this->room($this->user, 'Memberi', 'menunaikan', 1);
        $diri = $this->room($this->user, 'Diri', 'menumbuhkan', 2);
        $lama = $this->room($this->user, 'Ruang Lama', 'mencukupi', 0, ['archived' => true]);
        $this->pos($lama, 'Pos ruang lama');
        $zakat = $this->pos($memberi, 'Zakat mal', ['is_system' => true]);
        $this->pos($memberi, 'Langganan', ['archived' => true]);

        $context = $this->context();

        // Dompet dan Keluarga dibuat di setUp dengan sort_order 0 dan 3.
        $this->assertSame([$this->dompet->id, $bca->id, $gopay->id], array_map(fn (SmartAccount $a) => $a->id, $context->accounts));
        $this->assertSame(['tunai', 'bank', 'e_wallet'], array_map(fn (SmartAccount $a) => $a->type, $context->accounts));
        $this->assertSame([$memberi->id, $diri->id, $this->keluarga->id], array_map(fn (SmartRoom $r) => $r->id, $context->rooms));
        $this->assertSame('menunaikan', $context->room($memberi->id)->type);
        $this->assertNull($context->room($lama->id));
        // Pos sistem tetap pilihan Catat (hanya tidak ditebak dari nama); ruang tanpa pos tetap punya entri.
        $this->assertEquals([new SmartCategory($zakat->id, $memberi->id, 'Zakat mal', isSystem: true)], $context->categoriesOf($memberi->id));
        $this->assertSame([], $context->categoriesOf($diri->id));
        $this->assertSame(['Belanja bulanan', 'Sekolah'], array_map(fn (SmartCategory $c) => $c->name, $context->categoriesOf($this->keluarga->id)));
        $this->assertNull(SmartInputParser::parse('langganan 50rb', $context)->categoryId);
        $this->assertNull(SmartInputParser::parse('zakat mal 100rb', $context)->categoryId);
    }

    public function test_riwayat_tidak_disaring_ruang_aktif_seperti_kotlin_dan_isian_tetap_sah(): void
    {
        $lama = $this->room($this->user, 'Ruang Lama', 'mencukupi', 9);
        $posLama = $this->pos($lama, 'Pos lama');
        $this->spend($posLama, 25_000, $this->today->subDays(2), 'bakso langganan');
        $lama->update(['archived' => true]);
        $context = $this->context();

        $entry = SmartInputParser::parse('bakso 25rb', $context);
        $draft = SmartInputParser::toDraft($entry, CatatContext::fromSmartInput($context), $this->today);

        // Tebakan menunjuk ruang yang sudah diarsipkan; room()/category() menolaknya, dan isian
        // Catat jatuh ke ruang aktif pertama beserta pos bawaannya, sama seperti Android.
        $this->assertSame($lama->id, $entry->roomId);
        $this->assertTrue($entry->fromHistory);
        $this->assertNull($context->room($entry->roomId));
        $this->assertNull($context->category($entry->roomId, $entry->categoryId));
        $this->assertSame($this->keluarga->id, $draft->roomId);
        $this->assertSame($this->keluarga->categories()->orderBy('sort_order')->first()->id, $draft->categoryId);
        $this->assertNull($draft->issue(CatatContext::fromSmartInput($context)));
    }

    public function test_konteks_catat_memakai_akun_transaksi_terakhir_dan_pos_pengeluaran_terakhir(): void
    {
        $bca = $this->account($this->user, 'BCA', 'bank', ['sort_order' => 1]);
        $belanja = $this->keluarga->categories()->where('name', 'Belanja bulanan')->first();
        $this->spend($this->sekolah, 10_000, $this->today, 'fotokopi');
        $this->spend($belanja, 20_000, $this->today->subDays(5), 'sayur');
        $this->spend($belanja, 5_000_000, $this->today->subDays(10), 'gaji', type: 'pemasukan', account: $bca);
        $context = $this->context();

        $catat = CatatContext::forUser($this->user, $context);

        // "Terakhir" berarti terakhir dicatat (id terbesar), bukan tanggal transaksi terbaru.
        $this->assertSame($bca->id, $catat->lastAccountId);
        $this->assertSame($this->keluarga->id, $catat->lastRoomId);
        $this->assertSame($belanja->id, $catat->lastCategoryId);

        $draft = SmartInputParser::toDraft(SmartInputParser::parse('kopi 25rb', $context), $catat, $this->today);
        $this->assertSame($bca->id, $draft->accountId);
        $this->assertSame($belanja->id, $draft->categoryId);
    }

    public function test_hari_ini_dibulatkan_ke_awal_hari(): void
    {
        $context = SmartInputContext::forUser($this->user, CarbonImmutable::create(2026, 9, 21, 23, 59, 30));

        $this->assertSame('2026-09-21 00:00:00', $context->today->toDateTimeString());
        $this->assertSame('2026-09-20', SmartInputParser::parse('kopi 25rb kemarin', $context)->date->toDateString());
    }
}
