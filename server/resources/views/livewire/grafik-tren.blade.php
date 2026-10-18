<div style="display:flex; flex-direction:column; gap:22px; max-width:880px; margin:0 auto; width:100%;">

    <div><a href="{{ route('laporan') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Laporan</a></div>

    <h1 class="rf-serif" style="font-size:26px;">Grafik tren</h1>

    @if (! $canUseReports)
        <div class="rf-card">
            <p style="font-size:14px; color:var(--rf-on-surface-variant);">Grafik tren adalah fitur Pro. Upgrade untuk melihat pemasukan dan pengeluaran multi-bulan/tahun, per ruang dan kategori.</p>
        </div>
    @else
        <div class="rf-card" style="display:flex; gap:14px; flex-wrap:wrap; align-items:flex-end;">
            <div class="rf-field" style="margin:0; min-width:140px;">
                <label for="tren-months">Rentang</label>
                <select id="tren-months" wire:model.live="months">
                    <option value="6">6 bulan</option>
                    <option value="12">12 bulan</option>
                    <option value="24">24 bulan</option>
                </select>
            </div>
            <div class="rf-field" style="margin:0; min-width:160px;">
                <label for="tren-room">Ruang</label>
                <select id="tren-room" wire:model.live="roomId">
                    <option value="">Semua ruang</option>
                    @foreach ($rooms as $room)
                        <option value="{{ $room->id }}">{{ $room->name }}</option>
                    @endforeach
                </select>
            </div>
            @if ($roomId)
                <div class="rf-field" style="margin:0; min-width:160px;">
                    <label for="tren-category">Kategori</label>
                    <select id="tren-category" wire:model.live="categoryId">
                        <option value="">Semua kategori</option>
                        @foreach ($categories as $category)
                            <option value="{{ $category->id }}">{{ $category->name }}</option>
                        @endforeach
                    </select>
                </div>
            @endif
        </div>

        <div class="rf-card">
            <div wire:ignore style="position:relative; height:320px;">
                <canvas id="trend-chart" aria-label="Grafik tren pemasukan dan pengeluaran per bulan"></canvas>
            </div>
        </div>

        <p style="font-size:12.5px; color:var(--rf-on-surface-variant);">Pemasukan selalu total seluruh akun (web belum mengalirkan pemasukan ke ruang tertentu); filter ruang dan kategori hanya menyaring pengeluaran.</p>
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
            let trendChart = null;

            function renderTrendChart(series) {
                const canvas = document.getElementById('trend-chart');
                if (!canvas || typeof Chart === 'undefined') return;

                if (trendChart) {
                    trendChart.destroy();
                }

                trendChart = new Chart(canvas, {
                    type: 'line',
                    data: {
                        labels: series.labels,
                        datasets: [
                            { label: 'Pemasukan', data: series.income, borderColor: '#2a9a80', backgroundColor: 'rgba(42,154,128,0.12)', tension: 0.25, fill: true },
                            { label: 'Pengeluaran', data: series.expense, borderColor: '#c4653a', backgroundColor: 'rgba(196,101,58,0.12)', tension: 0.25, fill: true },
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

            window.addEventListener('trend-chart-data', (event) => renderTrendChart(event.detail.series));
        })();
    </script>
@endpush
