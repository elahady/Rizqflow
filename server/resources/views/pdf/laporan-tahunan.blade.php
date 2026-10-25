<!doctype html>
<html lang="id">
<head>
    <meta charset="utf-8">
    <title>Laporan tahunan {{ $report->year }} — Rizqflow</title>
    <style>
        /* DomPDF: CSS terbatas (tanpa flexbox/grid) — tata letak di sini pakai table biasa. */
        body { font-family: sans-serif; font-size: 12px; color: #1b1c1b; }
        h1 { font-size: 20px; margin: 0 0 4px; }
        .subtitle { color: #555; font-size: 11px; margin-bottom: 20px; }
        h2 { font-size: 14px; margin: 24px 0 8px; border-bottom: 1px solid #ccc; padding-bottom: 4px; }
        table { width: 100%; border-collapse: collapse; margin-bottom: 4px; }
        th, td { text-align: right; padding: 5px 8px; border-bottom: 1px solid #e3e2e0; }
        th:first-child, td:first-child { text-align: left; }
        th { font-size: 10px; text-transform: uppercase; color: #555; }
        tr.total td { font-weight: bold; }
        tr.empty td { color: #888; }
        ul { margin: 0; padding-left: 18px; line-height: 1.5; }
        .footer { margin-top: 24px; font-size: 9px; color: #888; }
    </style>
</head>
<body>
    @use('App\Support\Rupiah')
    @use('App\Support\Reports\ReportInsightSentences')
    @use('Carbon\Carbon')

    <h1>Laporan tahunan {{ $report->year }}</h1>
    <div class="subtitle">{{ $report->year }} dibanding {{ $report->year - 1 }} · diunduh oleh {{ $userName }} · {{ \Illuminate\Support\Carbon::now()->translatedFormat('j F Y, H:i') }}</div>

    <h2>Ringkasan</h2>
    <table>
        <tr>
            <th>Pos</th>
            <th>{{ $report->year }}</th>
            <th>{{ $report->year - 1 }}</th>
            <th>Beda</th>
        </tr>
        @foreach ([['Pemasukan', $report->income], ['Pengeluaran', $report->expense], ['Sisa', $report->net()]] as [$label, $cmp])
            @php
                $delta = $cmp->delta();
                $percentBp = $cmp->percentBp();
                $change = $delta > 0 ? 'naik ' : ($delta < 0 ? 'turun ' : '');
            @endphp
            <tr>
                <td>{{ $label }}</td>
                <td>{{ Rupiah::format($cmp->current) }}</td>
                <td>{{ Rupiah::format($cmp->previous) }}</td>
                <td>{{ $change }}{{ $percentBp === null ? Rupiah::format(abs($delta)) : ReportInsightSentences::percent(abs($percentBp)) }}</td>
            </tr>
        @endforeach
    </table>

    <h2>Per bulan</h2>
    <table>
        <tr><th>Bulan</th><th>Pemasukan</th><th>Pengeluaran</th><th>Sisa</th></tr>
        @foreach ($report->months as $month)
            <tr @class(['empty' => ! $month->hasData()])>
                <td>{{ Carbon::createFromFormat('Y-m', $month->month)->translatedFormat('F') }}</td>
                <td>{{ Rupiah::format($month->income) }}</td>
                <td>{{ Rupiah::format($month->expense) }}</td>
                <td>{{ Rupiah::format($month->income - $month->expense) }}</td>
            </tr>
        @endforeach
        <tr class="total">
            <td>Total</td>
            <td>{{ Rupiah::format($report->income->current) }}</td>
            <td>{{ Rupiah::format($report->expense->current) }}</td>
            <td>{{ Rupiah::format($report->net()->current) }}</td>
        </tr>
    </table>

    <h2>Pengeluaran per ruang</h2>
    @if ($report->roomSpend === [])
        <p>Belum ada pengeluaran di ruang tahun ini.</p>
    @else
        <table>
            <tr><th>Ruang</th><th>Jumlah</th></tr>
            @foreach ($report->roomSpend as $room)
                <tr>
                    <td>{{ $room->roomName }}</td>
                    <td>{{ Rupiah::format($room->amount) }}</td>
                </tr>
            @endforeach
        </table>
    @endif

    <h2>10 kategori terbesar</h2>
    @if ($report->topCategories === [])
        <p>Belum ada pengeluaran per kategori tahun ini.</p>
    @else
        <table>
            <tr><th>Kategori</th><th>Ruang</th><th>Jumlah</th></tr>
            @foreach ($report->topCategories as $category)
                <tr>
                    <td>{{ $category->categoryName }}</td>
                    <td>{{ $category->roomName }}</td>
                    <td>{{ Rupiah::format($category->amount) }}</td>
                </tr>
            @endforeach
        </table>
    @endif

    @if ($insightSentences !== [])
        <h2>Insight</h2>
        <ul>
            @foreach ($insightSentences as $sentence)
                <li>{{ $sentence }}</li>
            @endforeach
        </ul>
    @endif

    <div class="footer">Transfer dan transaksi pinjaman (utang-piutang) tidak ikut terhitung. Rizqflow — alat bantu, bukan nasihat keuangan.</div>
</body>
</html>
