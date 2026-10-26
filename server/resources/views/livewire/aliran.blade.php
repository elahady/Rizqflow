@use('App\Support\Rupiah')
@use('App\Support\SankeyLayout')
@use('Illuminate\Support\Str')

@php
    // Kelas warna slot ruang (c1..c8; selain itu netral cn) — sama dengan x-rf.room-icon.
    $slotClass = fn ($slot) => is_numeric($slot) && (int) $slot >= 1 && (int) $slot <= 8 ? 'c'.(int) $slot : 'cn';
    $fmt = fn (float $v): string => rtrim(rtrim(number_format($v, 2, '.', ''), '0'), '.');
    $layout = $flow['layout'] ?? null;
@endphp

@push('styles')
<style>
    /* Aliran: grup SVG per ruang bisa disorot (opacity diatur Alpine), penanda melewati jatah. */
    .al-grp { transition: opacity 0.15s ease; }
    @media (prefers-reduced-motion: reduce) { .al-grp { transition: none; } }
    .al-over { fill: color-mix(in srgb, var(--rf-status-warning) 55%, transparent); stroke: var(--rf-status-warning); stroke-width: 1.5; stroke-dasharray: 4 3; }
    .al-overt { fill: var(--rf-pill-warn-fg); font-size: 12px; font-weight: 600; font-family: var(--rf-font-ui); }
    .al-svg { display: block; overflow: visible; }
    .al-focus { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
    .al-note { margin-top: 10px; }
</style>
@endpush

<div class="rf-page">
    <x-rf.page-head :sub="$rangeLabel" title="Analisa">
        <x-slot:actions><x-rf.pill tone="info" icon="">Fitur Pro</x-rf.pill></x-slot:actions>
    </x-rf.page-head>

    <x-analisa.tabs active="aliran" />

    @if (! $canUseReports)
        <x-analisa.upsell title="Aliran rezeki adalah fitur Pro." text="Lihat ke mana setiap rupiah rezekimu mengalir: dari pemasukan ke ruang, lalu ke pos dan sisa." />
    @else
        {{-- Fokus ruang (Alpine) disimpan lewat wire:key: ganti tampilan atau rentang menyetel ulang fokus ke nilai tersimpan. --}}
        <div wire:key="aliran-{{ $end->format('Y-m') }}-{{ $count }}-{{ $akunMode }}-{{ implode(',', $akunTerpilih) }}-{{ $sorotAktif }}" x-data="{ focus: @js($sorotAktif), simpanBuka: false }">
            <div class="rf-row" style="gap: 8px" role="group" aria-label="Rentang waktu dan akun">
                @foreach (['1' => 'Bulan ini', '3' => '3 bulan', '12' => '12 bulan'] as $value => $label)
                    <button type="button" wire:click="setRentang('{{ $value }}')" @class(['rf-chip', 'on' => (string) $count === (string) $value]) aria-pressed="{{ (string) $count === (string) $value ? 'true' : 'false' }}">{{ (string) $value === '1' && ! \App\Support\Period::isCurrent($end) ? '1 bulan' : $label }}</button>
                @endforeach
                <button type="button" wire:click="setAkun('semua')" @class(['rf-chip', 'on' => $akunMode === 'semua']) aria-pressed="{{ $akunMode === 'semua' ? 'true' : 'false' }}">Semua akun</button>
                <button type="button" wire:click="setAkun('kustom')" @class(['rf-chip', 'on' => $akunMode === 'kustom']) aria-pressed="{{ $akunMode === 'kustom' ? 'true' : 'false' }}">Kustom</button>
                <span style="flex: 1 1 0"></span>
                <button type="button" class="rf-btn ghost sm" @click="simpanBuka = !simpanBuka" :aria-expanded="simpanBuka ? 'true' : 'false'" aria-controls="al-simpan">Simpan tampilan</button>
                <a class="rf-btn ghost sm" href="{{ $pdfUrl }}"><x-rf.icon name="download" />Unduh PDF</a>
            </div>

            @if ($akunMode === 'kustom')
                <div class="rf-card" style="margin-top: 10px">
                    <p class="rf-sub" style="margin-bottom: 8px">Pilih akun yang ikut dihitung di diagram ini. Minimal satu akun harus terpilih.</p>
                    <div class="rf-row" style="gap: 16px; flex-wrap: wrap">
                        @forelse ($akunList as $akun)
                            <label style="display: inline-flex; align-items: center; gap: 8px">
                                <input type="checkbox" wire:click="toggleAkun({{ $akun->id }})" @checked(in_array($akun->id, $akunTerpilih, true))>
                                <span>{{ $akun->name }}@if ($akun->archived) <span class="rf-sub">(diarsipkan)</span>@endif</span>
                            </label>
                        @empty
                            <p class="rf-sub">Belum ada akun.</p>
                        @endforelse
                    </div>
                </div>
            @endif

            <div id="al-simpan" style="display: none; margin-top: 10px" x-show="simpanBuka">
                <form class="rf-row" style="gap: 8px; flex-wrap: wrap" x-on:submit.prevent="$wire.simpanTampilan(focus)">
                    <input type="text" class="rf-input" style="max-width: 320px" wire:model="namaTampilan" maxlength="60" placeholder="Nama tampilan, mis. Rezeki 3 bulan" aria-label="Nama tampilan" aria-describedby="al-nama-err">
                    <button type="submit" class="rf-btn tonal sm">Simpan</button>
                </form>
                @error('namaTampilan')
                    <p class="rf-sub" id="al-nama-err" role="alert" style="margin-top: 6px">{{ $message }}</p>
                @enderror
            </div>

            @if ($tampilan->isNotEmpty())
                <div class="rf-row" style="gap: 8px; margin-top: 10px; flex-wrap: wrap" role="group" aria-label="Tampilan tersimpan">
                    <span class="rf-sub">Tersimpan:</span>
                    @foreach ($tampilan as $view)
                        <span class="rf-row" style="gap: 2px">
                            <button type="button" class="rf-chip" wire:click="terapkanTampilan({{ $view->id }})">{{ $view->name }}</button>
                            <button type="button" class="rf-btn ghost sm" wire:click="hapusTampilan({{ $view->id }})" wire:confirm="Hapus tampilan ini?" aria-label="Hapus tampilan {{ $view->name }}"><x-rf.icon name="x" /></button>
                        </span>
                    @endforeach
                </div>
            @endif

        @if ($layout['empty'])
            <section class="rf-card">
                <div class="rf-empty">
                    <x-rf.icon name="chart" />
                    <b>Belum ada rezeki yang dicatat pada {{ $rangeLabel }}.</b>
                    <p>Diagram aliran muncul setelah ada pemasukan yang dialirkan ke ruang. Pilih rentang lain, atau catat rezeki yang masuk.</p>
                    <div class="rf-actions">
                        <a class="rf-btn tonal sm" href="{{ route('transaksi.rezeki') }}"><x-rf.icon name="plus" />Catat rezeki masuk</a>
                    </div>
                </div>
            </section>
        @else
            @php
                $total = $layout['total'];
                $height = $layout['height'] + 28;
            @endphp

            <section class="rf-card">
                <div class="rf-card-head">
                    <div>
                        <h2 class="rf-h2">Dari mana rezeki datang, ke mana ia mengalir</h2>
                        <p class="rf-sub" style="margin-top: 4px">Lebar pita sebanding dengan rupiah. Total {{ Rupiah::format($total) }}.</p>
                    </div>
                    <div class="al-focus">
                        <span class="rf-sub" id="al-sorot">Sorot ruang</span>
                        <div class="rf-seg" role="group" aria-labelledby="al-sorot">
                            <button type="button" class="rf-seg-btn on" :class="{ on: focus === 'semua' }" :aria-pressed="focus === 'semua'" @click="focus = 'semua'">Semua</button>
                            @foreach ($flow['focus'] as $item)
                                <button type="button" class="rf-seg-btn" :class="{ on: focus === '{{ $item['key'] }}' }" :aria-pressed="focus === '{{ $item['key'] }}'" @click="focus = '{{ $item['key'] }}'">{{ $item['name'] }}</button>
                            @endforeach
                        </div>
                    </div>
                </div>

                <div class="rf-scroll">
                    <div style="min-width: 780px">
                        <svg class="al-svg" viewBox="0 0 {{ SankeyLayout::WIDTH }} {{ $fmt($height) }}" width="100%" role="img" aria-label="{{ $flow['aria'] }}">
                            <text class="ch-axis" x="0" y="14">SUMBER</text>
                            <text class="ch-axis" x="{{ SankeyLayout::ROOM_X }}" y="14">RUANG</text>
                            <text class="ch-axis" x="{{ SankeyLayout::POS_X }}" y="14">POS DAN SISA</text>
                            <g transform="translate(0 28)">

                                @foreach ($layout['rooms'] as $room)
                                    <g class="al-grp {{ $slotClass($room['slot'] ?? null) }}" :opacity="focus === 'semua' || focus === '{{ $room['key'] }}' ? 1 : 0.14">
                                        @foreach ($room['links_in'] as $link)
                                            <path class="sk-link" d="{{ $link['d'] }}" />
                                        @endforeach
                                        @foreach ($room['links_out'] as $link)
                                            <path @class(['sk-link', 'sisa' => $link['sisa']]) d="{{ $link['d'] }}" />
                                        @endforeach

                                        <rect class="sk-room" x="{{ $room['x'] }}" y="{{ $fmt($room['y']) }}" width="{{ $room['w'] }}" height="{{ $fmt($room['h']) }}" rx="6" />
                                        <rect class="sk-band" x="{{ $room['x'] }}" y="{{ $fmt($room['y']) }}" width="6" height="{{ $fmt($room['h']) }}" rx="3" />
                                        @if ($room['over_y'] !== null)
                                            <rect class="al-over" x="{{ $room['x'] + 8 }}" y="{{ $fmt($room['over_y']) }}" width="{{ $room['w'] - 8 }}" height="{{ $fmt(max($room['over_h'], 1)) }}" rx="3">
                                                <title>Melewati jatah {{ Rupiah::format($room['over']) }}</title>
                                            </rect>
                                        @endif
                                        <text class="sk-t1" x="{{ $room['x'] + 18 }}" y="{{ $fmt($room['over'] > 0 ? $room['y'] + 17 : $room['cy'] - 4) }}">{{ Str::limit($room['label'], 16, '…') }}</text>
                                        <text class="sk-t2" x="{{ $room['x'] + 18 }}" y="{{ $fmt($room['over'] > 0 ? $room['y'] + 33 : $room['cy'] + 12) }}">{{ Rupiah::compact($room['allocated']) }}@if ($room['allocated'] > 0) · {{ SankeyLayout::percentWhole($room['allocated'], $total) < 1 ? '<1' : SankeyLayout::percentWhole($room['allocated'], $total) }}%@endif</text>
                                        @if ($room['over'] > 0)
                                            <text class="al-overt" x="{{ $room['x'] + 18 }}" y="{{ $fmt($room['y'] + $room['h'] + 14) }}">{{ $room['allocated'] > 0 ? 'Melewati jatah' : 'Tanpa jatah' }} {{ Rupiah::compact($room['over']) }}</text>
                                        @endif

                                        @foreach ($room['pos'] as $pos)
                                            <rect class="sk-node" x="{{ $pos['x'] }}" y="{{ $fmt($pos['y']) }}" width="{{ $pos['w'] }}" height="{{ $fmt($pos['h']) }}" rx="2" />
                                            <text x="{{ $pos['x'] + 24 }}" y="{{ $fmt($pos['cy'] + 4) }}"><title>{{ $pos['label'] }}: {{ Rupiah::format($pos['amount']) }}</title><tspan class="sk-n">{{ Str::limit($pos['label'], 24, '…') }}</tspan><tspan class="sk-v" dx="8">{{ Rupiah::compact($pos['amount']) }}</tspan></text>
                                        @endforeach
                                        @if ($room['sisa'])
                                            <rect class="sk-sisa" x="{{ $room['sisa']['x'] }}" y="{{ $fmt($room['sisa']['y']) }}" width="{{ $room['sisa']['w'] }}" height="{{ $fmt($room['sisa']['h']) }}" rx="2" />
                                            <text x="{{ $room['sisa']['x'] + 24 }}" y="{{ $fmt($room['sisa']['cy'] + 4) }}"><title>Sisa belum terpakai: {{ Rupiah::format($room['sisa']['amount']) }}</title><tspan class="sk-n">Sisa belum terpakai</tspan><tspan class="sk-v" dx="8">{{ Rupiah::compact($room['sisa']['amount']) }}</tspan></text>
                                        @endif
                                    </g>
                                @endforeach

                                @if ($layout['unallocated'])
                                    @php $un = $layout['unallocated']; @endphp
                                    <g class="al-grp cn" :opacity="focus === 'semua' ? 1 : 0.14">
                                        @foreach ($un['links_in'] as $link)
                                            <path class="sk-link sisa" d="{{ $link['d'] }}" />
                                        @endforeach
                                        <rect class="sk-sisa" x="{{ $un['x'] }}" y="{{ $fmt($un['y']) }}" width="{{ $un['w'] }}" height="{{ $fmt($un['h']) }}" rx="6" />
                                        <text class="sk-t1" x="{{ $un['x'] + 18 }}" y="{{ $fmt($un['cy'] - 4) }}">Belum dialirkan</text>
                                        <text class="sk-t2" x="{{ $un['x'] + 18 }}" y="{{ $fmt($un['cy'] + 12) }}">{{ Rupiah::compact($un['amount']) }} · {{ SankeyLayout::percentWhole($un['amount'], $total) < 1 ? '<1' : SankeyLayout::percentWhole($un['amount'], $total) }}%</text>
                                    </g>
                                @endif

                                @foreach ($layout['sources'] as $source)
                                    @php
                                        $lines = [['sk-t1', Str::limit($source['label'], 19, '…')], ['sk-t2', Rupiah::format($source['amount'])]];
                                        if (! empty($source['detail']) && $source['h'] >= 56) {
                                            $lines[] = ['sk-t2', Str::limit($source['detail'], 24, '…')];
                                        }
                                        $first = $source['cy'] - (count($lines) - 1) * 9 + 4;
                                    @endphp
                                    <rect class="sk-src" x="{{ $source['x'] }}" y="{{ $fmt($source['y']) }}" width="{{ $source['w'] }}" height="{{ $fmt($source['h']) }}" rx="6"><title>{{ $source['label'] }}: {{ Rupiah::format($source['amount']) }}</title></rect>
                                    <rect class="sk-srcband" x="{{ $source['x'] }}" y="{{ $fmt($source['y']) }}" width="6" height="{{ $fmt($source['h']) }}" rx="3" />
                                    @foreach ($lines as $i => [$class, $text])
                                        <text class="{{ $class }}" x="{{ $source['x'] + 20 }}" y="{{ $fmt($first + $i * 18) }}">{{ $text }}</text>
                                    @endforeach
                                @endforeach
                            </g>
                        </svg>
                    </div>
                </div>

                <div class="rf-legend" style="margin-top: 12px">
                    @foreach ($layout['rooms'] as $room)
                        <span><i class="rf-dot {{ $slotClass($room['slot'] ?? null) }}"></i>{{ $room['label'] }}</span>
                    @endforeach
                    <span><i class="rf-dot" style="background: var(--rf-surface-container-highest); outline: 1px solid var(--rf-outline)"></i>Sisa belum terpakai</span>
                    @if ($layout['unallocated'])
                        <span><i class="rf-dot" style="background: var(--rf-surface-container-highest); outline: 1px solid var(--rf-outline)"></i>Belum dialirkan</span>
                    @endif
                    @if ($layout['over'] > 0)
                        <span><i class="rf-dot" style="background: var(--rf-status-warning); outline: 1px dashed var(--rf-status-warning)"></i>Melewati jatah</span>
                    @endif
                </div>

                <div class="rf-readout" style="margin-top: 14px" role="status" aria-live="polite">
                    <x-rf.icon name="info" />
                    @foreach ($flow['readouts'] as $key => $text)
                        <span @if ($key !== 'semua') style="display: none" @endif x-show="focus === '{{ $key }}'">{{ $text }}</span>
                    @endforeach
                </div>
            </section>

            <div class="rf-split">
                <section class="rf-card a">
                    <div class="rf-card-head"><h2 class="rf-h2">Rincian (tabel kembaran grafik)</h2><span class="rf-sub">{{ $rangeLabel }}</span></div>
                    <div class="rf-scroll">
                        <table class="rf-table" style="min-width: 560px">
                            <thead>
                                <tr><th>Ruang</th><th>Pos</th><th class="num">Rupiah</th><th class="num">% jatah ruang</th><th class="num">% rezeki</th></tr>
                            </thead>
                            <tbody>
                                @foreach ($flow['rows'] as $row)
                                    <tr>
                                        <td><span class="rf-roomname"><i class="rf-dot {{ $slotClass($row['slot']) }}"></i>{{ $row['room'] }}</span></td>
                                        <td>{{ $row['pos'] }}</td>
                                        <td class="num">{{ Rupiah::plain($row['amount']) }}</td>
                                        <td class="num">{{ $row['ofRoom'] }}</td>
                                        <td class="num">{{ $row['ofTotal'] }}</td>
                                    </tr>
                                @endforeach
                            </tbody>
                            <tfoot>
                                <tr><td colspan="2">Total rezeki</td><td class="num">{{ Rupiah::plain($total) }}</td><td class="num"></td><td class="num">100%</td></tr>
                            </tfoot>
                        </table>
                    </div>
                    @if ($flow['overNote'])
                        <p class="rf-sub al-note">Pengeluaran yang melewati jatah ruang ({{ Rupiah::format($flow['overNote']) }}) ikut terhitung di baris pos, jadi jumlah baris bisa lebih besar dari total rezeki.</p>
                    @endif
                </section>

                <section class="rf-card b">
                    <h2 class="rf-h2" style="margin-bottom: 6px">{{ $count === 1 && \App\Support\Period::isCurrent($end) ? 'Yang terlihat bulan ini' : 'Yang terlihat pada rentang ini' }}</h2>
                    @forelse ($flow['insights'] as $item)
                        <div class="rf-rowline">
                            @if ($item['roomIcon'] === false)
                                <span class="rf-roomic cn"><x-rf.icon name="clock" /></span>
                            @else
                                <x-rf.room-icon :slot="$item['slot']" :icon="$item['roomIcon']" />
                            @endif
                            <p class="rf-grow">{{ $item['before'] }}@isset($item['link'])<a class="rf-link" href="{{ $item['link']['href'] }}">{{ $item['link']['label'] }}</a>{{ $item['after'] }}@endisset</p>
                        </div>
                    @empty
                        <p class="rf-sub">Belum ada pola yang bisa dibaca pada rentang ini.</p>
                    @endforelse
                </section>
            </div>
        @endif
        </div>
    @endif
</div>
