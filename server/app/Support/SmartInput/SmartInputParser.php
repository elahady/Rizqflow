<?php

namespace App\Support\SmartInput;

use Carbon\CarbonImmutable;
use Carbon\CarbonInterface;

/**
 * Input cerdas (Tahap 11, Gratis): kalimat bebas seperti "gojek 23rb dari gopay", "gaji masuk 8jt",
 * atau "tf bca ke gopay 100rb kemarin" diuraikan jadi isian Catat. Berbasis aturan, tanpa jaringan
 * dan tanpa model. Hasilnya TIDAK PERNAH tersimpan sendiri: ia hanya mengisi form Catat, tempat
 * pengguna memeriksa dan menekan Simpan.
 *
 * Porting SmartInputParser di domain/.../smartinput/SmartInput.kt dengan perilaku identik; kasus
 * ujinya juga sama (tests/Unit/SmartInputParserTest.php). Urutan langkah, kamus kata, dan aturan
 * "kata terpakai" sengaja disalin apa adanya: ubah di Kotlin lebih dulu, lalu samakan di sini.
 * Nominal tidak pernah lewat float: pecahan seperti "1,5jt" diolah sebagai digit teks dengan
 * pembulatan HALF_UP seperti BigDecimal.
 */
final class SmartInputParser
{
    /** Sumber pemasukan yang ditawarkan (INCOME_SOURCES di ledger/Catat.kt). */
    public const INCOME_SOURCES = ['Gaji', 'Usaha', 'Lainnya'];

    /** Batas panjang catatan (LedgerService.NOTE_MAX di :domain). */
    public const NOTE_MAX = 200;

    /** \s Java tanpa flag hanya mencakup spasi ASCII; \s PCRE dengan /u lebih luas, jadi ditulis eksplisit. */
    private const WHITESPACE = '/[ \t\n\x0B\f\r]+/';

    private const AMOUNT_TOKEN = '/^([0-9]+(?:[.,][0-9]+)*)(juta|jt|ribu|rb|k)?$/D';

    private const THOUSANDS = '/^[0-9]{1,3}(?:[.,][0-9]{3})+$/D';

    private const NUMERIC_DATE = '#^([0-9]{1,2})[/-]([0-9]{1,2})(?:[/-]([0-9]{2}|[0-9]{4}))?$#D';

    private const MAX_DIGITS = 13;

    private const MAX_DAYS_BACK = 60;

    private const SMALL_LIMIT = 1_000;

    private const MIN_ACCOUNT_WORD = 3;

    private const MIN_CONTENT_WORD = 3;

    /** Nama hari ke nomor hari ISO (Senin 1 sampai Minggu 7), seperti DayOfWeek. */
    private const WEEKDAYS = [
        'senin' => 1, 'selasa' => 2, 'rabu' => 3, 'kamis' => 4,
        'jumat' => 5, "jum'at" => 5, 'sabtu' => 6, 'minggu' => 7, 'ahad' => 7,
    ];

    private const UNITS = ['juta', 'jt', 'ribu', 'rb', 'k'];

    private const YESTERDAY = ['kemarin', 'kemaren', 'kmrn', 'semalam'];

    private const YESTERDAY_BEFORE = ['kemarin', 'kemaren'];

    private const DAYPARTS = ['pagi', 'siang', 'sore'];

    private const FROM_PREPOSITIONS = ['dari', 'pakai', 'pake', 'pk', 'via', 'lewat', 'dengan', 'dgn', 'memakai', 'menggunakan'];

    private const CASH_WORDS = ['tunai', 'cash', 'kas'];

    private const EXPENSE_VERBS = ['beli', 'bayar', 'byr', 'bayarin', 'jajan', 'beliin'];

    private const RECEIVE_VERBS = ['terima', 'diterima', 'dapat', 'dapet', 'cair', 'dikasih'];

    private const INCOME_VERBS = self::RECEIVE_VERBS;

    private const INCOME_NOUNS = ['gaji', 'gajian', 'bonus', 'thr', 'upah', 'honor', 'komisi', 'cashback', 'refund', 'dividen', 'pemasukan', 'income'];

    private const BUSINESS_WORDS = ['usaha', 'jualan', 'dagang', 'omzet', 'laba', 'untung', 'penjualan'];

    private const NOT_INCOME_BEFORE_MASUK = ['tiket', 'karcis', 'biaya', 'tarif', 'pintu', 'tol', 'parkir'];

