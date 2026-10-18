<?php

namespace Tests\Unit;

use App\Support\CsvTransactionImporter;
use PHPUnit\Framework\TestCase;

class CsvTransactionImporterTest extends TestCase
{
    public function test_a_negative_amount_becomes_an_expense_and_positive_becomes_income(): void
    {
        $result = CsvTransactionImporter::parse("2026-10-01,-150000,Belanja pasar\n2026-10-02,2000000,Gaji");

        $this->assertSame('pengeluaran', $result['rows'][0]['type']);
        $this->assertSame(150_000, $result['rows'][0]['amount_minor']);
        $this->assertSame('pemasukan', $result['rows'][1]['type']);
        $this->assertSame(2_000_000, $result['rows'][1]['amount_minor']);
    }

    public function test_a_header_row_is_skipped_automatically(): void
    {
        $result = CsvTransactionImporter::parse("Tanggal,Nominal,Catatan\n2026-10-01,-150000,Belanja pasar");

        $this->assertCount(1, $result['rows']);
        $this->assertSame(0, $result['skipped']);
    }

    public function test_a_file_without_a_header_still_parses_the_first_row(): void
    {
        $result = CsvTransactionImporter::parse('2026-10-01,-150000,Belanja pasar');

        $this->assertCount(1, $result['rows']);
    }

    public function test_thousand_separators_are_stripped_not_treated_as_decimals(): void
    {
        $result = CsvTransactionImporter::parse('2026-10-01,-1.500.000,Belanja');

        $this->assertSame(1_500_000, $result['rows'][0]['amount_minor']);
    }

    public function test_accepts_slash_and_dash_date_formats(): void
    {
        $result = CsvTransactionImporter::parse("01/10/2026,-10000,A\n01-10-2026,-10000,B");

        $this->assertCount(2, $result['rows']);
        $this->assertSame('2026-10-01', $result['rows'][0]['date']);
        $this->assertSame('2026-10-01', $result['rows'][1]['date']);
    }

    public function test_rows_with_an_unparseable_date_or_zero_amount_are_skipped_and_counted(): void
    {
        // Baris pertama sengaja tanggal yang sah supaya tidak dianggap header — kasus header
        // tersendiri sudah ditangani test_a_header_row_is_skipped_automatically.
        $result = CsvTransactionImporter::parse("2026-10-01,-5000,A\ntidak-sah,-10000,B\n2026-10-02,0,C");

        $this->assertCount(1, $result['rows']);
        $this->assertSame(2, $result['skipped']);
    }

    public function test_blank_lines_are_ignored_without_counting_as_skipped(): void
    {
        $result = CsvTransactionImporter::parse("2026-10-01,-10000,A\n\n2026-10-02,-5000,B\n");

        $this->assertCount(2, $result['rows']);
        $this->assertSame(0, $result['skipped']);
    }

    public function test_a_note_longer_than_200_characters_is_truncated_not_rejected(): void
    {
        $result = CsvTransactionImporter::parse('2026-10-01,-10000,'.str_repeat('x', 250));

        $this->assertSame(200, mb_strlen($result['rows'][0]['note']));
    }

    public function test_a_missing_note_column_is_null(): void
    {
        $result = CsvTransactionImporter::parse('2026-10-01,-10000');

        $this->assertNull($result['rows'][0]['note']);
    }

    public function test_parenthesized_amounts_are_treated_as_negative(): void
    {
        $result = CsvTransactionImporter::parse('2026-10-01,(50000),Potongan admin');

        $this->assertSame('pengeluaran', $result['rows'][0]['type']);
        $this->assertSame(50_000, $result['rows'][0]['amount_minor']);
    }
}
