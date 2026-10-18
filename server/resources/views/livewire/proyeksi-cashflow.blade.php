@php
    use App\Support\Rupiah;
    use Illuminate\Support\Carbon;
@endphp

<div style="display:flex; flex-direction:column; gap:22px; max-width:880px; margin:0 auto; width:100%;">

    <div><a href="{{ route('laporan') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Laporan</a></div>

    <h1 class="rf-serif" style="font-size:26px;">Proyeksi cashflow</h1>

    @if (! $canUseReports)
        <div class="rf-card">
            <p style="font-size:14px; color:var(--rf-on-surface-variant);">Proyeksi cashflow adalah fitur Pro. Upgrade untuk melihat perkiraan saldo ke depan dari transaksi berulang dan tagihan terjadwal.</p>
        </div>
    @else
        <div class="rf-card" style="display:flex; gap:14px; align-items:flex-end;">
            <div class="rf-field" style="margin:0; min-width:160px;">
                <label for="cf-days">Rentang ke depan</label>
                <select id="cf-days" wire:model.live="days">
                    <option value="30">30 hari</option>
                    <option value="60">60 hari</option>
                    <option value="90">90 hari</option>
                </select>
            </div>
            <div style="flex:1;">
                <div style="font-size:12px; color:var(--rf-on-surface-variant);">Saldo sekarang (semua akun)</div>
                <div style="font-weight:700; font-size:18px;">{{ Rupiah::format($projection['startBalance']) }}</div>
            </div>
        </div>

        <div class="rf-card">
            <div wire:ignore style="position:relative; height:280px;">
                <canvas id="cashflow-chart" aria-label="Grafik proyeksi saldo ke depan"></canvas>
            </div>
        </div>

        <div class="rf-card">
            <h2 class="rf-serif" style="font-size:18px; margin-bottom:10px;">Kemunculan ke depan</h2>
            @forelse ($projection['events'] as $event)
                <div style="display:flex; align-items:center; justify-content:space-between; gap:12px; padding:10px 0; border-top:1px solid var(--rf-outline-variant); font-size:13px;">
                    <div>
                        <span class="rf-pill neutral" style="margin-right:8px;">{{ $event['source'] === 'berulang' ? 'Berulang' : 'Tagihan' }}</span>
                        <span style="font-weight:600;">{{ $event['label'] }}</span>
                        <span style="color:var(--rf-on-surface-variant);">· {{ Carbon::parse($event['date'])->translatedFormat('j F Y') }}</span>
                    </div>
                    <div style="text-align:right;">
                        <div style="font-weight:700; {{ $event['amount_minor'] < 0 ? '' : 'color:#275c3f;' }}">{{ $event['amount_minor'] < 0 ? '−' : '+' }} {{ Rupiah::format(abs($event['amount_minor'])) }}</div>
                        <div style="font-size:11px; color:var(--rf-on-surface-variant);">saldo {{ Rupiah::format($event['balance_minor']) }}</div>
                    </div>
                </div>
            @empty
                <p style="font-size:14px; color:var(--rf-on-surface-variant);">Tidak ada transaksi berulang atau tagihan yang jatuh tempo dalam rentang ini.</p>
            @endforelse
        </div>

        <p style="font-size:12.5px; color:var(--rf-on-surface-variant);">Hanya dari transaksi berulang dan tagihan yang aktif dan bisa dicatat (akun, ruang, kategorinya belum diarsipkan); utang-piutang tidak diikutkan. Perkiraan, bukan kepastian — tergantung pencatatan yang sebenarnya.</p>
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
            let cashflowChart = null;

            function renderCashflowChart(series) {
                const canvas = document.getElementById('cashflow-chart');
                if (!canvas || typeof Chart === 'undefined') return;

                if (cashflowChart) {
                    cashflowChart.destroy();
                }

                cashflowChart = new Chart(canvas, {
                    type: 'line',
                    data: {
                        labels: series.labels,
                        datasets: [
                            { label: 'Proyeksi saldo', data: series.balance, borderColor: '#4d6359', backgroundColor: 'rgba(77,99,89,0.12)', tension: 0.15, fill: true, pointRadius: 0 },
                        ],
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        scales: { y: { ticks: { callback: (v) => 'Rp ' + Number(v).toLocaleString('id-ID') } } },
                        plugins: { legend: { display: false } },
                    },
                });
            }

            window.addEventListener('cashflow-chart-data', (event) => renderCashflowChart(event.detail.series));
        })();
    </script>
@endpush