    private const TRANSFER_WORDS = ['transfer', 'tf', 'trf', 'pindah', 'pindahkan', 'tarik', 'setor'];

    private const TRANSFER_TWO_ACCOUNTS = ['transfer', 'tf', 'trf'];

    private const LEADING_CONNECTORS = ['untuk', 'buat', 'utk', 'di', 'ke', 'dari', 'pakai', 'pake', 'via', 'lewat', 'dengan', 'dgn', 'dan', 'sama', 'sebesar', 'senilai', 'rp', 'sebanyak'];

    private const TRAILING_CONNECTORS = self::LEADING_CONNECTORS;

    private const STOPWORDS = ['dan', 'dari', 'untuk', 'yang', 'buat', 'utk', 'dengan', 'dgn', 'ini', 'itu', 'aku', 'saya', 'lagi', 'sama', 'beli', 'bayar', 'tadi'];

    private const FROM = 'from';

    private const TO = 'to';

    private const NONE = 'none';

    /** Uraian $text; null bila kosong atau tidak ada nominal yang bisa dibaca. */
    public static function parse(string $text, SmartInputContext $context): ?ParsedEntry
    {
        $tokens = array_map(
            fn (string $word) => new Token($word),
            preg_split(self::WHITESPACE, KotlinText::trim(mb_scrub($text, 'UTF-8')), -1, PREG_SPLIT_NO_EMPTY) ?: [],
        );
        if ($tokens === []) {
            return null;
        }
        $understood = [];

        $date = self::extractDate($tokens, $context->today);
        if ($date !== null) {
            $understood[] = SmartField::Date;
        } else {
            $date = $context->today;
        }
        $amount = self::extractAmount($tokens);
        if ($amount === null) {
            return null;
        }
        $understood[] = SmartField::Amount;

        $mentions = self::extractAccounts($tokens, $context->accounts);
        $kind = self::decideKind($tokens, count($mentions));
        if ($kind['explicit']) {
            $understood[] = SmartField::Kind;
        }
        self::consumeKindWords($tokens, $kind['kind']);

        $accountId = null;
        $toAccountId = null;
        if ($kind['kind'] === ParsedEntry::TRANSFER) {
            $fromAccount = self::firstMention($mentions, self::FROM)['account'] ?? null;
            $toAccount = self::firstMention($mentions, self::TO)['account'] ?? null;
            $loose = array_values(array_filter($mentions, fn (array $m) => $m['direction'] === self::NONE));
            // "topup gopay 100rb": satu akun tanpa arah pada kata masuk-ke berarti tujuannya.
            if ($kind['destinationFirst'] && $fromAccount === null && $toAccount === null && count($loose) === 1) {
                $toAccount = array_shift($loose)['account'];
            }
            if ($fromAccount === null && $loose !== []) {
                $fromAccount = array_shift($loose)['account'];
            }
            if ($toAccount === null && $loose !== []) {
                $toAccount = array_shift($loose)['account'];
            }
            $accountId = $fromAccount?->id;
            $toAccountId = $toAccount !== null && $toAccount->id !== $accountId ? $toAccount->id : null;
            if ($accountId !== null) {
                $understood[] = SmartField::Account;
            }
            if ($toAccountId !== null) {
                $understood[] = SmartField::ToAccount;
            }
        } else {
            $main = self::firstMention($mentions, self::FROM) ?? ($mentions[0] ?? null);
            $accountId = $main !== null ? $main['account']->id : null;
            if ($accountId !== null) {
                $understood[] = SmartField::Account;
            }
        }
        // Akun yang tidak terpakai (mis. "ke" pada bukan transfer) tetap dianggap bagian kalimat, bukan catatan.
        foreach ($mentions as $mention) {
            self::take($tokens, ...$mention['indexes']);
        }

        $source = null;
        if ($kind['kind'] === ParsedEntry::INCOME) {
            $source = self::incomeSourceOf($tokens);
            if ($source !== null) {
                $understood[] = SmartField::Source;
            }
        }

        $roomId = null;
        $categoryId = null;
        $fromHistory = false;
        if ($kind['kind'] === ParsedEntry::EXPENSE) {
            $match = self::matchCategory($tokens, $context);
            if ($match !== null) {
                ['roomId' => $roomId, 'categoryId' => $categoryId, 'fromHistory' => $fromHistory] = $match;
                $understood[] = $categoryId !== null ? SmartField::Category : SmartField::Room;
            }
        }

        return new ParsedEntry(
            kind: $kind['kind'],
            amountMinor: $amount,
            note: self::noteOf($tokens),
            date: $date,
            accountId: $accountId,
            toAccountId: $toAccountId,
            roomId: $roomId,
            categoryId: $categoryId,
            source: $source,
            understood: $understood,
            fromHistory: $fromHistory,
        );
    }

