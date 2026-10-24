<!doctype html>
<html lang="id">
<head>
    <meta charset="utf-8">
    <title>Rekap zakat {{ $year }} — Rizqflow</title>
    <style>
        /* DomPDF: CSS terbatas (tanpa flexbox/grid) — tata letak di sini pakai table biasa. */
        body { font-family: sans-serif; font-size: 12px; color: #1b1c1b; }
        h1 { font-size: 20px; margin: 0 0 4px; }
        .subtitle { color: #555; font-size: 11px; margin-bottom: 20px; }
        table { width: 100%; border-collapse: collapse; margin-bottom: 4px; }
        th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid #e3e2e0; vertical-align: top; }
        th { font-size: 10px; text-transform: uppercase; color: #555; border-bottom: 1px solid #999; }
        .num { text-align: right; white-space: nowrap; }
        .muted { color: #888; }
        tfoot td { font-weight: bold; border-top: 1px solid #999; border-bottom: 0; }
        .note { margin-top: 24px; font-size: 10px; color: #555; line-height: 1.5; }
    </style>
</head>
<body>
    <h1>Rekap zakat {{ $year }}</h1>
    <div class="subtitle">{{ $userName }} · dibuat {{ \Illuminate\Support\Carbon::now()->locale('id')->translatedFormat('j F Y, H:i') }}</div>

    <table>
        <thead>
            <tr>
                <th>Tanggal</th>
                <th>Jenis zakat</th>
                <th>Lembaga penerima</th>
                <th>No. bukti setor</th>
                <th class="num">Nominal</th>
            </tr>
        </thead>
        <tbody>
            @forelse ($transactions as $transaction)
                <tr>
                    <td>{{ $transaction->occurred_on->locale('id')->translatedFormat('j M Y') }}</td>
                    <td>{{ $transaction->category }}</td>
                    <td>{{ $transaction->recipient_institution ?: '—' }}</td>
                    <td>@if ($transaction->receipt_number){{ $transaction->receipt_number }}@else<span class="muted">Belum diisi</span>@endif</td>
                    <td class="num">{{ \App\Support\Rupiah::format($transaction->amount_minor) }}</td>
                </tr>
            @empty
                <tr><td colspan="5" class="muted">Belum ada zakat yang dicatat pada {{ $year }}.</td></tr>
            @endforelse
        </tbody>
        <tfoot>
            <tr>
                <td colspan="4">Total zakat {{ $year }}</td>
                <td class="num">{{ \App\Support\Rupiah::format($totalMinor) }}</td>
            </tr>
        </tfoot>
    </table>

    <div class="note">
        Zakat yang dibayar lewat BAZNAS atau LAZ resmi dapat menjadi pengurang penghasilan kena pajak bila bukti setornya dilampirkan ke SPT (UU 23/2011 Pasal 22). Ketentuan rinci perlu dicek ke aturan DJP terbaru atau konsultan pajak; Rizqflow bukan nasihat pajak. Bukti setor tidak disimpan di server, hanya daftar di atas. Rizqflow adalah bantuan hitung zakat, bukan fatwa.
    </div>
</body>
</html>
