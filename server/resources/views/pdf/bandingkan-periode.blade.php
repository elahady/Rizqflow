<!doctype html>
<html lang="id">
<head>
    <meta charset="utf-8">
    <title>Bandingkan periode — Rizqflow</title>
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
        .positive { color: #275c3f; }
        .footer { margin-top: 24px; font-size: 9px; color: #888; }
    </style>
</head>
<body>
    <h1>Bandingkan periode</h1>
    <div class="subtitle">{{ $periodLabelA }} vs {{ $periodLabelB }} · diunduh oleh {{ $userName }} · {{ \Illuminate\Support\Carbon::now()->translatedFormat('j F Y, H:i') }}</div>

    <h2>Ringkasan</h2>
    <table>
        <tr>
            <th>Pos</th>
            <th>{{ $periodLabelA }}</th>
            <th>{{ $periodLabelB }}</th>
        </tr>
        @foreach ([['Pemasukan', $a['income'], $b['income']], ['Pengeluaran', $a['expense'], $b['expense']], ['Sisa', $a['remainder'], $b['remainder']]] as [$label, $va, $vb])
            <tr>
                <td>{{ $label }}</td>
                <td>{{ \App\Support\Rupiah::format($va) }}</td>
                <td>{{ \App\Support\Rupiah::format($vb) }}</td>
            </tr>
        @endforeach
    </table>

    <h2>Pengeluaran per ruang</h2>
    @if ($rooms->isEmpty())
        <p>Belum ada pengeluaran di kedua periode.</p>
    @else
        <table>
            <tr><th>Ruang</th><th>{{ $periodLabelA }}</th><th>{{ $periodLabelB }}</th></tr>
            @foreach ($rooms as $room)
                <tr>
                    <td>{{ $room['name'] }}</td>
                    <td>{{ \App\Support\Rupiah::format($room['a']) }}</td>
                    <td>{{ \App\Support\Rupiah::format($room['b']) }}</td>
                </tr>
            @endforeach
        </table>
    @endif

    <h2>Kategori terbesar</h2>
    @if ($categories->isEmpty())
        <p>Belum ada pengeluaran di kedua periode.</p>
    @else
        <table>
            <tr><th>Kategori</th><th>{{ $periodLabelA }}</th><th>{{ $periodLabelB }}</th></tr>
            @foreach ($categories as $category)
                <tr>
                    <td>{{ $category['name'] }}</td>
                    <td>{{ \App\Support\Rupiah::format($category['a']) }}</td>
                    <td>{{ \App\Support\Rupiah::format($category['b']) }}</td>
                </tr>
            @endforeach
        </table>
    @endif

    <div class="footer">Transfer dan transaksi pinjaman (utang-piutang) tidak ikut terhitung. Rizqflow — alat bantu, bukan nasihat keuangan.</div>
</body>
</html>