    /**
     * Menerapkan $entry ke isian Catat lewat CatatDraft::withMode(), sehingga hasilnya selalu sah
     * terhadap $context. Kolom yang tidak dikenali memakai bawaan Catat (akun, ruang, dan pos terakhir).
     */
    public static function toDraft(ParsedEntry $entry, CatatContext $context, CarbonInterface $today): CatatDraft
    {
        $draft = CatatDraft::start($context, $today, $entry->kind)
            ->withAmount($entry->amountMinor)
            ->withDate($entry->date)
            ->withNote($entry->note);
        if ($entry->accountId !== null) {
            $draft = $draft->withAccount($entry->accountId, $context);
        }
        if ($entry->toAccountId !== null) {
            $draft = $draft->withToAccount($entry->toAccountId, $context);
        }
        if ($entry->roomId !== null) {
            $draft = $draft->withRoom($entry->roomId, $context);
        }
        if ($entry->categoryId !== null) {
            $draft = $draft->withCategory($entry->categoryId);
        }
        if ($entry->source !== null) {
            $draft = $draft->withSource($entry->source);
        }
        if ($entry->kind === ParsedEntry::INCOME && $entry->source === null) {
            $draft = $draft->withSource(self::INCOME_SOURCES[count(self::INCOME_SOURCES) - 1]);
        }

        return $draft->withMode($entry->kind, $context);
    }

    // ------------------------------------------------------------------------------ token

    /**
     * Token yang belum terpakai, dengan indeks aslinya. Larik ini salinan: menandai token terpakai
     * sambil mengulanginya tidak mengubah isi perulangan, sama seperti `free()` di Kotlin.
     *
     * @param  list<Token>  $tokens
     * @return array<int, Token>
     */
    private static function free(array $tokens): array
    {
        return array_filter($tokens, fn (Token $t) => ! $t->used);
    }

    /** @param list<Token> $tokens */
    private static function take(array $tokens, int ...$indexes): void
    {
        foreach ($indexes as $i) {
            $tokens[$i]->used = true;
        }
    }

    /** Kata kecil token ke-$i bila ada dan belum terpakai. */
    private static function lowAt(array $tokens, int $i): ?string
    {
        return isset($tokens[$i]) && ! $tokens[$i]->used ? $tokens[$i]->low : null;
    }

    /** @param list<string> $words */
    private static function anyIn(array $words, array $set): bool
    {
        foreach ($words as $word) {
            if (in_array($word, $set, true)) {
                return true;
            }
        }

        return false;
    }

    // ------------------------------------------------------------------------------ tanggal

