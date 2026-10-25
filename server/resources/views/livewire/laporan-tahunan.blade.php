@use('App\Support\Rupiah')
@use('App\Support\Reports\ReportInsightSentences')
@use('Carbon\Carbon')

<div class="rf-page">
    <x-rf.page-head :sub="'Tahun '.$year.' · Pribadi'" title="Analisa">
        <x-slot:actions>
            <x-rf.pill tone="info" icon="">Fitur Pro</x-rf.pill>
        </x-slot:actions>
    </x-rf.page-head>

    <x-analisa.tabs active="tahunan" />

    @if (! $canUseReports)
        <x-analisa.upsell title="Laporan tahunan adalah fitur Pro." text="Lihat satu tahun penuh: 12 bulan, perbandingan dengan tahun lalu, pengeluaran per ruang, dan kategori terbesar." />
    @else
        <div class="rf-row" style="gap: 8px; align-items: flex-end;">
            <div class="rf-field an-field">
                <label for="yr-tahun">Tahun</label>
                <input type="number" id="yr-tahun" class="rf-input" wire:model.live="tahun" min="2000" max="2100">
            </div>
        </div>

        @if (! $report->hasData())
            <div class="rf-empty">
                <x-rf.icon name="grid" />
                <b>Belum ada catatan di {{ $year }}</b>
                <p>Laporan tahunan terisi begitu ada pemasukan atau pengeluaran di tahun ini.</p>
            </div>
        @else
            @php
                $summaryRows = [
                    ['label' => 'Pemasukan', 'cmp' => $report->income],
                    ['label' => 'Pengeluaran', 'cmp' => $report->expense],
                    ['label' => 'Sisa', 'cmp' => $report->net()],
                ];
            @endphp

            <section class="rf-card">
                <div class="rf-card-head">
                    <div>
                        <h2 class="rf-h2">{{ $year }} dibanding {{ $year - 1 }}</h2>
                        <p class="rf-sub" style="margin-top: 4px;">Arah panah hanya menunjukkan naik atau turun, bukan baik atau buruk.</p>
                    </div>
                </div>
                <div class="rf-scroll">
                    <table class="rf-table" style="min-width: 480px;">
                        <thead>
                            <tr><th></th><th class="num">{{ $year }}</th><th class="num">{{ $year - 1 }}</th><th class="num">Beda</th></tr>
                        </thead>
                        <tbody>
                            @foreach ($summaryRows as $row)
                                @php
                                    $cmp = $row['cmp'];
                                    $delta = $cmp->delta();
                                    $percentBp = $cmp->percentBp();
                                    $arrow = $delta > 0 ? '↑' : ($delta < 0 ? '↓' : '=');
                                    $arrowText = $delta > 0 ? 'naik' : ($delta < 0 ? 'turun' : 'sama');
                                @endphp
                                <tr>
                                    <td style="font-weight: 600;">{{ $row['label'] }}</td>
                                    <td class="num" style="font-weight: 700;">{{ Rupiah::format($cmp->current) }}</td>
                                    <td class="num" style="color: var(--rf-on-surface-variant);">{{ Rupiah::format($cmp->previous) }}</td>
                                    <td class="num" style="color: var(--rf-on-surface-variant);">
                                        <span aria-hidden="true">{{ $arrow }}</span> <span class="rf-sr-only">{{ $arrowText }}</span>
                                        {{ $percentBp === null ? Rupiah::format(abs($delta)) : ReportInsightSentences::percent(abs($percentBp)) }}
                                    </td>
                                </tr>
                            @endforeach
                        </tbody>
                    </table>
                </div>
            </section>

            <section class="rf-card">
                <div class="rf-card-head"><h2 class="rf-h2">Per bulan</h2></div>
                <div class="rf-scroll">
                    <table class="rf-table" style="min-width: 480px;">
                        <thead>
                            <tr><th>Bulan</th><th class="num">Pemasukan</th><th class="num">Pengeluaran</th><th class="num">Sisa</th></tr>
                        </thead>
                        <tbody>
                            @foreach ($report->months as $month)
                                <tr @if (! $month->hasData()) style="color: var(--rf-on-surface-variant);" @endif>
                                    <td style="font-weight: 600;">{{ Carbon::createFromFormat('Y-m', $month->month)->translatedFormat('F') }}</td>
                                    <td class="num">{{ Rupiah::format($month->income) }}</td>
                                    <td class="num">{{ Rupiah::format($month->expense) }}</td>
                                    <td class="num">{{ Rupiah::format($month->income - $month->expense) }}</td>
                                </tr>
                            @endforeach
                            <tr>
                                <td style="font-weight: 700;">Total</td>
                                <td class="num" style="font-weight: 700;">{{ Rupiah::format($report->income->current) }}</td>
                                <td class="num" style="font-weight: 700;">{{ Rupiah::format($report->expense->current) }}</td>
                                <td class="num" style="font-weight: 700;">{{ Rupiah::format($report->net()->current) }}</td>
                            </tr>
                        </tbody>
                    </table>
                </div>
            </section>

            @php $maxRoom = max(array_map(fn ($room) => $room->amount, $report->roomSpend) ?: [1]); @endphp
            <div class="rf-split">
                <section class="rf-card eq">
                    <div class="rf-card-head"><h2 class="rf-h2">Pengeluaran per ruang</h2></div>
                    @if ($report->roomSpend === [])
                        <div class="rf-empty">
                            <x-rf.icon name="grid" />
                            <b>Belum ada pengeluaran</b>
                            <p>Belum ada pengeluaran di ruang tahun ini.</p>
                        </div>
                    @else
                        @foreach ($report->roomSpend as $room)
                            <div class="rf-rowline an-room">
                                <div class="rf-grow">
                                    <div style="font-size: 13px; font-weight: 600; margin-bottom: 6px;">{{ $room->roomName }}</div>
                                    <div class="an-bar-line">
                                        <div class="an-bar"><i style="width: {{ intdiv($room->amount * 100, $maxRoom) }}%; --c: var(--rf-primary);"></i></div>
                                        <span class="an-bar-val">{{ Rupiah::format($room->amount) }}</span>
                                    </div>
                                </div>
                            </div>
                        @endforeach
                    @endif
                </section>

                <section class="rf-card eq">
                    <div class="rf-card-head"><h2 class="rf-h2">10 kategori terbesar</h2></div>
                    @if ($report->topCategories === [])
                        <div class="rf-empty">
                            <x-rf.icon name="list" />
                            <b>Belum ada pengeluaran</b>
                            <p>Belum ada pengeluaran per kategori tahun ini.</p>
                        </div>
                    @else
                        <div class="rf-scroll">
                            <table class="rf-table">
                                <thead>
                                    <tr><th>Kategori</th><th>Ruang</th><th class="num">Jumlah</th></tr>
                                </thead>
                                <tbody>
                                    @foreach ($report->topCategories as $category)
                                        <tr>
                                            <td style="font-weight: 600;">{{ $category->categoryName }}</td>
                                            <td style="color: var(--rf-on-surface-variant);">{{ $category->roomName }}</td>
                                            <td class="num">{{ Rupiah::format($category->amount) }}</td>
                                        </tr>
                                    @endforeach
                                </tbody>
                            </table>
                        </div>
                    @endif
                </section>
            </div>

            @if ($insightSentences !== [])
                <section class="rf-card">
                    <div class="rf-card-head">
                        <div>
                            <h2 class="rf-h2">Insight {{ $year }}</h2>
                            <p class="rf-sub" style="margin-top: 4px;">Hanya dari angka tahun ini. Bila datanya belum cukup, insight tidak ditampilkan.</p>
                        </div>
                    </div>
                    <ul class="an-insights">
                        @foreach ($insightSentences as $sentence)
                            <li>{{ $sentence }}</li>
                        @endforeach
                    </ul>
                </section>
            @endif
        @endif

        <p class="rf-hint"><x-rf.icon name="info" />Transfer dan transaksi pinjaman (utang-piutang) tidak ikut terhitung: keduanya tidak pernah tercatat di tabel transaksi biasa.</p>
    @endif
</div>

@push('styles')
    <style>
        .an-field { min-width: 160px; }
        .an-room .an-bar-line { display: flex; align-items: center; gap: 10px; margin-top: 4px; }
        .an-bar { flex: 1 1 0; min-width: 0; height: 8px; border-radius: 999px; overflow: hidden; background: var(--rf-surface-container-high); }
        .an-bar > i { display: block; height: 100%; border-radius: 999px; background: var(--c); }
        .an-bar-val { flex: none; width: 110px; text-align: right; font-size: 12.5px; font-variant-numeric: tabular-nums; }
        .an-insights { margin: 0; padding-left: 20px; display: grid; gap: 8px; font-size: 13.5px; line-height: 1.5; }
    </style>
@endpush
