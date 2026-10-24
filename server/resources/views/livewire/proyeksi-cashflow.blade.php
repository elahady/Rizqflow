@use('App\Support\Period')
@use('App\Support\Rupiah')
@use('Illuminate\Support\Carbon')

<div class="rf-page">
    <x-rf.page-head :sub="Period::label(Period::fromQuery(null)).' · Pribadi'" title="Analisa">
        <x-slot:actions><x-rf.pill tone="info" icon="">Fitur Pro</x-rf.pill></x-slot:actions>
    </x-rf.page-head>

    <x-analisa.tabs active="proyeksi" />

    @if (! $canUseReports)
        <x-analisa.upsell title="Proyeksi cashflow adalah fitur Pro." text="Lihat perkiraan saldo ke depan dari transaksi berulang dan tagihan terjadwal." />
    @else
        @php
            $endBalance = $projection['dailyBalances'] ? end($projection['dailyBalances']) : $projection['startBalance'];
            $netChange = $endBalance - $projection['startBalance'];
        @endphp

        <div class="rf-row" style="gap: 8px;">
            <span class="rf-sub" id="cf-rentang">Rentang ke depan</span>
            <div class="rf-row" style="gap: 8px;" role="group" aria-labelledby="cf-rentang">
                @foreach ([30 => '30 hari', 60 => '60 hari', 90 => '90 hari'] as $value => $label)
                    <button type="button" wire:click="$set('days', {{ $value }})" @class(['rf-chip', 'on' => $days === $value]) aria-pressed="{{ $days === $value ? 'true' : 'false' }}">{{ $label }}</button>
                @endforeach
            </div>
        </div>

        <div class="rf-stats">
            <div class="rf-stat">
                <div class="l">Saldo sekarang (semua akun)</div>
                <div class="v">{{ Rupiah::format($projection['startBalance']) }}</div>
            </div>
            <div class="rf-stat">
                <div class="l">Perkiraan {{ $days }} hari lagi</div>
                <div class="v">{{ Rupiah::format($endBalance) }}</div>
                <div class="d">{{ $netChange === 0 ? 'Tidak berubah' : Rupiah::signed($netChange, $netChange < 0 ? 'pengeluaran' : 'pemasukan') }}</div>
            </div>
            <div class="rf-stat">
                <div class="l">Kemunculan terjadwal</div>
                <div class="v">{{ count($projection['events']) }}</div>
                <div class="d">Berulang dan tagihan</div>
            </div>
        </div>

        <section class="rf-card">
            <div class="rf-card-head">
                <div>
                    <h2 class="rf-h2">Perkiraan saldo ke depan</h2>
                    <p class="rf-sub" style="margin-top: 4px;">Perkiraan, bukan kepastian: tergantung pencatatan yang sebenarnya.</p>
                </div>
            </div>
            <div wire:ignore style="position: relative; height: 280px;">
                <canvas id="cashflow-chart" role="img" aria-label="Grafik proyeksi saldo {{ $days }} hari ke depan. Tabel di bawah memuat tiap kemunculan."></canvas>
            </div>
        </section>

        <section class="rf-card">
            <div class="rf-card-head"><h2 class="rf-h2">Kemunculan ke depan</h2><span class="rf-sub">{{ $days }} hari</span></div>
            @if (count($projection['events']) === 0)
                <div class="rf-empty">
                    <x-rf.icon name="calendar" />
                    <b>Tidak ada yang jatuh tempo</b>
                    <p>Tidak ada transaksi berulang atau tagihan yang jatuh tempo dalam rentang ini.</p>
                </div>
            @else
                <div class="rf-scroll">
                    <table class="rf-table" style="min-width: 560px;">
                        <thead>
                            <tr><th>Tanggal</th><th>Sumber</th><th>Keterangan</th><th class="num">Jumlah</th><th class="num">Saldo sesudahnya</th></tr>
                        </thead>
                        <tbody>
                            @foreach ($projection['events'] as $event)
                                <tr>
                                    <td>{{ Carbon::parse($event['date'])->translatedFormat('j F Y') }}</td>
                                    <td><x-rf.pill tone="neutral" icon="">{{ $event['source'] === 'berulang' ? 'Berulang' : 'Tagihan' }}</x-rf.pill></td>
                                    <td>{{ $event['label'] }}</td>
                                    <td class="num" @if ($event['amount_minor'] >= 0) style="color: var(--rf-income); font-weight: 700;" @endif>{{ Rupiah::signed($event['amount_minor'], $event['amount_minor'] < 0 ? 'pengeluaran' : 'pemasukan') }}</td>
                                    <td class="num">{{ Rupiah::format($event['balance_minor']) }}</td>
                                </tr>
                            @endforeach
                        </tbody>
                    </table>
                </div>
            @endif
        </section>

        <p class="rf-hint"><x-rf.icon name="info" />Hanya dari transaksi berulang dan tagihan yang aktif dan bisa dicatat (akun, ruang, kategorinya belum diarsipkan); utang-piutang tidak diikutkan.</p>
    @endif
</div>

@if ($canUseReports)
    @include('partials.analisa-chart')

    @push('scripts')
        <script>
            rizqflowAnalisaChart.mount({
                canvasId: 'cashflow-chart',
                event: 'cashflow-chart-data',
                build: function (series, t) {
                    return {
                        type: 'line',
                        data: {
                            labels: series.labels,
                            datasets: [
                                { label: 'Proyeksi saldo', data: series.balance, borderColor: t.primary, backgroundColor: t.alpha(t.primary, 0.14), tension: 0.15, fill: true, pointRadius: 0 },
                            ],
                        },
                        options: rizqflowAnalisaChart.baseOptions(t, { beginAtZero: false, legend: false }),
                    };
                },
            });
        </script>
    @endpush
@endif