    /** Menemukan dan memakai satu penanda tanggal; tanggal di masa depan tidak pernah dihasilkan. */
    private static function extractDate(array $tokens, CarbonImmutable $today): ?CarbonImmutable
    {
        foreach (array_keys($tokens) as $i) {
            $t = self::lowAt($tokens, $i);
            if ($t === null) {
                continue;
            }
            $n1 = self::lowAt($tokens, $i + 1);
            $n2 = self::lowAt($tokens, $i + 2);
            $n3 = self::lowAt($tokens, $i + 3);

            // Satu cabang `when` di Kotlin: bila syaratnya cocok tetapi tanggalnya tidak sah, cabang
            // berikutnya tidak dicoba untuk token ini (karena itu `continue`).
            if (in_array($t, self::YESTERDAY_BEFORE, true) && $n1 === 'lusa') {
                self::take($tokens, $i, $i + 1);

                return $today->subDays(2);
            }
            if (in_array($t, self::YESTERDAY, true)) {
                self::take($tokens, $i);

                return $today->subDays(1);
            }
            if (($t === 'tadi' && $n1 === 'malam') || ($t === 'malam' && $n1 === 'tadi')) {
                self::take($tokens, $i, $i + 1);

                return $today->subDays(1);
            }
            if ($t === 'tadi' && in_array($n1, self::DAYPARTS, true)) {
                self::take($tokens, $i, $i + 1);

                return $today;
            }
            if ($t === 'tadi' || $t === 'barusan' || $t === 'td') {
                self::take($tokens, $i);

                return $today;
            }
            if ($t === 'hari' && $n1 === 'ini') {
                self::take($tokens, $i, $i + 1);

                return $today;
            }
            if ($t === 'hari' && $n1 !== null && array_key_exists($n1, self::WEEKDAYS)) {
                self::take($tokens, $i, $i + 1);

                return self::mostRecent($today, self::WEEKDAYS[$n1]);
            }
            if ($t === 'seminggu' && $n1 === 'lalu') {
                self::take($tokens, $i, $i + 1);

                return $today->subDays(7);
            }
            if (($t === 'tgl' || $t === 'tanggal') && $n1 !== null && ($day = KotlinText::smallNumber($n1)) !== null) {
                $date = self::dayOfRecentMonth($today, $day);
                if ($date !== null) {
                    self::take($tokens, $i, $i + 1);

                    return $date;
                }

                continue;
            }
            $days = KotlinText::smallNumber($t);
            if ($days !== null && ($n1 === 'hari' || $n1 === 'hr') && ($n2 === 'lalu' || ($n2 === 'yang' && $n3 === 'lalu'))) {
                if ($days >= 1 && $days <= self::MAX_DAYS_BACK) {
                    if ($n2 === 'lalu') {
                        self::take($tokens, $i, $i + 1, $i + 2);
                    } else {
                        self::take($tokens, $i, $i + 1, $i + 2, $i + 3);
                    }

                    return $today->subDays($days);
                }

                continue;
            }
            $date = self::numericDate($t, $today);
            if ($date !== null) {
                self::take($tokens, $i);

                return $date;
            }
        }

        return null;
    }

    private static function mostRecent(CarbonImmutable $today, int $isoDay): CarbonImmutable
    {
        return $today->subDays(($today->dayOfWeekIso - $isoDay + 7) % 7);
    }

    /** "tanggal 24": tanggal itu bulan ini, atau bulan lalu bila belum tiba; null bila tanggalnya tidak ada. */
    private static function dayOfRecentMonth(CarbonImmutable $today, int $day): ?CarbonImmutable
    {
        if ($day < 1 || $day > 31) {
            return null;
        }
        if (self::validDate($today->year, $today->month, $day)) {
            $thisMonth = $today->setDate($today->year, $today->month, $day);
            if ($thisMonth->lte($today)) {
                return $thisMonth;
            }
        }
        [$year, $month] = $today->month === 1 ? [$today->year - 1, 12] : [$today->year, $today->month - 1];

        return self::validDate($year, $month, $day) ? $today->setDate($year, $month, $day) : null;
    }

    /** "24/9", "24-9-2026", "24/09/26": hari lebih dulu; tanpa tahun berarti tahun ini (atau tahun lalu bila belum tiba). */
    private static function numericDate(string $token, CarbonImmutable $today): ?CarbonImmutable
    {
        if (preg_match(self::NUMERIC_DATE, $token, $match) !== 1) {
            return null;
        }
        $day = (int) $match[1];
        $month = (int) $match[2];
        $yearText = $match[3] ?? '';
        $year = match (true) {
            $yearText === '' => $today->year,
            strlen($yearText) === 2 => 2000 + (int) $yearText,
            default => (int) $yearText,
        };
        if (! self::validDate($year, $month, $day)) {
            return null;
        }
        $date = $today->setDate($year, $month, $day);
        if ($date->lte($today)) {
            return $date;
        }

        return $yearText === '' && self::validDate($year - 1, $month, $day) ? $today->setDate($year - 1, $month, $day) : null;
    }

    /** Tanggal sah menurut kalender Gregorian proleptik seperti LocalDate.of (tahun 0 pun sah). */
    private static function validDate(int $year, int $month, int $day): bool
    {
        if ($month < 1 || $month > 12 || $day < 1) {
            return false;
        }
        $leap = $year % 4 === 0 && ($year % 100 !== 0 || $year % 400 === 0);

        return $day <= [31, $leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][$month - 1];
    }

    // ------------------------------------------------------------------------------ nominal

