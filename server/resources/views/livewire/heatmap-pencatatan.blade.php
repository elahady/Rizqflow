@use('App\Support\Period')
@use('Illuminate\Support\Carbon')

<div class="rf-page">
    <x-rf.page-head :sub="Period::label(Period::fromQuery(null)).' · Pribadi'" title="Analisa">
        <x-slot:actions><x-rf.pill tone="info" icon="">Fitur Pro</x-rf.pill></x-slot:actions>
    </x-rf.page-head>

    <x-analisa.tabs active="kebiasaan" />

    @if (! $canUseReports)
        <x-analisa.upsell title="Heatmap pencatatan adalah fitur Pro." text="Lihat kebiasaan mencatat setahun terakhir dalam kalender, gaya kontribusi GitHub." />
    @else
        <div class="rf-stats">
            <div class="rf-stat">
                <div class="l">Hari dengan catatan</div>
                <div class="v">{{ $grid['totalDays'] }}</div>
                <div class="d">dari setahun terakhir</div>
            </div>
            <div class="rf-stat">
                <div class="l">Beruntun sampai hari ini</div>
                <div class="v">{{ $grid['streak'] }} <span class="rf-sub" style="font-weight: 400;">hari</span></div>
                <div class="d">{{ $grid['streak'] > 0 ? 'Hari ini sudah ada catatan' : 'Belum ada catatan hari ini' }}</div>
            </div>
        </div>

        <section class="rf-card">
            <div class="rf-card-head">
                <div>
                    <h2 class="rf-h2">Kalender pencatatan</h2>
                    <p class="rf-sub" style="margin-top: 4px;">Satu kotak satu hari. Makin pekat, makin banyak transaksi dicatat hari itu (jenis apa pun). Arahkan kursor ke kotak untuk melihat jumlahnya.</p>
                </div>
            </div>

            <div class="rf-scroll">
                <div class="an-heat" role="img" aria-label="Kalender pencatatan setahun terakhir: {{ $grid['totalDays'] }} hari dengan catatan, beruntun {{ $grid['streak'] }} hari sampai hari ini.">
                    <div class="an-heat-months">
                        @foreach ($grid['weeks'] as $weekIndex => $week)
                            @php $label = collect($grid['monthLabels'])->firstWhere('index', $weekIndex); @endphp
                            <div class="an-heat-month">{{ $label['label'] ?? '' }}</div>
                        @endforeach
                    </div>
                    <div class="an-heat-body">
                        <div class="an-heat-days">
                            @foreach (['Min', '', 'Sel', '', 'Kam', '', 'Sab'] as $dayLabel)
                                <div>{{ $dayLabel }}</div>
                            @endforeach
                        </div>
                        @foreach ($grid['weeks'] as $week)
                            <div class="an-heat-week">
                                @foreach ($week as $day)
                                    @if ($day['inFuture'])
                                        <div class="an-heat-cell future"></div>
                                    @else
                                        <div class="an-heat-cell lv{{ $day['level'] }}" title="{{ Carbon::parse($day['date'])->translatedFormat('j F Y') }} · {{ $day['count'] }} transaksi"></div>
                                    @endif
                                @endforeach
                            </div>
                        @endforeach
                    </div>
                </div>
            </div>

            <div class="rf-legend an-heat-legend">
                <span>Sedikit</span>
                @foreach ([0, 1, 2, 3, 4] as $level)
                    <i class="an-heat-cell lv{{ $level }}"></i>
                @endforeach
                <span>Banyak</span>
            </div>
        </section>
    @endif
</div>

@push('styles')
    <style>
        /* Level warna dari --rf-primary yang dicampur ke permukaan, jadi ikut tema terang/gelap. */
        .an-heat { display: flex; flex-direction: column; gap: 4px; width: 100%; min-width: 760px; }
        .an-heat-months { display: flex; gap: 3px; padding-left: 28px; }
        .an-heat-month { flex: 1 1 0; min-width: 0; font-size: 10px; color: var(--rf-on-surface-variant); white-space: nowrap; }
        .an-heat-body { display: flex; gap: 3px; align-items: stretch; }
        .an-heat-days { display: grid; grid-template-rows: repeat(7, 1fr); gap: 3px; width: 25px; flex: none; }
        .an-heat-days > div { display: flex; align-items: center; font-size: 9px; color: var(--rf-on-surface-variant); }
        .an-heat-week { flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
        .an-heat-week > .an-heat-cell { width: 100%; height: auto; aspect-ratio: 1; }
        .an-heat-cell { display: block; width: 11px; height: 11px; border-radius: 2px; flex: none; }
        .an-heat-cell.future { background: transparent; }
        .an-heat-cell.lv0 { background: var(--rf-surface-container-high); }
        .an-heat-cell.lv1 { background: color-mix(in srgb, var(--rf-primary) 25%, var(--rf-surface-container-high)); }
        .an-heat-cell.lv2 { background: color-mix(in srgb, var(--rf-primary) 50%, var(--rf-surface-container-high)); }
        .an-heat-cell.lv3 { background: color-mix(in srgb, var(--rf-primary) 75%, var(--rf-surface-container-high)); }
        .an-heat-cell.lv4 { background: var(--rf-primary); }
        .an-heat-legend { margin-top: 14px; align-items: center; gap: 4px; justify-content: flex-end; font-size: 12px; }
        .an-heat-legend > span:first-child { margin-right: 4px; }
        .an-heat-legend > span:last-child { margin-left: 4px; }
    </style>
@endpush
