@php use App\Support\Rupiah; @endphp

<div style="display:flex; flex-direction:column; gap:22px; max-width:880px; margin:0 auto; width:100%;">

    <div><a href="{{ route('laporan') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Laporan</a></div>

    <h1 class="rf-serif" style="font-size:26px;">Kekayaan bersih</h1>

    @if (! $canUseReports)
        <div class="rf-card">
            <p style="font-size:14px; color:var(--rf-on-surface-variant);">Kekayaan bersih sepanjang waktu adalah fitur Pro. Upgrade untuk melihat gabungan saldo akun dan harta zakat dari hari ke hari.</p>
        </div>
    @else
        @if ($latest)
            <div class="rf-card">
                <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-bottom:4px;">Hari ini</div>
                <div style="font-weight:700; font-size:28px;">{{ Rupiah::format($latest->total_minor) }}</div>
                <div style="font-size:13px; color:var(--rf-on-surface-variant); margin-top:6px;">
                    Saldo akun {{ Rupiah::format($latest->accounts_minor) }} + harta zakat {{ Rupiah::format($latest->zakat_wealth_minor) }}
                </div>
            </div>
        @endif

        @if (! $hasHistory)
            <div class="rf-card">
                <p style="font-size:14px; color:var(--rf-on-surface-variant);">Riwayat baru mulai tercatat sejak hari ini — belum ada data historis untuk direkonstruksi mundur. Buka Denah tiap hari untuk membangun grafiknya.</p>
            </div>
        @else
            <div class="rf-card">
                <div wire:ignore style="position:relative; height:320px;">
                    <canvas id="net-worth-chart" aria-label="Grafik kekayaan bersih sepanjang waktu"></canvas>
                </div>
            </div>
        @endif

        <p style="font-size:12.5px; color:var(--rf-on-surface-variant);">Gabungan saldo semua akun (termasuk yang diarsipkan) dan harta bersih semua profil zakat aktif, dicatat satu kali tiap hari saat Denah dibuka.</p>
    @endif

</div>

@once
    @push('scripts')
        <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js"></script>
    @endpush
@endonce

@push('scripts')
    <script>
        (function () {
            let netWorthChart = null;

            function renderNetWorthChart(series) {
                const canvas = document.getElementById('net-worth-chart');
                if (!canvas || typeof Chart === 'undefined') return;

                if (netWorthChart) {
                    netWorthChart.destroy();
                }

                netWorthChart = new Chart(canvas, {
                    type: 'line',
                    data: {
                        labels: series.labels,
                        datasets: [
                            { label: 'Kekayaan bersih', data: series.total, borderColor: '#4d6359', backgroundColor: 'rgba(77,99,89,0.12)', tension: 0.25, fill: true },
                            { label: 'Saldo akun', data: series.accounts, borderColor: '#3b6fa8', borderDash: [4, 4], tension: 0.25, fill: false },
                            { label: 'Harta zakat', data: series.zakatWealth, borderColor: '#c4653a', borderDash: [4, 4], tension: 0.25, fill: false },
                        ],
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        scales: { y: { beginAtZero: true, ticks: { callback: (v) => 'Rp ' + Number(v).toLocaleString('id-ID') } } },
                        plugins: { legend: { position: 'bottom' } },
                    },
                });
            }

            window.addEventListener('net-worth-chart-data', (event) => renderNetWorthChart(event.detail.series));
        })();
    </script>
@endpush
