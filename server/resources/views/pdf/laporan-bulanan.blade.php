<!doctype html>
<html lang="id">
<head>
    <meta charset="utf-8">
    <title>Laporan bulanan — Rizqflow</title>
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
        .footer { margin-top: 24px; font-size: 9px; color: #888; }
    </style>
</head>
<body>
    @use('App\Support\Rupiah')
    @use('App\Support\Reports\ReportInsightSentences')

    <h1>Laporan bulanan</h1>
    <div class="subtitle">{{ $periodLabel }} dibanding {{ $previousLabel }} · diunduh oleh {{ $userName }} · {{ \Illuminate\Support\Carbon::now()->translatedFormat('j F Y, H:i') }}</div>

    <h2>Ringkasan</h2>
    <table>
        <tr>
            <th>Pos</th>
            <th>{{ $periodLabel }}</th>
            <th>{{ $previousLabel }}</th>
            <th>Beda</th>
        </tr>
        @foreach ([['Pemasukan', $income], ['Pengeluaran', $expense], ['Sisa', $net]] as [$label, $cmp])
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

    <h2>Pengeluaran per ruang</h2>
    @if ($rooms->isEmpty())
        <p>Belum ada pengeluaran di bulan ini.</p>
    @else
        <table>
            <tr><th>Ruang</th><th>{{ $periodLabel }}</th></tr>
            @foreach ($rooms as $room)
                <tr>
                    <td>{{ $room['name'] }}</td>
                    <td>{{ Rupiah::format($room['amount_minor']) }}</td>
                </tr>
            @endforeach
        </table>
    @endif

    <h2>Kategori terbesar</h2>
    @if ($categories->isEmpty())
        <p>Belum ada pengeluaran per kategori di bulan ini.</p>
    @else
        <table>
            <tr><th>Kategori</th><th>Ruang</th><th>Jumlah</th></tr>
            @foreach ($categories as $category)
                <tr>
                    <td>{{ $category['name'] }}</td>
                    <td>{{ $category['room_name'] }}</td>
                    <td>{{ Rupiah::format($category['amount_minor']) }}</td>
                </tr>
            @endforeach
        </table>
    @endif

    <div class="footer">Transfer dan transaksi pinjaman (utang-piutang) tidak ikut terhitung. Rizqflow — alat bantu, bukan nasihat keuangan.</div>
</body>
</html>
