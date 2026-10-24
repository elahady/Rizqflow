@use('App\Support\Period')
@use('App\Support\Rupiah')

<div class="rf-page">
    <x-rf.page-head :sub="Period::label(Period::fromQuery(null)).' · Pribadi'" title="Analisa">
        <x-slot:actions><x-rf.pill tone="info" icon="">Fitur Pro</x-rf.pill></x-slot:actions>
    </x-rf.page-head>

    <x-analisa.tabs active="tren" />

    @if (! $canUseReports)
        <x-analisa.upsell title="Grafik tren adalah fitur Pro." text="Lihat pemasukan dan pengeluaran multi-bulan atau multi-tahun, per ruang dan kategori." />
    @else
        <div class="rf-row" style="gap: 14px; align-items: flex-end;">
            <div class="rf-field">
            <span class="rf-label" id="tren-rentang">Rentang</span>
            <div class="rf-row" style="gap: 8px;" role="group" aria-labelledby="tren-rentang">
                @foreach ([6 => '6 bulan', 12 => '12 bulan', 24 => '24 bulan'] as $value => $label)
                    <button type="button" wire:click="$set('months', {{ $value }})" @class(['rf-chip', 'on' => $months === $value]) aria-pressed="{{ $months === $value ? 'true' : 'false' }}">{{ $label }}</button>
                @endforeach
            </div>
            </div>
            <span style="flex: 1 1 0;"></span>
            <div class="rf-field an-filter">
                <label for="tren-room">Ruang</label>
                <select id="tren-room" class="rf-input" wire:model.live="roomId">
                    <option value="">Semua ruang</option>
                    @foreach ($rooms as $room)
                        <option value="{{ $room->id }}">{{ $room->name }}</option>
                    @endforeach
                </select>
            </div>
            @if ($roomId)
                <div class="rf-field an-filter">
                    <label for="tren-category">Kategori</label>
                    <select id="tren-category" class="rf-input" wire:model.live="categoryId">
                        <option value="">Semua kategori</option>
                        @foreach ($categories as $category)
                            <option value="{{ $category->id }}">{{ $category->name }}</option>
                        @endforeach
                    </select>
                </div>
            @endif
        </div>

        <section class="rf-card">
            <div class="rf-card-head">
                <div>
                    <h2 class="rf-h2">Pemasukan dan pengeluaran per bulan</h2>
                    <p class="rf-sub" style="margin-top: 4px;">{{ $months }} bulan terakhir. Pemasukan selalu total seluruh akun; filter ruang dan kategori hanya menyaring pengeluaran.</p>
                </div>
            </div>
            <div wire:ignore style="position: relative; height: 320px;">
                <canvas id="trend-chart" role="img" aria-label="Grafik tren pemasukan dan pengeluaran per bulan. Tabel di bawah memuat semua angka."></canvas>
            </div>
        </section>

        <section class="rf-card">
            <div class="rf-card-head"><h2 class="rf-h2">Rincian (tabel kembaran grafik)</h2></div>
            <div class="rf-scroll">
                <table class="rf-table" style="min-width: 480px;">
                    <thead>
                        <tr><th>Bulan</th><th class="num">Pemasukan</th><th class="num">Pengeluaran</th><th class="num">Selisih</th></tr>
                    </thead>
                    <tbody>
                        @foreach ($series['labels'] as $i => $label)
                            @php $selisih = $series['income'][$i] - $series['expense'][$i]; @endphp
                            <tr>
                                <td>{{ $label }}</td>
                                <td class="num">{{ Rupiah::format($series['income'][$i]) }}</td>
                                <td class="num">{{ Rupiah::format($series['expense'][$i]) }}</td>
                                <td class="num">{{ Rupiah::signed($selisih, $selisih < 0 ? 'pengeluaran' : ($selisih > 0 ? 'pemasukan' : '')) }}</td>
                            </tr>
                        @endforeach
                    </tbody>
                </table>
            </div>
        </section>
    @endif
</div>

@push('styles')
    <style>
        .an-filter { min-width: 150px; }
    </style>
@endpush

@if ($canUseReports)
    @include('partials.analisa-chart')

    @push('scripts')
        <script>
            rizqflowAnalisaChart.mount({
                canvasId: 'trend-chart',
                event: 'trend-chart-data',
                build: function (series, t) {
                    return {
                        type: 'line',
                        data: {
                            labels: series.labels,
                            datasets: [
                                { label: 'Pemasukan', data: series.income, borderColor: t.room3, backgroundColor: t.alpha(t.room3, 0.14), tension: 0.25, fill: true },
                                { label: 'Pengeluaran', data: series.expense, borderColor: t.room2, backgroundColor: t.alpha(t.room2, 0.14), tension: 0.25, fill: true },
                            ],
                        },
                        options: rizqflowAnalisaChart.baseOptions(t),
                    };
                },
            });
        </script>
    @endpush
@endif
