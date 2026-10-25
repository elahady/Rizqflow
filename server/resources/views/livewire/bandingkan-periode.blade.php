@use('App\Support\Period')
@use('App\Support\Rupiah')

<div class="rf-page">
    <x-rf.page-head :sub="Period::label(Period::fromQuery(null)).' · Pribadi'" title="Analisa">
        <x-slot:actions>
            <x-rf.pill tone="info" icon="">Fitur Pro</x-rf.pill>
            @if ($canUseReports)
                <a href="{{ route('laporan.bandingkan.pdf', ['granularity' => $granularity, 'periodA' => $periodA, 'periodB' => $periodB]) }}" class="rf-btn ghost sm"><x-rf.icon name="download" />Unduh PDF</a>
            @endif
        </x-slot:actions>
    </x-rf.page-head>

    <x-analisa.tabs active="bandingkan" />

    @if (! $canUseReports)
        <x-analisa.upsell title="Bandingkan periode adalah fitur Pro." text="Bandingkan dua bulan atau dua tahun berdampingan: pemasukan, pengeluaran, ruang, dan kategori." />
    @else
        <div class="rf-row" style="gap: 8px; align-items: flex-end;">
            <div class="rf-field an-field">
                <span class="rf-label" id="cmp-satuan">Satuan</span>
                <div class="rf-row" style="gap: 8px;" role="group" aria-labelledby="cmp-satuan">
                    <button type="button" wire:click="$set('granularity', 'bulan')" @class(['rf-chip', 'on' => $granularity === 'bulan']) aria-pressed="{{ $granularity === 'bulan' ? 'true' : 'false' }}">Bulan</button>
                    <button type="button" wire:click="$set('granularity', 'tahun')" @class(['rf-chip', 'on' => $granularity === 'tahun']) aria-pressed="{{ $granularity === 'tahun' ? 'true' : 'false' }}">Tahun</button>
                </div>
            </div>
            <div class="rf-field an-field">
                <label for="cmp-a">Periode A</label>
                @if ($granularity === 'bulan')
                    <input type="month" id="cmp-a" class="rf-input" wire:model.live="periodA">
                @else
                    <input type="number" id="cmp-a" class="rf-input" wire:model.live="periodA" min="2000" max="2100">
                @endif
            </div>
            <div class="rf-field an-field">
                <label for="cmp-b">Periode B</label>
                @if ($granularity === 'bulan')
                    <input type="month" id="cmp-b" class="rf-input" wire:model.live="periodB">
                @else
                    <input type="number" id="cmp-b" class="rf-input" wire:model.live="periodB" min="2000" max="2100">
                @endif
            </div>
        </div>

        @php
            $rows = [
                ['label' => 'Pemasukan', 'a' => $a['income'], 'b' => $b['income']],
                ['label' => 'Pengeluaran', 'a' => $a['expense'], 'b' => $b['expense']],
                ['label' => 'Sisa', 'a' => $a['remainder'], 'b' => $b['remainder']],
            ];
        @endphp

        <section class="rf-card">
            <div class="rf-card-head">
                <div>
                    <h2 class="rf-h2">{{ $periodLabelA }} dibanding {{ $periodLabelB }}</h2>
                    <p class="rf-sub" style="margin-top: 4px;">Arah panah hanya menunjukkan naik atau turun, bukan baik atau buruk.</p>
                </div>
            </div>
            <div class="rf-scroll">
                <table class="rf-table" style="min-width: 480px;">
                    <thead>
                        <tr><th></th><th class="num">{{ $periodLabelA }}</th><th class="num">{{ $periodLabelB }}</th><th class="num">Beda</th></tr>
                    </thead>
                    <tbody>
                        @foreach ($rows as $row)
                            @php
                                $delta = $row['a'] - $row['b'];
                                $deltaPercent = $row['b'] != 0 ? round(abs($delta) / abs($row['b']) * 100) : null;
                                $arrow = $delta > 0 ? '↑' : ($delta < 0 ? '↓' : '=');
                                $arrowText = $delta > 0 ? 'naik' : ($delta < 0 ? 'turun' : 'sama');
                            @endphp
                            <tr>
                                <td style="font-weight: 600;">{{ $row['label'] }}</td>
                                <td class="num" style="font-weight: 700;">{{ Rupiah::format($row['a']) }}</td>
                                <td class="num" style="color: var(--rf-on-surface-variant);">{{ Rupiah::format($row['b']) }}</td>
                                <td class="num" style="color: var(--rf-on-surface-variant);">
                                    <span aria-hidden="true">{{ $arrow }}</span> <span class="rf-sr-only">{{ $arrowText }}</span> {{ $deltaPercent === null ? Rupiah::format(abs($delta)) : $deltaPercent.'%' }}
                                </td>
                            </tr>
                        @endforeach
                    </tbody>
                </table>
            </div>
        </section>

        <div class="rf-split">
            <section class="rf-card eq">
                <div class="rf-card-head"><h2 class="rf-h2">Pengeluaran per ruang</h2></div>
                @if ($rooms->isEmpty())
                    <div class="rf-empty">
                        <x-rf.icon name="grid" />
                        <b>Belum ada pengeluaran</b>
                        <p>Belum ada pengeluaran di kedua periode.</p>
                    </div>
                @else
                    <div class="rf-legend" style="margin-bottom: 8px;">
                        <span><i class="rf-dot" style="--c: var(--rf-primary);"></i>{{ $periodLabelA }}</span>
                        <span><i class="rf-dot" style="--c: var(--rf-outline-variant);"></i>{{ $periodLabelB }}</span>
                    </div>
                    @foreach ($rooms as $room)
                        @php $max = max($room['a'], $room['b'], 1); @endphp
                        <div class="rf-rowline an-room">
                            <div class="rf-grow">
                                <div style="font-size: 13px; font-weight: 600; margin-bottom: 6px;">{{ $room['name'] }}</div>
                                <div class="an-bar-line">
                                    <div class="an-bar"><i style="width: {{ $room['a'] / $max * 100 }}%; --c: var(--rf-primary);"></i></div>
                                    <span class="an-bar-val">{{ Rupiah::format($room['a']) }}</span>
                                </div>
                                <div class="an-bar-line">
                                    <div class="an-bar"><i style="width: {{ $room['b'] / $max * 100 }}%; --c: var(--rf-outline-variant);"></i></div>
                                    <span class="an-bar-val" style="color: var(--rf-on-surface-variant);">{{ Rupiah::format($room['b']) }}</span>
                                </div>
                            </div>
                        </div>
                    @endforeach
                @endif
            </section>

            <section class="rf-card eq">
                <div class="rf-card-head"><h2 class="rf-h2">Kategori terbesar</h2></div>
                @if ($categories->isEmpty())
                    <div class="rf-empty">
                        <x-rf.icon name="list" />
                        <b>Belum ada pengeluaran</b>
                        <p>Belum ada pengeluaran di kedua periode.</p>
                    </div>
                @else
                    <div class="rf-scroll">
                        <table class="rf-table">
                            <thead>
                                <tr><th>Kategori</th><th class="num">{{ $periodLabelA }}</th><th class="num">{{ $periodLabelB }}</th></tr>
                            </thead>
                            <tbody>
                                @foreach ($categories as $category)
                                    <tr>
                                        <td style="font-weight: 600;">{{ $category['name'] }}</td>
                                        <td class="num">{{ Rupiah::format($category['a']) }}</td>
                                        <td class="num" style="color: var(--rf-on-surface-variant);">{{ Rupiah::format($category['b']) }}</td>
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
                        <h2 class="rf-h2">Insight {{ $periodLabelA }}</h2>
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
