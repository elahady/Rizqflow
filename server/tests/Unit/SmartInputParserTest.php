<?php

namespace Tests\Unit;

use App\Support\SmartInput\CatatContext;
use App\Support\SmartInput\CatatDraft;
use App\Support\SmartInput\CatatIssue;
use App\Support\SmartInput\HistoryHint;
use App\Support\SmartInput\ParsedEntry;
use App\Support\SmartInput\SmartAccount;
use App\Support\SmartInput\SmartCategory;
use App\Support\SmartInput\SmartField;
use App\Support\SmartInput\SmartInputContext;
use App\Support\SmartInput\SmartInputParser;
use App\Support\SmartInput\SmartRoom;
use Carbon\CarbonImmutable;
use PHPUnit\Framework\TestCase;

/**
 * Porting langsung SmartInputParserTest di :domain (domain/src/test/kotlin/.../smartinput/SmartInputTest.kt):
 * golden test parser input cerdas, satu baris contoh nyata per perilaku, kasus ujinya sama persis.
 * Hari ini dibekukan pada Kamis 24 September 2026 supaya semua tanggal relatif bisa dihitung tangan.
 * Id Kotlin ("a-tunai", "r-keluarga", ...) diganti bilangan bulat seperti id tabel web, dan
 * CatatDraft.digits diganti amountMinor. Bagian terakhir berisi kasus tambahan khusus port PHP
 * yang nilai harapannya diambil dari keluaran parser Kotlin asli.
 */
class SmartInputParserTest extends TestCase
{
    private const TUNAI = 1;

    private const BCA = 2;

    private const GOPAY = 3;

    private const OVO = 4;

    private const MEMBERI = 11;

    private const DIRI = 12;

    private const KELUARGA = 13;

    private const SEDEKAH = 21;

    private const HIBURAN = 23;

    private const BELANJA = 24;

    private const LISTRIK = 25;

    private const SEKOLAH = 26;

    private const TRANSPORT = 27;

    private const MAKAN_LUAR = 28;

    private function today(): CarbonImmutable
    {
        return CarbonImmutable::create(2026, 9, 24);
    }

    private function d(int $y, int $m, int $day): string
    {
        return CarbonImmutable::create($y, $m, $day)->toDateString();
    }

    private function tunai(): SmartAccount
    {
        return new SmartAccount(self::TUNAI, 'Tunai', 'tunai');
    }

    private function bca(): SmartAccount
    {
        return new SmartAccount(self::BCA, 'BCA Utama', 'bank');
    }

    private function gopay(): SmartAccount
    {
        return new SmartAccount(self::GOPAY, 'GoPay', 'e_wallet');
    }

    /** @return list<SmartAccount> */
    private function accounts(): array
    {
        return [$this->tunai(), $this->bca(), $this->gopay(), new SmartAccount(self::OVO, 'OVO', 'e_wallet')];
    }

    /** @return list<SmartRoom> */
    private function rooms(): array
    {
        return [
            new SmartRoom(self::MEMBERI, 'Memberi', 'menunaikan'),
            new SmartRoom(self::DIRI, 'Diri', 'menumbuhkan'),
            new SmartRoom(self::KELUARGA, 'Keluarga', 'mencukupi'),
        ];
    }

    /** @return array<int, list<SmartCategory>> */
    private function categories(): array
    {
        return [
            self::MEMBERI => [
                new SmartCategory(self::SEDEKAH, self::MEMBERI, 'Sedekah'),
                new SmartCategory(22, self::MEMBERI, 'Zakat mal', isSystem: true),
            ],
            self::DIRI => [new SmartCategory(self::HIBURAN, self::DIRI, 'Hiburan')],
            self::KELUARGA => [
                new SmartCategory(self::BELANJA, self::KELUARGA, 'Belanja bulanan'),
                new SmartCategory(self::LISTRIK, self::KELUARGA, 'Listrik dan air'),
                new SmartCategory(self::SEKOLAH, self::KELUARGA, 'Sekolah'),
                new SmartCategory(self::TRANSPORT, self::KELUARGA, 'Transportasi'),
                new SmartCategory(self::MAKAN_LUAR, self::KELUARGA, 'Makan luar'),
                new SmartCategory(29, self::KELUARGA, 'Langganan', archived: true),
            ],
        ];
    }

    /** @return list<HistoryHint> */
    private function history(): array
    {
        return [
            new HistoryHint('bakso mie ayam', self::KELUARGA, self::MAKAN_LUAR),
            new HistoryHint('nonton bioskop', self::DIRI, self::HIBURAN),
        ];
    }

    /** @param list<SmartAccount>|null $accountList */
    private function context(bool $withHistory = true, ?array $accountList = null): SmartInputContext
    {
        return new SmartInputContext($accountList ?? $this->accounts(), $this->rooms(), $this->categories(), $withHistory ? $this->history() : [], $this->today());
    }