    /** Memilih satu nominal: yang bersatuan (rb, k, jt) atau berawalan Rp lebih dulu, lalu bilangan bulat besar, lalu yang kecil. */
    private static function extractAmount(array $tokens): ?int
    {
        $best = null;
        foreach (self::free($tokens) as $i => $token) {
            $low = $token->low;
            $index = $i;
            $text = $low;
            $indexes = [$i];
            $rupiahPrefix = false;
            if ($low === 'rp' || $low === 'rp.') {
                $next = self::lowAt($tokens, $i + 1);
                if ($next === null) {
                    continue;
                }
                $text = $next;
                $indexes = [$i, $i + 1];
                $index = $i + 1;
                $rupiahPrefix = true;
            } elseif (preg_match('/^rp[0-9.]/', $low) === 1) {
                // Kotlin memakai Char.isDigit() di sini; angka non-ASCII tetap gagal di AMOUNT_TOKEN, jadi hasilnya sama.
                $text = substr($low, 2);
                if (str_starts_with($text, '.')) {
                    $text = substr($text, 1);
                }
                $rupiahPrefix = true;
            }
            if (preg_match(self::AMOUNT_TOKEN, $text, $match) !== 1) {
                continue;
            }
            $number = $match[1];
            $unit = $match[2] ?? '';
            if ($unit === '') {
                $next = self::lowAt($tokens, $index + 1);
                if ($next !== null && in_array($next, self::UNITS, true)) {
                    $unit = $next;
                    $indexes[] = $index + 1;
                }
            }
            $value = self::valueOf($number, $unit);
            if ($value === null) {
                continue;
            }
            $grouped = $unit === '' && preg_match(self::THOUSANDS, $number) === 1;
            $rank = match (true) {
                $unit !== '' || $rupiahPrefix || $grouped => 1,
                $value >= self::SMALL_LIMIT => 2,
                default => 3,
            };
            // minWithOrNull(compareBy { rank }.thenByDescending { value }): yang pertama menang bila seri.
            if ($best === null || $rank < $best['rank'] || ($rank === $best['rank'] && $value > $best['value'])) {
                $best = ['value' => $value, 'rank' => $rank, 'indexes' => $indexes];
            }
        }
        if ($best === null || $best['value'] <= 0) {
            return null;
        }
        self::take($tokens, ...$best['indexes']);

        return $best['value'];
    }

    /**
     * "25000", "25.000", "1.250.000" tanpa satuan; "23rb", "1,5jt", "2.5k" dengan satuan (pecahan
     * boleh). Null bila tidak masuk akal. Pecahan dihitung dari digit teks, dibulatkan HALF_UP
     * seperti `BigDecimal.setScale(0, RoundingMode.HALF_UP)`.
     */
    private static function valueOf(string $number, string $unit): ?int
    {
        if (strlen($number) > self::MAX_DIGITS) {
            return null;
        }
        if ($unit === '') {
            if (preg_match(self::THOUSANDS, $number) === 1) {
                return (int) str_replace(['.', ','], '', $number);
            }

            return ctype_digit($number) ? (int) $number : null;
        }
        $scale = $unit === 'juta' || $unit === 'jt' ? 6 : 3;
        $normalized = str_replace(',', '.', $number);
        if (substr_count($normalized, '.') > 1) {
            return null;
        }
        [$whole, $fraction] = array_pad(explode('.', $normalized, 2), 2, '');
        $digits = ltrim($whole.substr(str_pad($fraction, $scale, '0'), 0, $scale), '0');
        $roundUp = strlen($fraction) > $scale && $fraction[$scale] >= '5';
        // BigDecimal.toLong() di Kotlin membungkus nilai di atas Long.MAX_VALUE (mis. "9999999999999jt")
        // menjadi negatif. Yang menentukan hasil hanya tandanya: kandidat yang tidak positif tetap
        // mengalahkan kandidat berperingkat lebih rendah lalu membuat uraian null. Jadi cukup -1.
        $max = (string) PHP_INT_MAX;
        if (strlen($digits) > strlen($max) || (strlen($digits) === strlen($max) && strcmp($digits, $max) > 0)) {
            return -1;
        }
        $value = (int) $digits;
        if ($roundUp) {
            return $value === PHP_INT_MAX ? -1 : $value + 1;
        }

        return $value;
    }

    // ------------------------------------------------------------------------------ jenis

