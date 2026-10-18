<div style="display:flex; flex-direction:column; gap:22px; max-width:720px; margin:0 auto; width:100%;">

    <div><a href="{{ route('denah') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Denah</a></div>

    <h1 class="rf-serif" style="font-size:26px;">Laporan</h1>
    <p style="font-size:13px; color:var(--rf-on-surface-variant); margin:-8px 0 0;">Analisa yang hanya ada di web, cocok untuk layar lebar dan sesi duduk-lama — mobile tetap fokus catat kilat.</p>

    @foreach ([
        ['route' => 'laporan.tren', 'title' => 'Grafik tren', 'desc' => 'Pemasukan dan pengeluaran multi-bulan/tahun, per ruang dan kategori.'],
        ['route' => 'laporan.kekayaan-bersih', 'title' => 'Kekayaan bersih', 'desc' => 'Gabungan saldo akun dan harta zakat dari hari ke hari.'],
        ['route' => 'laporan.bandingkan', 'title' => 'Bandingkan periode', 'desc' => 'Dua bulan atau tahun berdampingan, per ruang dan kategori.'],
        ['route' => 'laporan.cashflow', 'title' => 'Proyeksi cashflow', 'desc' => 'Perkiraan saldo ke depan dari transaksi berulang dan tagihan.'],
        ['route' => 'laporan.heatmap', 'title' => 'Heatmap pencatatan', 'desc' => 'Kebiasaan mencatat setahun terakhir, gaya kontribusi GitHub.'],
    ] as $card)
        <a href="{{ route($card['route']) }}" class="rf-card" style="display:flex; align-items:center; justify-content:space-between; gap:16px; color:inherit; text-decoration:none;">
            <div>
                <div style="font-weight:700; font-size:15px; margin-bottom:4px;">{{ $card['title'] }} @unless ($canUseReports) (Pro) @endunless</div>
                <div style="font-size:13px; color:var(--rf-on-surface-variant);">{{ $card['desc'] }}</div>
            </div>
            <span aria-hidden="true">→</span>
        </a>
    @endforeach

</div>