    private function parse(string $text, bool $withHistory = true): ?ParsedEntry
    {
        return SmartInputParser::parse($text, $this->context($withHistory));
    }

    private function entry(string $text, bool $withHistory = true): ParsedEntry
    {
        $entry = $this->parse($text, $withHistory);
        $this->assertNotNull($entry, "tidak terbaca: {$text}");

        return $entry;
    }

    /** Isi understood sebagai himpunan (urutan diabaikan), seperti assertEquals(setOf(...)) di Kotlin. */
    private function assertUnderstood(array $expected, ParsedEntry $entry, string $message = ''): void
    {
        $values = fn (array $fields) => array_map(fn (SmartField $f) => $f->value, $fields);
        $actual = $values($entry->understood);
        $wanted = $values($expected);
        sort($actual);
        sort($wanted);
        $this->assertSame($wanted, $actual, $message);
    }

    // ---------------------------------------------------------------------------------------------- nominal

    public function test_berbagai_penulisan_nominal_menghasilkan_angka_yang_sama(): void
    {
        foreach (['kopi 25rb', 'kopi 25000', 'kopi 25.000', 'kopi 25k', 'kopi 25 rb', 'kopi 25 ribu', 'kopi Rp 25.000', 'kopi rp25.000', 'kopi Rp25000', 'kopi 25RB', 'kopi rp. 25.000'] as $text) {
            $e = $this->entry($text);
            $this->assertSame(25_000, $e->amountMinor, $text);
            $this->assertSame('kopi', $e->note, $text);
        }
    }

    public function test_nominal_dengan_pecahan_dan_juta(): void
    {
        $this->assertSame(1_500_000, $this->entry('servis motor 1,5jt')->amountMinor);
        $this->assertSame(1_500_000, $this->entry('servis motor 1.5jt')->amountMinor);
        $this->assertSame(1_500_000, $this->entry('servis motor 1,5 juta')->amountMinor);
        $this->assertSame(2_500, $this->entry('permen 2,5rb')->amountMinor);
        $this->assertSame(1_250_000, $this->entry('laptop 1,25jt')->amountMinor);
        $this->assertSame(500_000, $this->entry('bensin 0,5jt')->amountMinor);
        $this->assertSame(8_000_000, $this->entry('gaji 8jt')->amountMinor);
        $this->assertSame(1_250_000, $this->entry('sewa 1.250.000')->amountMinor);
    }

    public function test_nominal_kecil_tanpa_satuan_tetap_terbaca_bila_hanya_itu_angkanya(): void
    {
        $this->assertSame(500, $this->entry('parkir 500')->amountMinor);
        $this->assertSame(3_000, $this->entry('parkir 3rb')->amountMinor);
    }

    public function test_nominal_bersatuan_dipilih_mengalahkan_jumlah_barang(): void
    {
        $e = $this->entry('beli 2 kopi 50rb');
        $this->assertSame(50_000, $e->amountMinor);
        $this->assertSame('2 kopi', $e->note);
        $this->assertSame(50_000, $this->entry('beli 2 kopi 50000')->amountMinor);
        $this->assertSame('servis 2 motor', $this->entry('servis 2 motor 45rb')->note);
        $this->assertSame(45_000, $this->entry('servis 2 motor 45rb')->amountMinor);
    }

    public function test_tanpa_nominal_atau_nominal_nol_tidak_terbaca(): void
    {
        $this->assertNull($this->parse('kopi'));
        $this->assertNull($this->parse(''));
        $this->assertNull($this->parse('    '));
        $this->assertNull($this->parse('kopi 0rb'));
        $this->assertNull($this->parse('kopi 0'));
        $this->assertNull($this->parse('kopi 25,5'));
    }

    public function test_hanya_nominal_menghasilkan_pengeluaran_tanpa_catatan(): void
    {
        $e = $this->entry('25rb');
        $this->assertSame(ParsedEntry::EXPENSE, $e->kind);
        $this->assertSame('', $e->note);
        $this->assertSame(25_000, $e->amountMinor);
        $this->assertUnderstood([SmartField::Amount], $e);
    }

    public function test_nominal_terlalu_panjang_ditolak_tanpa_meledak(): void
    {
        $this->assertNull($this->parse('kopi 99999999999999999999rb'));
    }

    // ---------------------------------------------------------------------------------------------- jenis

    public function test_kata_penerimaan_dan_kata_benda_pemasukan_menghasilkan_pemasukan(): void
    {
        foreach ([
            'gaji masuk 8jt' => 'gaji',
            'terima gaji 8jt' => 'gaji',
            'cair gaji 8jt' => 'gaji',
            'dapat thr 1jt' => 'thr',
            'bonus 2jt' => 'bonus',
            'gajian 5jt' => 'gajian',
            'dikasih ibu 200rb' => 'ibu',
        ] as $text => $note) {
            $e = $this->entry($text);
            $this->assertSame(ParsedEntry::INCOME, $e->kind, $text);
            $this->assertSame($note, $e->note, $text);
            $this->assertTrue($e->has(SmartField::Kind), $text);
        }
    }