    /**
     * Urutan keputusan: transfer (kata transfer dengan cukup akun) lebih dulu; lalu kata kerja pengeluaran
     * (beli, bayar) mengalahkan kata benda pemasukan (gaji, bonus) kecuali ada kata kerja penerimaan
     * (terima, dapat, cair); lalu pemasukan; selebihnya pengeluaran.
     *
     * @return array{kind: string, explicit: bool, destinationFirst: bool}
     */
    private static function decideKind(array $tokens, int $accountMentions): array
    {
        $words = array_values(array_map(fn (Token $t) => $t->low, self::free($tokens)));
        $hasTopUp = self::anyIn($words, ['topup', 'top-up']);
        for ($i = 0; ! $hasTopUp && $i + 1 < count($words); $i++) {
            $hasTopUp = $words[$i] === 'top' && $words[$i + 1] === 'up';
        }
        $transferWord = $hasTopUp || self::anyIn($words, self::TRANSFER_WORDS);
        if ($transferWord) {
            $needsTwo = self::anyIn($words, self::TRANSFER_TWO_ACCOUNTS);
            if ($accountMentions >= 2 || (! $needsTwo && $accountMentions >= 1)) {
                return ['kind' => ParsedEntry::TRANSFER, 'explicit' => true, 'destinationFirst' => $hasTopUp];
            }
        }
        $expenseVerb = self::anyIn($words, self::EXPENSE_VERBS);
        $incomeVerb = false;
        foreach ($words as $i => $word) {
            if (in_array($word, self::INCOME_VERBS, true) || ($word === 'masuk' && ! in_array($words[$i - 1] ?? null, self::NOT_INCOME_BEFORE_MASUK, true))) {
                $incomeVerb = true;
                break;
            }
        }
        $receiveVerb = self::anyIn($words, self::RECEIVE_VERBS);
        if ($expenseVerb && ! $receiveVerb) {
            return ['kind' => ParsedEntry::EXPENSE, 'explicit' => true, 'destinationFirst' => false];
        }
        // Kata usaha ("omzet", "jualan", ...) tanpa kata kerja lain juga menandakan pemasukan, seperti "gaji" atau "bonus".
        if ($incomeVerb || self::anyIn($words, self::INCOME_NOUNS) || self::anyIn($words, self::BUSINESS_WORDS)) {
            return ['kind' => ParsedEntry::INCOME, 'explicit' => true, 'destinationFirst' => false];
        }

        return ['kind' => ParsedEntry::EXPENSE, 'explicit' => $expenseVerb, 'destinationFirst' => false];
    }

    /** Kata penunjuk jenis (beli, terima, transfer, ...) bukan bagian catatan; kata benda seperti "gaji" tetap. */
    private static function consumeKindWords(array $tokens, string $kind): void
    {
        $words = match ($kind) {
            ParsedEntry::TRANSFER => [...self::TRANSFER_WORDS, 'topup', 'top-up'],
            ParsedEntry::INCOME => [...self::INCOME_VERBS, ...self::RECEIVE_VERBS],
            default => self::EXPENSE_VERBS,
        };
        foreach (self::free($tokens) as $i => $token) {
            if (in_array($token->low, $words, true)) {
                $token->used = true;
            }
            if ($kind === ParsedEntry::TRANSFER && $token->low === 'top' && self::lowAt($tokens, $i + 1) === 'up') {
                self::take($tokens, $i, $i + 1);
            }
        }
        if ($kind === ParsedEntry::INCOME) {
            foreach (self::free($tokens) as $i => $token) {
                // Kata sebelumnya dilihat dari kalimat asli, termasuk yang sudah terpakai (seperti Kotlin).
                if ($token->low === 'masuk' && ! in_array(isset($tokens[$i - 1]) ? $tokens[$i - 1]->low : null, self::NOT_INCOME_BEFORE_MASUK, true)) {
                    $token->used = true;
                }
            }
        }
    }

    private static function incomeSourceOf(array $tokens): ?string
    {
        $words = array_map(fn (Token $t) => $t->low, $tokens);
        if (self::anyIn($words, ['gaji', 'gajian'])) {
            return self::INCOME_SOURCES[0];
        }
        if (self::anyIn($words, self::BUSINESS_WORDS)) {
            return self::INCOME_SOURCES[1];
        }

        return null;
    }

    // ------------------------------------------------------------------------------ akun

    /**
     * Kata-kata huruf kecil sebuah teks: dipisah pada semua yang bukan huruf atau angka.
     *
     * @return list<string>
     */
    private static function normalize(string $text): array
    {
        return preg_split('/[^\p{L}\p{Nd}]+/u', mb_strtolower($text, 'UTF-8'), -1, PREG_SPLIT_NO_EMPTY) ?: [];
    }

