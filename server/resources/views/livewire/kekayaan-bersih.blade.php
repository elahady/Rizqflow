@use('App\Support\Period')
@use('App\Support\Rupiah')

<div class="rf-page">
    <x-rf.page-head :sub="Period::label(Period::fromQuery(null)).' · Pribadi'" title="Analisa">
        <x-slot:actions><x-rf.pill tone="info" icon="">Fitur Pro</x-rf.pill></x-slot:actions>
    </x-rf.page-head>

    <x-analisa.tabs active="kekayaan" />

    @if (! $canUseReports)
        <x-analisa.upsell title="Kekayaan bersih sepanjang waktu adalah fitur Pro." text="Lihat gabungan saldo akun dan harta zakat dari hari ke hari." />
    @else
        @if ($latest)
            <div class="rf-stats">
                <div class="rf-stat">
                    <div class="l">Kekayaan bersih hari ini</div>
                    <div class="v">{{ Rupiah::format($latest->total_minor) }}</div>
                    <div class="d">{{ $latest->snapshot_date->translatedFormat('j F Y') }}</div>
                </div>
                <div class="rf-stat">
                    <div class="l">Saldo akun</div>
                    <div class="v">{{ Rupiah::format($latest->accounts_minor) }}</div>
                    <div class="d">Semua akun, termasuk yang diarsipkan</div>
                </div>
                <div class="rf-stat">
                    <div class="l">Harta zakat</div>
                    <div class="v">{{ Rupiah::format($latest->zakat_wealth_minor) }}</div>
                    <div class="d">Harta bersih profil zakat aktif</div>
                </div>
            </div>
        @endif

        <section class="rf-card">
            <div class="rf-card-head">
                <div>
                    <h2 class="rf-h2">Kekayaan bersih sepanjang waktu</h2>
                    <p class="rf-sub" style="margin-top: 4px;">Dicatat satu kali tiap hari saat Denah dibuka.</p>
                </div>
            </div>
            @if (! $hasHistory)
                <div class="rf-empty">
                    <x-rf.icon name="chart" />
                    <b>Riwayat baru mulai tercatat sejak hari ini</b>
                    <p>Belum ada data historis untuk direkonstruksi mundur. Buka Denah tiap hari untuk membangun grafiknya.</p>
                </div>
            @else
                <div wire:ignore style="position: relative; height: 320px;">
                    <canvas id="net-worth-chart" role="img" aria-label="Grafik kekayaan bersih sepanjang waktu: total, saldo akun, dan harta zakat."></canvas>
                </div>
            @endif
        </section>

        <p class="rf-hint"><x-rf.icon name="info" />Gabungan saldo semua akun (termasuk yang diarsipkan) dan harta bersih semua profil zakat aktif.</p>
    @endif
</div>

@if ($canUseReports)
    @include('partials.analisa-chart')

    @push('scripts')
        <script>
            rizqflowAnalisaChart.mount({
                canvasId: 'net-worth-chart',
                event: 'net-worth-chart-data',
                build: function (series, t) {
                    return {
                        type: 'line',
                        data: {
                            labels: series.labels,
                            datasets: [
                                { label: 'Kekayaan bersih', data: series.total, borderColor: t.primary, backgroundColor: t.alpha(t.primary, 0.14), tension: 0.25, fill: true },
                                { label: 'Saldo akun', data: series.accounts, borderColor: t.room1, borderDash: [4, 4], tension: 0.25, fill: false },
                                { label: 'Harta zakat', data: series.zakatWealth, borderColor: t.room2, borderDash: [4, 4], tension: 0.25, fill: false },
                            ],
                        },
                        options: rizqflowAnalisaChart.baseOptions(t),
                    };
                },
            });
        </script>
    @endpush
@endif