    public function test_sumber_pemasukan_dikenali_dari_kata_gaji_dan_usaha(): void
    {
        $this->assertSame('Gaji', $this->entry('gaji masuk 8jt')->source);
        $this->assertSame('Gaji', $this->entry('gajian 5jt')->source);
        $this->assertSame('Usaha', $this->entry('jualan masuk 300rb')->source);
        $this->assertSame('Usaha', $this->entry('omzet 1jt')->source);
        $this->assertNull($this->entry('dapat thr 1jt')->source);
        $this->assertTrue($this->entry('gaji masuk 8jt')->has(SmartField::Source));
        $this->assertFalse($this->entry('dapat thr 1jt')->has(SmartField::Source));
    }

    public function test_kata_kerja_pengeluaran_mengalahkan_kata_benda_pemasukan(): void
    {
        $e = $this->entry('bayar gaji karyawan 5jt');
        $this->assertSame(ParsedEntry::EXPENSE, $e->kind);
        $this->assertSame('gaji karyawan', $e->note);
    }

    public function test_masuk_sebagai_tiket_bukan_pemasukan(): void
    {
        $e = $this->entry('tiket masuk museum 50rb');
        $this->assertSame(ParsedEntry::EXPENSE, $e->kind);
        $this->assertSame('tiket masuk museum', $e->note);
        $this->assertSame(ParsedEntry::EXPENSE, $this->entry('parkir masuk mall 5rb')->kind);
    }

    public function test_uang_masuk_dari_orang_adalah_pemasukan(): void
    {
        $e = $this->entry('uang masuk 500rb dari ibu');
        $this->assertSame(ParsedEntry::INCOME, $e->kind);
        $this->assertSame('uang dari ibu', $e->note);
    }

    public function test_kata_kerja_beli_menandai_jenis_dikenali_tetapi_tanpa_kata_kunci_hanya_bawaan(): void
    {
        $this->assertTrue($this->entry('beli kopi 25rb')->has(SmartField::Kind));
        $this->assertFalse($this->entry('kopi 25rb')->has(SmartField::Kind));
        $this->assertSame('kopi', $this->entry('beli kopi 25rb')->note);
        $this->assertSame('kopi', $this->entry('jajan kopi 25rb')->note);
    }

    // ---------------------------------------------------------------------------------------------- akun

    public function test_akun_disebut_dengan_kata_depan_atau_tanpa(): void
    {
        foreach (['gojek 23rb dari gopay', 'gojek 23rb pakai gopay', 'gojek 23rb pake gopay', 'gojek 23rb via gopay', 'gojek 23rb lewat gopay', 'gojek 23rb gopay', 'gojek gopay 23rb'] as $text) {
            $e = $this->entry($text);
            $this->assertSame(ParsedEntry::EXPENSE, $e->kind, $text);
            $this->assertSame(self::GOPAY, $e->accountId, $text);
            $this->assertSame('gojek', $e->note, $text);
            $this->assertTrue($e->has(SmartField::Account), $text);
        }
    }

    public function test_nama_akun_tanpa_memandang_huruf_besar_dan_sebagian_nama_yang_unik(): void
    {
        $this->assertSame(self::GOPAY, $this->entry('gojek 23rb dari GOPAY')->accountId);
        $this->assertSame(self::BCA, $this->entry('servis 500rb dari bca')->accountId);
        $this->assertSame(self::BCA, $this->entry('servis 500rb dari bca utama')->accountId);
        $this->assertSame('servis', $this->entry('servis 500rb dari bca utama')->note);
        $this->assertSame(self::OVO, $this->entry('kopi 20rb ovo')->accountId);
    }

    public function test_kata_tunai_dan_cash_menunjuk_akun_bertipe_tunai(): void
    {
        $this->assertSame(self::TUNAI, $this->entry('servis 35rb tunai')->accountId);
        $this->assertSame(self::TUNAI, $this->entry('servis 35rb cash')->accountId);
        $this->assertSame(self::TUNAI, $this->entry('servis 35rb pakai cash')->accountId);
        $this->assertSame('servis', $this->entry('servis 35rb pakai cash')->note);
    }

    public function test_alias_tunai_tidak_dipakai_bila_tidak_ada_akun_tunai(): void
    {
        $e = SmartInputParser::parse('servis 35rb cash', $this->context(accountList: [$this->bca(), $this->gopay()]));
        $this->assertNotNull($e);
        $this->assertNull($e->accountId);
        $this->assertSame('servis cash', $e->note);
    }

    public function test_akun_yang_tidak_dikenal_tetap_bagian_catatan(): void
    {
        $e = $this->entry('servis 35rb dari mandiri');
        $this->assertNull($e->accountId);
        $this->assertSame('servis dari mandiri', $e->note);
        $this->assertFalse($e->has(SmartField::Account));
    }