    /** @param list<array{account: SmartAccount, indexes: list<int>, direction: string}> $mentions */
    private static function firstMention(array $mentions, string $direction): ?array
    {
        foreach ($mentions as $mention) {
            if ($mention['direction'] === $direction) {
                return $mention;
            }
        }

        return null;
    }

    /**
     * Mencari akun yang disebut: nama lengkap (boleh beberapa kata) lebih dulu, lalu satu kata nama yang
     * hanya dimiliki satu akun. "tunai", "cash", dan "kas" berarti akun bertipe tunai bila tidak ada akun
     * bernama itu. Kata depan langsung sebelumnya menentukan arah. Token belum ditandai terpakai di sini.
     *
     * @param  list<Token>  $tokens
     * @param  list<SmartAccount>  $accounts
     * @return list<array{account: SmartAccount, indexes: list<int>, direction: string}> urut posisi di kalimat
     */
    private static function extractAccounts(array $tokens, array $accounts): array
    {
        $found = [];
        $taken = [];
        $words = array_map(fn (Token $t) => implode('', self::normalize($t->low)), $tokens);
        $free = function (int $i) use (&$taken, $tokens): bool {
            return ! isset($taken[$i]) && ! $tokens[$i]->used;
        };
        $known = function (SmartAccount $account) use (&$found): bool {
            foreach ($found as $mention) {
                if ($mention['account']->id === $account->id) {
                    return true;
                }
            }

            return false;
        };
        $mention = function (SmartAccount $account, int $from, int $length) use (&$found, &$taken, $tokens): void {
            $indexes = range($from, $from + $length - 1);
            $previous = $from - 1;
            $direction = self::NONE;
            if ($previous >= 0 && ! isset($taken[$previous]) && ! $tokens[$previous]->used) {
                if (in_array($tokens[$previous]->low, self::FROM_PREPOSITIONS, true)) {
                    $direction = self::FROM;
                    array_unshift($indexes, $previous);
                } elseif ($tokens[$previous]->low === 'ke') {
                    $direction = self::TO;
                    array_unshift($indexes, $previous);
                }
            }
            foreach ($indexes as $i) {
                $taken[$i] = true;
            }
            $found[] = ['account' => $account, 'indexes' => $indexes, 'direction' => $direction];
        };

        // Nama lengkap, yang terpanjang lebih dulu supaya "BCA Utama" tidak kalah dari "BCA" (usort stabil, seperti sortedByDescending).
        $names = array_map(fn (SmartAccount $a) => self::normalize($a->name), $accounts);
        $order = array_keys($accounts);
        usort($order, fn (int $a, int $b) => count($names[$b]) <=> count($names[$a]));
        foreach ($order as $position) {
            $name = $names[$position];
            $size = count($name);
            if ($size === 0) {
                continue;
            }
            for ($i = 0; $i + $size <= count($tokens); $i++) {
                $matches = true;
                for ($k = 0; $k < $size; $k++) {
                    if (! $free($i + $k) || $words[$i + $k] !== $name[$k]) {
                        $matches = false;
                        break;
                    }
                }
                if ($matches) {
                    $mention($accounts[$position], $i, $size);
                    break;
                }
            }
        }
        // Satu kata nama yang unik di antara semua akun ("bca" untuk "BCA Utama").
        $owners = [];
        foreach ($accounts as $position => $account) {
            foreach (array_unique($names[$position]) as $word) {
                $owners[$word][] = $account;
            }
        }
        foreach ($words as $i => $word) {
            if (! $free($i) || KotlinText::length($word) < self::MIN_ACCOUNT_WORD) {
                continue;
            }
            $candidates = $owners[$word] ?? [];
            if (count($candidates) !== 1 || $known($candidates[0])) {
                continue;
            }
            $mention($candidates[0], $i, 1);
        }
        // Kata tunai untuk akun bertipe tunai.
        foreach ($words as $i => $word) {
            if (! $free($i) || ! in_array($word, self::CASH_WORDS, true)) {
                continue;
            }
            $cash = null;
            foreach ($accounts as $account) {
                if ($account->type === SmartAccount::CASH) {
                    $cash = $account;
                    break;
                }
            }
            if ($cash === null || $known($cash)) {
                continue;
            }
            $mention($cash, $i, 1);
        }
        usort($found, fn (array $a, array $b) => min($a['indexes']) <=> min($b['indexes']));

        return $found;
    }

