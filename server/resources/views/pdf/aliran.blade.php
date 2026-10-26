<!doctype html>
<html lang="id">
<head>
    <meta charset="utf-8">
    <title>Aliran rezeki — Rizqflow</title>
    <style>
        /* DomPDF: CSS terbatas (tanpa flexbox/grid) — tata letak di sini pakai table biasa. */
        body { font-family: sans-serif; font-size: 12px; color: #1b1c1b; }
        h1 { font-size: 20px; margin: 0 0 4px; }
        .subtitle { color: #555; font-size: 11px; margin-bottom: 20px; }
        h2 { font-size: 14px; margin: 24px 0 8px; border-bottom: 1px solid #ccc; padding-bottom: 4px; }
        table { width: 100%; border-collapse: collapse; margin-bottom: 4px; }
        th, td { text-align: right; padding: 5px 8px; border-bottom: 1px solid #e3e2e0; }
        th:first-child, td:first-child, th.left, td.left { text-align: left; }
        th { font-size: 10px; text-transform: uppercase; color: #555; }
        .readout { margin: 8px 0; }
        .note { color: #555; font-size: 11px; margin-top: 6px; }
        .footer { margin-top: 24px; font-size: 9px; color: #888; }
    </style>
</head>
<body>
    @use('App\Support\Rupiah')
    @use('App\Support\SankeyLayout')

    @php
        $layout = $flow['layout'];
        $total = $layout['total'];
        $pct = fn (int $part) => SankeyLayout::formatTenths(SankeyLayout::percentTenths($part, $total));
    @endphp

    <h1>Aliran rezeki</h1>
    <div class="subtitle">{{ $rangeLabel }} · {{ $akunLabel }} · diunduh oleh {{ $userName }} · {{ \Illuminate\Support\Carbon::now()->translatedFormat('j F Y, H:i') }}</div>

    @if ($layout['empty'])
        <p>Belum ada rezeki yang dicatat pada {{ $rangeLabel }}.</p>
    @else
        <h2>Ringkasan</h2>
        <table>
            <tr>
                <th class="left">Rezeki</th>
                <th>Terpakai</th>
                <th>Sisa belum terpakai</th>
                <th>Belum dialirkan</th>
                <th>Melewati jatah</th>
            </tr>
            <tr>
                <td class="left">{{ Rupiah::format($total) }}</td>
                <td>{{ Rupiah::format($layout['spent']) }}</td>
                <td>{{ Rupiah::format($layout['remaining']) }}</td>
                <td>{{ Rupiah::format($layout['unallocated']['amount'] ?? 0) }}</td>
                <td>{{ Rupiah::format($layout['over']) }}</td>
            </tr>
        </table>
        <p class="readout">{{ $flow['readouts']['semua'] ?? '' }}</p>

        <h2>Sumber rezeki</h2>
        <table>
            <tr><th class="left">Sumber</th><th class="left">Rincian</th><th>Jumlah</th><th>% rezeki</th></tr>
            @foreach ($layout['sources'] as $source)
                <tr>
                    <td class="left">{{ $source['label'] }}</td>
                    <td class="left">{{ $source['detail'] ?? '' }}</td>
                    <td>{{ Rupiah::format($source['amount']) }}</td>
                    <td>{{ $pct($source['amount']) }}</td>
                </tr>
            @endforeach
        </table>

        <h2>Ruang</h2>
        <table>
            <tr><th class="left">Ruang</th><th>Jatah</th><th>Terpakai</th><th>Sisa</th><th>Melewati jatah</th><th>% rezeki</th></tr>
            @foreach ($layout['rooms'] as $room)
                <tr>
                    <td class="left">{{ $room['label'] }}</td>
                    <td>{{ Rupiah::format($room['allocated']) }}</td>
                    <td>{{ Rupiah::format($room['spent']) }}</td>
                    <td>{{ Rupiah::format($room['remaining']) }}</td>
                    <td>{{ Rupiah::format($room['over']) }}</td>
                    <td>{{ $pct($room['allocated']) }}</td>
                </tr>
            @endforeach
        </table>

        <h2>Rincian per pos</h2>
        <table>
            <tr><th class="left">Ruang</th><th class="left">Pos</th><th>Rupiah</th><th>% jatah ruang</th><th>% rezeki</th></tr>
            @foreach ($flow['rows'] as $row)
                <tr>
                    <td class="left">{{ $row['room'] }}</td>
                    <td class="left">{{ $row['pos'] }}</td>
                    <td>{{ Rupiah::format($row['amount']) }}</td>
                    <td>{{ $row['ofRoom'] }}</td>
                    <td>{{ $row['ofTotal'] }}</td>
                </tr>
            @endforeach
        </table>
        @if ($flow['overNote'])
            <p class="note">Pengeluaran yang melewati jatah ruang ({{ Rupiah::format($flow['overNote']) }}) ikut terhitung di baris pos, jadi jumlah baris bisa lebih besar dari total rezeki.</p>
        @endif

        @if (! empty($flow['insights']))
            <h2>Yang terlihat</h2>
            @foreach ($flow['insights'] as $item)
                <p>{{ $item['before'] }}@isset($item['link']){{ $item['link']['label'] }}@endisset{{ $item['after'] ?? '' }}</p>
            @endforeach
        @endif
    @endif

    <div class="footer">Diagram Sankey tidak dicetak di PDF; tabel di atas memuat angka yang sama dengan diagram di layar. Rizqflow — alat bantu, bukan nasihat keuangan.</div>
</body>
</html>