    public function test_pemasukan_ke_akun_tertentu(): void
    {
        $e = $this->entry('gaji masuk 8jt ke bca');
        $this->assertSame(ParsedEntry::INCOME, $e->kind);
        $this->assertSame(self::BCA, $e->accountId);
        $this->assertSame('gaji', $e->note);
    }

    // ---------------------------------------------------------------------------------------------- transfer

    public function test_transfer_antar_dua_akun_dengan_berbagai_urutan_kata(): void
    {
        foreach (['tf bca ke gopay 100rb', 'transfer ke gopay 100rb dari bca', 'transfer dari bca ke gopay 100rb', 'transfer bca ke gopay 100rb', 'tf 100rb dari bca ke gopay'] as $text) {
            $e = $this->entry($text);
            $this->assertSame(ParsedEntry::TRANSFER, $e->kind, $text);
            $this->assertSame(self::BCA, $e->accountId, $text);
            $this->assertSame(self::GOPAY, $e->toAccountId, $text);
            $this->assertSame(100_000, $e->amountMinor, $text);
            $this->assertSame('', $e->note, $text);
            $this->assertTrue($e->has(SmartField::Account) && $e->has(SmartField::ToAccount), $text);
            $this->assertNull($e->roomId, $text);
        }
    }

    public function test_top_up_satu_akun_berarti_tujuannya(): void
    {
        $e = $this->entry('topup gopay 100rb');
        $this->assertSame(ParsedEntry::TRANSFER, $e->kind);
        $this->assertNull($e->accountId);
        $this->assertSame(self::GOPAY, $e->toAccountId);
        $this->assertFalse($e->has(SmartField::Account));
        $this->assertTrue($e->has(SmartField::ToAccount));
        $this->assertSame('', $e->note);
    }

    public function test_top_up_dua_kata_dengan_sumber(): void
    {
        $e = $this->entry('top up gopay 100rb dari bca');
        $this->assertSame(ParsedEntry::TRANSFER, $e->kind);
        $this->assertSame(self::BCA, $e->accountId);
        $this->assertSame(self::GOPAY, $e->toAccountId);
        $this->assertSame('', $e->note);
    }

    public function test_tarik_tunai_dari_bank_menuju_akun_tunai(): void
    {
        $e = $this->entry('tarik tunai 500rb dari bca');
        $this->assertSame(ParsedEntry::TRANSFER, $e->kind);
        $this->assertSame(self::BCA, $e->accountId);
        $this->assertSame(self::TUNAI, $e->toAccountId);
        $this->assertSame(500_000, $e->amountMinor);
    }

    public function test_pindah_dari_satu_akun_ke_akun_lain(): void
    {
        $e = $this->entry('pindah dari bca ke tunai 200rb');
        $this->assertSame(ParsedEntry::TRANSFER, $e->kind);
        $this->assertSame(self::BCA, $e->accountId);
        $this->assertSame(self::TUNAI, $e->toAccountId);
    }

    public function test_transfer_ke_orang_bukan_akun_tetap_pengeluaran_dengan_catatan_lengkap(): void
    {
        $e = $this->entry('transfer ke ibu 500rb');
        $this->assertSame(ParsedEntry::EXPENSE, $e->kind);
        $this->assertSame('transfer ke ibu', $e->note);
        $one = $this->entry('tf gopay 100rb');
        $this->assertSame(ParsedEntry::EXPENSE, $one->kind);
        $this->assertSame(self::GOPAY, $one->accountId);
    }

    public function test_transfer_ke_akun_yang_sama_tidak_menghasilkan_akun_tujuan(): void
    {
        $e = $this->entry('pindah dari gopay ke gopay 100rb');
        $this->assertSame(ParsedEntry::TRANSFER, $e->kind);
        $this->assertSame(self::GOPAY, $e->accountId);
        $this->assertNull($e->toAccountId);
    }

    // ---------------------------------------------------------------------------------------------- tanggal

    public function test_tanggal_relatif(): void
    {
        $cases = [
            'kopi 25rb kemarin' => $this->d(2026, 9, 23),
            'kemarin kopi 25rb' => $this->d(2026, 9, 23),
            'kopi 25rb kemaren' => $this->d(2026, 9, 23),
            'kopi 25rb kemarin lusa' => $this->d(2026, 9, 22),
            'kopi 25rb tadi' => $this->d(2026, 9, 24),
            'kopi 25rb tadi pagi' => $this->d(2026, 9, 24),
            'kopi 25rb tadi siang' => $this->d(2026, 9, 24),
            'kopi 25rb tadi malam' => $this->d(2026, 9, 23),
            'kopi 25rb semalam' => $this->d(2026, 9, 23),
            'kopi 25rb hari ini' => $this->d(2026, 9, 24),
            'kopi 25rb 3 hari lalu' => $this->d(2026, 9, 21),
            'kopi 25rb 3 hari yang lalu' => $this->d(2026, 9, 21),
            'kopi 25rb seminggu lalu' => $this->d(2026, 9, 17),
        ];
        foreach ($cases as $text => $expected) {
            $e = $this->entry($text);
            $this->assertSame($expected, $e->date->toDateString(), $text);
            $this->assertSame('kopi', $e->note, $text);
            $this->assertSame(25_000, $e->amountMinor, $text);
            $this->assertTrue($e->has(SmartField::Date), $text);
        }
    }