    // ------------------------------------------------------------------------------ kategori

    /**
     * Menebak ruang dan pos dari kata sisa: nama pos (bukan pos sistem) yang kata-katanya paling
     * banyak cocok; lalu nama ruang; lalu kata yang pernah dipakai di riwayat pengeluaran. Nama yang
     * seri antara dua pos dianggap tidak dikenali daripada menebak.
     *
     * @return array{roomId: int, categoryId: ?int, fromHistory: bool}|null
     */
    private static function matchCategory(array $tokens, SmartInputContext $context): ?array
    {
        // Indeks token ikut disimpan supaya kata nama pos atau ruang yang cocok bisa ditandai
        // terpakai (tidak berakhir dobel di catatan, seperti akun dan kata jenis).
        $contentTokens = [];
        foreach (self::free($tokens) as $i => $token) {
            $word = implode('', self::normalize($token->low));
            if (KotlinText::length($word) >= self::MIN_CONTENT_WORD && ! in_array($word, self::STOPWORDS, true)) {
                $contentTokens[] = [$i, $word];
            }
        }
        $content = array_values(array_unique(array_column($contentTokens, 1)));
        if ($content === []) {
            return null;
        }
        $consume = function (array $words) use ($contentTokens, $tokens): void {
            foreach ($contentTokens as [$i, $word]) {
                if (in_array($word, $words, true)) {
                    $tokens[$i]->used = true;
                }
            }
        };
        $isContent = fn (string $word) => KotlinText::length($word) >= self::MIN_CONTENT_WORD
            && ! in_array($word, self::STOPWORDS, true) && in_array($word, $content, true);

        $scored = [];
        foreach ($context->rooms as $room) {
            foreach ($context->categoriesOf($room->id) as $category) {
                if ($category->archived || $category->isSystem) {
                    continue;
                }
                $nameWords = array_values(array_unique(array_filter(
                    self::normalize($category->name),
                    fn (string $w) => KotlinText::length($w) >= self::MIN_CONTENT_WORD && ! in_array($w, self::STOPWORDS, true),
                )));
                $score = count(array_filter($nameWords, fn (string $w) => in_array($w, $content, true)));
                if ($score > 0) {
                    $scored[] = ['room' => $room, 'category' => $category, 'words' => $nameWords, 'score' => $score];
                }
            }
        }
        if ($scored !== []) {
            $top = max(array_column($scored, 'score'));
            $best = array_values(array_filter($scored, fn (array $s) => $s['score'] === $top));
            if (count($best) === 1) {
                $consume($best[0]['words']);

                return ['roomId' => $best[0]['room']->id, 'categoryId' => $best[0]['category']->id, 'fromHistory' => false];
            }
        }

        $roomHits = array_values(array_filter(
            $context->rooms,
            fn (SmartRoom $room) => array_filter(self::normalize($room->name), fn (string $w) => in_array($w, $content, true)) !== [],
        ));
        if (count($roomHits) === 1) {
            $consume(array_values(array_unique(self::normalize($roomHits[0]->name))));

            return ['roomId' => $roomHits[0]->id, 'categoryId' => null, 'fromHistory' => false];
        }

        $bestHint = null;
        $bestShared = 0;
        foreach ($context->history as $hint) {
            $shared = count(array_filter(self::normalize($hint->note), $isContent));
            if ($shared > $bestShared) {
                $bestShared = $shared;
                $bestHint = $hint;
            }
        }

        return $bestHint !== null ? ['roomId' => $bestHint->roomId, 'categoryId' => $bestHint->categoryId, 'fromHistory' => true] : null;
    }

    // ------------------------------------------------------------------------------ catatan

    /** Kata sisa menjadi catatan, dengan huruf aslinya; kata sambung di ujung dibuang. */
    private static function noteOf(array $tokens): string
    {
        $rest = [];
        foreach ($tokens as $token) {
            $word = trim($token->raw, Token::EDGE_PUNCTUATION);
            if (! $token->used && $word !== '') {
                $rest[] = $word;
            }
        }
        while ($rest !== [] && in_array(mb_strtolower($rest[0], 'UTF-8'), self::LEADING_CONNECTORS, true)) {
            array_shift($rest);
        }
        while ($rest !== [] && in_array(mb_strtolower($rest[count($rest) - 1], 'UTF-8'), self::TRAILING_CONNECTORS, true)) {
            array_pop($rest);
        }

        return implode(' ', $rest);
    }
}