    public function test_tanpa_kata_tanggal_memakai_hari_ini_dan_tidak_menandai_tanggal_dikenali(): void
    {
        $e = $this->entry('kopi 25rb');
        $this->assertSame($this->today()->toDateString(), $e->date->toDateString());
        $this->assertFalse($e->has(SmartField::Date));
    }

    public function test_nama_hari_mengarah_ke_hari_itu_yang_terakhir(): void
    {
        $this->assertSame($this->d(2026, 9, 21), $this->entry('kopi 25rb hari senin')->date->toDateString());
        $this->assertSame($this->d(2026, 9, 22), $this->entry('kopi 25rb hari selasa')->date->toDateString());
        $this->assertSame($this->d(2026, 9, 24), $this->entry('kopi 25rb hari kamis')->date->toDateString());
        $this->assertSame($this->d(2026, 9, 23), $this->entry('kopi 25rb hari rabu')->date->toDateString());
        $this->assertSame($this->d(2026, 9, 19), $this->entry('kopi 25rb hari sabtu')->date->toDateString());
        $this->assertSame($this->d(2026, 9, 20), $this->entry('kopi 25rb hari minggu')->date->toDateString());
    }

    public function test_tanggal_kalender_dengan_tanggal_dan_bulan(): void
    {
        $this->assertSame($this->d(2026, 9, 20), $this->entry('kopi 25rb tgl 20')->date->toDateString());
        $this->assertSame($this->d(2026, 9, 20), $this->entry('kopi 25rb tanggal 20')->date->toDateString());
        $this->assertSame($this->d(2026, 8, 27), $this->entry('kopi 25rb tgl 27')->date->toDateString());
        $this->assertSame($this->d(2026, 9, 20), $this->entry('kopi 25rb 20/9')->date->toDateString());
        $this->assertSame($this->d(2026, 9, 20), $this->entry('kopi 25rb 20-09-2026')->date->toDateString());
        $this->assertSame($this->d(2026, 9, 20), $this->entry('kopi 25rb 20/9/26')->date->toDateString());
        $this->assertSame($this->d(2025, 12, 25), $this->entry('kopi 25rb 25/12')->date->toDateString());
        $this->assertSame('kopi', $this->entry('kopi 25rb 20/9')->note);
    }

    public function test_tanggal_yang_tidak_ada_atau_di_masa_depan_tidak_dipakai(): void
    {
        $invalid = $this->entry('kopi 25rb 31/2');
        $this->assertSame($this->today()->toDateString(), $invalid->date->toDateString());
        $this->assertSame('kopi 31/2', $invalid->note);
        $this->assertSame($this->today()->toDateString(), $this->entry('kopi 25rb 25/12/2026')->date->toDateString());
        $this->assertSame($this->today()->toDateString(), $this->entry('kopi 25rb tgl 45')->date->toDateString());
        $this->assertSame('kopi tgl 45', $this->entry('kopi 25rb tgl 45')->note);
    }

    public function test_tanggal_tidak_dianggap_nominal(): void
    {
        $e = $this->entry('kopi 20/9 25rb');
        $this->assertSame(25_000, $e->amountMinor);
        $this->assertSame($this->d(2026, 9, 20), $e->date->toDateString());
        $e2 = $this->entry('kopi tgl 20 25000');
        $this->assertSame(25_000, $e2->amountMinor);
        $this->assertSame($this->d(2026, 9, 20), $e2->date->toDateString());
        $e3 = $this->entry('kopi 3 hari lalu 25000');
        $this->assertSame(25_000, $e3->amountMinor);
        $this->assertSame($this->d(2026, 9, 21), $e3->date->toDateString());
    }

    // ---------------------------------------------------------------------------------------------- kategori

    public function test_kategori_dikenali_dari_kata_dalam_namanya(): void
    {
        foreach ([
            'bayar listrik 350rb' => [self::KELUARGA, self::LISTRIK],
            'listrik dan air 350rb' => [self::KELUARGA, self::LISTRIK],
            'bayar sekolah 500rb' => [self::KELUARGA, self::SEKOLAH],
            'belanja bulanan 500rb' => [self::KELUARGA, self::BELANJA],
            'belanja 300rb' => [self::KELUARGA, self::BELANJA],
            'transportasi 20rb' => [self::KELUARGA, self::TRANSPORT],
            'sedekah 20rb' => [self::MEMBERI, self::SEDEKAH],
            'makan luar 80rb' => [self::KELUARGA, self::MAKAN_LUAR],
        ] as $text => [$room, $category]) {
            $e = $this->entry($text, withHistory: false);
            $this->assertSame($category, $e->categoryId, $text);
            $this->assertSame($room, $e->roomId, $text);
            $this->assertTrue($e->has(SmartField::Category), $text);
        }
    }

    public function test_kategori_dipilih_yang_paling_banyak_katanya_cocok(): void
    {
        $this->assertSame(self::BELANJA, $this->entry('belanja bulanan 500rb', withHistory: false)->categoryId);
    }

    public function test_kategori_sistem_dan_yang_diarsipkan_tidak_pernah_ditebak(): void
    {
        $this->assertNull($this->entry('zakat mal 100rb', withHistory: false)->categoryId);
        $this->assertNull($this->entry('langganan 50rb', withHistory: false)->categoryId);
    }

    public function test_nama_ruang_menentukan_ruang_tanpa_kategori(): void
    {
        $e = $this->entry('jajan keluarga 50rb', withHistory: false);
        $this->assertSame(self::KELUARGA, $e->roomId);
        $this->assertNull($e->categoryId);
        $this->assertTrue($e->has(SmartField::Room));
        $this->assertFalse($e->has(SmartField::Category));
    }

    public function test_riwayat_pengeluaran_menebak_kategori_dari_kata_yang_pernah_dipakai(): void
    {
        $e = $this->entry('bakso 25rb');
        $this->assertSame(self::MAKAN_LUAR, $e->categoryId);
        $this->assertSame(self::KELUARGA, $e->roomId);
        $this->assertSame(self::DIRI, $this->entry('nonton 50rb')->roomId);
        $this->assertSame(self::HIBURAN, $this->entry('nonton 50rb')->categoryId);
    }

    public function test_tanpa_riwayat_dan_tanpa_nama_kategori_tidak_menebak(): void
    {
        $e = $this->entry('bakso 25rb', withHistory: false);
        $this->assertNull($e->roomId);
        $this->assertNull($e->categoryId);
        $this->assertTrue(! $e->has(SmartField::Category) && ! $e->has(SmartField::Room));
    }

    public function test_nama_kategori_mengalahkan_riwayat(): void
    {
        $e = SmartInputParser::parse('bakso sekolah 25rb', new SmartInputContext($this->accounts(), $this->rooms(), $this->categories(), $this->history(), $this->today()));
        $this->assertNotNull($e);
        $this->assertSame(self::SEKOLAH, $e->categoryId);
    }

    public function test_pemasukan_dan_transfer_tidak_memilih_ruang(): void
    {
        $this->assertNull($this->entry('gaji masuk sekolah 8jt')->roomId);
        $this->assertNull($this->entry('tf bca ke gopay 100rb')->categoryId);
    }

    // ---------------------------------------------------------------------------------------------- catatan

    public function test_catatan_mempertahankan_huruf_asli_dan_merapikan_spasi_dan_tanda_baca(): void
    {
        $this->assertSame('Servis Motor', $this->entry('Servis Motor 35K')->note);
        $this->assertSame(35_000, $this->entry('Servis Motor 35K')->amountMinor);
        $this->assertSame('kopi', $this->entry('  kopi   25rb  ')->note);
        $this->assertSame('kopi', $this->entry('kopi, 25rb')->note);
        $this->assertSame('kopi', $this->entry('untuk kopi 25rb')->note);
        $this->assertSame('kopi susu', $this->entry('kopi susu 25rb')->note);
    }

    public function test_contoh_riset_gojek_dari_gopay(): void
    {
        $e = $this->entry('gojek 23rb dari gopay');
        $this->assertSame(ParsedEntry::EXPENSE, $e->kind);
        $this->assertSame(23_000, $e->amountMinor);
        $this->assertSame(self::GOPAY, $e->accountId);
        $this->assertSame('gojek', $e->note);
        $this->assertUnderstood([SmartField::Amount, SmartField::Account], $e);
    }

    public function test_contoh_riset_gaji_masuk_dan_bakso(): void
    {
        $gaji = $this->entry('gaji masuk 8jt');
        $this->assertSame(ParsedEntry::INCOME, $gaji->kind);
        $this->assertSame(8_000_000, $gaji->amountMinor);
        $bakso = $this->entry('bakso 25rb');
        $this->assertSame(ParsedEntry::EXPENSE, $bakso->kind);
        $this->assertSame('bakso', $bakso->note);
    }

    // ---------------------------------------------------------------------------------------------- ke isian Catat

    private function catatContext(?int $last = self::TUNAI): CatatContext
    {
        return new CatatContext($this->accounts(), $this->rooms(), $this->categories(), lastAccountId: $last, lastRoomId: self::KELUARGA, lastCategoryId: self::BELANJA);
    }

    private function draftOf(string $text, ?int $last = self::TUNAI): CatatDraft
    {
        return SmartInputParser::toDraft($this->entry($text), $this->catatContext($last), $this->today());
    }

    public function test_pengeluaran_menjadi_isian_catat_lengkap(): void
    {
        $draft = $this->draftOf('gojek 23rb dari gopay kemarin bayar transportasi');
        $this->assertSame(ParsedEntry::EXPENSE, $draft->mode);
        $this->assertSame(23_000, $draft->amountMinor);
        $this->assertSame(self::GOPAY, $draft->accountId);
        $this->assertSame(self::KELUARGA, $draft->roomId);
        $this->assertSame(self::TRANSPORT, $draft->categoryId);
        $this->assertSame($this->d(2026, 9, 23), $draft->date->toDateString());
        $this->assertSame('gojek', $draft->note);
        $this->assertNull($draft->issue($this->catatContext()));
    }

    public function test_kolom_yang_tidak_dikenali_memakai_bawaan_catat(): void
    {
        $draft = $this->draftOf('kopi 25rb', last: self::BCA);
        $this->assertSame(self::BCA, $draft->accountId);
        $this->assertSame(self::KELUARGA, $draft->roomId);
        $this->assertSame(self::BELANJA, $draft->categoryId);
        $this->assertSame($this->today()->toDateString(), $draft->date->toDateString());
    }

    public function test_pemasukan_mengisi_sumber_dan_memakai_lainnya_bila_tidak_dikenali(): void
    {
        $gaji = $this->draftOf('gaji masuk 8jt ke bca');
        $this->assertSame(ParsedEntry::INCOME, $gaji->mode);
        $this->assertSame('Gaji', $gaji->source);
        $this->assertSame(self::BCA, $gaji->accountId);
        $this->assertSame(8_000_000, $gaji->amountMinor);
        $this->assertSame('Lainnya', $this->draftOf('dapat thr 1jt')->source);
        $this->assertNull($this->draftOf('gaji masuk 8jt')->issue($this->catatContext()));
    }

    public function test_transfer_mengisi_akun_asal_dan_tujuan(): void
    {
        $draft = $this->draftOf('tf bca ke gopay 100rb');
        $this->assertSame(ParsedEntry::TRANSFER, $draft->mode);
        $this->assertSame(self::BCA, $draft->accountId);
        $this->assertSame(self::GOPAY, $draft->toAccountId);
        $this->assertNull($draft->issue($this->catatContext()));
    }

    public function test_transfer_tanpa_tujuan_diisi_akun_lain_supaya_tetap_sah(): void
    {
        $draft = $this->draftOf('topup 100rb tarik bca');
        $this->assertSame(ParsedEntry::TRANSFER, $draft->mode);
        $this->assertNotNull($draft->toAccountId);
        $this->assertNotSame($draft->accountId, $draft->toAccountId);
        $this->assertNull($draft->issue($this->catatContext()));
    }

    public function test_catatan_panjang_dipotong_sebatas_batas_catatan(): void
    {
        $long = str_repeat('x', 300);
        $draft = SmartInputParser::toDraft($this->entry("{$long} 25rb"), $this->catatContext(), $this->today());
        $this->assertSame(200, mb_strlen($draft->note));
    }

    public function test_semua_contoh_menghasilkan_isian_yang_sah(): void
    {
        $samples = [
            'kopi 25rb', 'gojek 23rb dari gopay', 'gaji masuk 8jt', 'bakso 25rb', 'tf bca ke gopay 100rb', 'topup gopay 100rb',
            'tarik tunai 500rb dari bca', 'bayar listrik 350rb kemarin', 'dapat thr 1jt', 'transfer ke ibu 500rb', 'belanja 300rb tunai',
        ];
        foreach ($samples as $text) {
            $this->assertNull($this->draftOf($text)->issue($this->catatContext()), $text);
        }
    }

    // ---------------------------------------------------------------------------------------------- tambahan port PHP

    public function test_tebakan_dari_riwayat_ditandai_dari_catatan_serupa_dan_tebakan_nama_tidak(): void
    {
        $history = $this->entry('bakso 25rb');
        $this->assertTrue($history->fromHistory);
        $this->assertTrue($history->has(SmartField::Category));
        $this->assertFalse($this->entry('bayar listrik 350rb')->fromHistory);
        $this->assertFalse($this->entry('jajan keluarga 50rb', withHistory: false)->fromHistory);
        $this->assertFalse($this->entry('kopi 25rb')->fromHistory);
    }

    public function test_larik_polos_untuk_livewire(): void
    {
        $this->assertSame([
            'kind' => 'transfer',
            'amount_minor' => 100_000,
            'note' => '',
            'date' => '2026-09-23',
            'account_id' => self::BCA,
            'to_account_id' => self::GOPAY,
            'room_id' => null,
            'category_id' => null,
            'source' => null,
            'understood' => ['date', 'amount', 'kind', 'account', 'to_account'],
            'from_history' => false,
        ], $this->entry('tf bca ke gopay 100rb kemarin')->toArray());
        $this->assertSame('2026-09-23', $this->draftOf('kopi 25rb kemarin')->toArray()['date']);
    }

    public function test_angka_desimal_unicode_dibaca_seperti_char_is_digit_kotlin(): void
    {
        $this->assertSame($this->d(2026, 9, 20), $this->entry('tgl ٢٠ kopi 25rb')->date->toDateString());
        $this->assertSame($this->d(2026, 9, 21), $this->entry('٣ hari lalu kopi 25rb')->date->toDateString());
        // Angka di luar BMP adalah surrogate pair di Kotlin, jadi bukan Char.isDigit().
        $e = $this->entry('kopi 25rb tgl 𝟐');
        $this->assertSame($this->today()->toDateString(), $e->date->toDateString());
        $this->assertSame('kopi tgl 𝟐', $e->note);
        // Nominal hanya angka ASCII, seperti \d di regex Java.
        $this->assertNull($this->parse('kopi 𝟐𝟎rb'));
    }

    public function test_spasi_seperti_kotlin_pemisah_hanya_spasi_ascii_tetapi_ujung_dirapikan_unicode(): void
    {
        $this->assertNull($this->parse("kopi\u{00A0}25rb"));
        $e = $this->entry("\u{00A0}kopi 25rb\u{2003}");
        $this->assertSame('kopi', $e->note);
        $this->assertSame(25_000, $e->amountMinor);
    }

    public function test_pembulatan_pecahan_half_up_dan_nominal_meluap_seperti_kotlin(): void
    {
        $this->assertSame(1, $this->entry('kopi 0,0005rb')->amountMinor);
        $this->assertNull($this->parse('kopi 0,0004rb'));
        $this->assertSame(2_000, $this->entry('kopi 1,9995rb')->amountMinor);
        // Long.MAX_VALUE terlampaui: Kotlin menjadikannya negatif, sehingga mengalahkan "500" lalu tidak terbaca.
        $this->assertNull($this->parse('kopi 9999999999999jt 500'));
        $this->assertSame(25_000, $this->entry('kopi 9999999999999jt 25rb')->amountMinor);
        $this->assertNull($this->parse('kopi 0rb 500'));
    }

    public function test_top_dan_up_terpisah_nominal_tetap_transfer_tetapi_masuk_catatan(): void
    {
        $e = $this->entry('top 100rb up gopay');
        $this->assertSame(ParsedEntry::TRANSFER, $e->kind);
        $this->assertSame(self::GOPAY, $e->toAccountId);
        $this->assertSame('top up', $e->note);
    }

    public function test_emoji_tetap_di_catatan_dan_pemotongan_tidak_membelah_karakter(): void
    {
        $this->assertSame('🍜 bakso', $this->entry('🍜 bakso 25rb')->note);
        $this->assertSame(self::MAKAN_LUAR, $this->entry('🍜 bakso 25rb')->categoryId);
        // Kotlin take(200) menghitung unit UTF-16 (emoji = 2) dan bisa menyisakan separuh emoji;
        // UTF-8 tidak bisa menyimpannya, jadi emoji yang terbelah dibuang utuh.
        $draft = SmartInputParser::toDraft($this->entry(str_repeat('x', 199).'🍜 25rb'), $this->catatContext(), $this->today());
        $this->assertSame(str_repeat('x', 199), $draft->note);
        $this->assertSame(str_repeat('x', 198).'🍜', SmartInputParser::toDraft($this->entry(str_repeat('x', 198).'🍜 25rb'), $this->catatContext(), $this->today())->note);
    }

    public function test_isian_tanpa_akun_dan_tanpa_ruang_melaporkan_masalahnya(): void
    {
        $empty = new CatatContext([], [], []);
        $draft = SmartInputParser::toDraft($this->entry('kopi 25rb'), $empty, $this->today());
        $this->assertSame(CatatIssue::NoAccount, $draft->issue($empty));
        $single = new CatatContext([$this->tunai()], $this->rooms(), []);
        $this->assertSame(CatatIssue::NoCategory, SmartInputParser::toDraft($this->entry('kopi 25rb'), $single, $this->today())->issue($single));
        $this->assertSame(CatatIssue::TransferNeedsTwoAccounts, SmartInputParser::toDraft($this->entry('topup gopay 100rb'), $single, $this->today())->issue($single));
    }

    public function test_jenis_catat_di_luar_tiga_jenis_ditolak(): void
    {
        $this->expectException(\InvalidArgumentException::class);

        new CatatDraft($this->today(), mode: 'pinjaman');
    }
}
