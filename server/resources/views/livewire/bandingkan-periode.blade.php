@php use App\Support\Rupiah; @endphp

<div style="display:flex; flex-direction:column; gap:22px; max-width:880px; margin:0 auto; width:100%;">

    <div><a href="{{ route('laporan') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Laporan</a></div>

    <h1 class="rf-serif" style="font-size:26px;">Bandingkan periode</h1>

    @if (! $canUseReports)
        <div class="rf-card">
            <p style="font-size:14px; color:var(--rf-on-surface-variant);">Bandingkan periode adalah fitur Pro. Upgrade untuk membandingkan dua bulan atau tahun berdampingan.</p>
        </div>
    @else
        <div class="rf-card" style="display:flex; gap:14px; flex-wrap:wrap; align-items:flex-end;">
            <div class="rf-field" style="margin:0; min-width:140px;">
                <label for="cmp-granularity">Satuan</label>
                <select id="cmp-granularity" wire:model.live="granularity">
                    <option value="bulan">Bulan</option>
                    <option value="tahun">Tahun</option>
                </select>
            </div>
            <div class="rf-field" style="margin:0; min-width:160px;">
                <label for="cmp-a">Periode A</label>
                @if ($granularity === 'bulan')
                    <input type="month" id="cmp-a" wire:model.live="periodA">
                @else
                    <input type="number" id="cmp-a" wire:model.live="periodA" min="2000" max="2100">
                @endif
            </div>
            <div class="rf-field" style="margin:0; min-width:160px;">
                <label for="cmp-b">Periode B</label>
                @if ($granularity === 'bulan')
                    <input type="month" id="cmp-b" wire:model.live="periodB">
                @else
                    <input type="number" id="cmp-b" wire:model.live="periodB" min="2000" max="2100">
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

        <div class="rf-card">
            <div style="display:flex; justify-content:flex-end; gap:40px; font-size:12px; font-weight:700; text-transform:uppercase; letter-spacing:0.03em; color:var(--rf-on-surface-variant); margin-bottom:10px;">
                <span style="width:130px; text-align:right;">{{ $periodLabelA }}</span>
                <span style="width:130px; text-align:right;">{{ $periodLabelB }}</span>
                <span style="width:90px; text-align:right;">Beda</span>
            </div>
            @foreach ($rows as $row)
                @php
                    $delta = $row['a'] - $row['b'];
                    $deltaPercent = $row['b'] != 0 ? round(abs($delta) / abs($row['b']) * 100) : null;
                    $arrow = $delta > 0 ? '↑' : ($delta < 0 ? '↓' : '=');
                @endphp
                <div style="display:flex; align-items:center; justify-content:space-between; gap:12px; padding:10px 0; border-top:1px solid var(--rf-outline-variant);">
                    <span style="font-weight:600; font-size:14px;">{{ $row['label'] }}</span>
                    <div style="display:flex; gap:40px; font-size:14px;">
                        <span style="width:130px; text-align:right; font-weight:700;">{{ Rupiah::format($row['a']) }}</span>
                        <span style="width:130px; text-align:right; color:var(--rf-on-surface-variant);">{{ Rupiah::format($row['b']) }}</span>
                        <span style="width:90px; text-align:right; color:var(--rf-on-surface-variant);">
                            {{ $arrow }} {{ $deltaPercent === null ? Rupiah::format(abs($delta)) : $deltaPercent.'%' }}
                        </span>
                    </div>
                </div>
            @endforeach
        </div>

        <div class="rf-card">
            <h2 class="rf-serif" style="font-size:18px; margin-bottom:10px;">Pengeluaran per ruang</h2>
            @forelse ($rooms as $room)
                @php $max = max($room['a'], $room['b'], 1); @endphp
                <div style="padding:10px 0; border-top:1px solid var(--rf-outline-variant);">
                    <div style="font-size:13px; font-weight:600; margin-bottom:6px;">{{ $room['name'] }}</div>
                    <div style="display:flex; align-items:center; gap:8px; margin-bottom:4px;">
                        <div style="flex:1; height:8px; border-radius:999px; overflow:hidden; background:var(--rf-surface-container-highest);"><div style="width:{{ $room['a'] / $max * 100 }}%; height:100%; background:#3b6fa8;"></div></div>
                        <span style="font-size:12px; width:100px; text-align:right;">{{ Rupiah::format($room['a']) }}</span>
                    </div>
                    <div style="display:flex; align-items:center; gap:8px;">
                        <div style="flex:1; height:8px; border-radius:999px; overflow:hidden; background:var(--rf-surface-container-highest);"><div style="width:{{ $room['b'] / $max * 100 }}%; height:100%; background:#c2c8c3;"></div></div>
                        <span style="font-size:12px; width:100px; text-align:right; color:var(--rf-on-surface-variant);">{{ Rupiah::format($room['b']) }}</span>
                    </div>
                </div>
            @empty
                <p style="font-size:14px; color:var(--rf-on-surface-variant);">Belum ada pengeluaran di kedua periode.</p>
            @endforelse
        </div>

        <div class="rf-card">
            <h2 class="rf-serif" style="font-size:18px; margin-bottom:10px;">Kategori terbesar</h2>
            @forelse ($categories as $category)
                <div style="display:flex; align-items:center; justify-content:space-between; gap:12px; padding:10px 0; border-top:1px solid var(--rf-outline-variant); font-size:13px;">
                    <span style="font-weight:600;">{{ $category['name'] }}</span>
                    <span>{{ Rupiah::format($category['a']) }} <span style="color:var(--rf-on-surface-variant);">vs {{ Rupiah::format($category['b']) }}</span></span>
                </div>
            @empty
                <p style="font-size:14px; color:var(--rf-on-surface-variant);">Belum ada pengeluaran di kedua periode.</p>
            @endforelse
        </div>

        <p style="font-size:12.5px; color:var(--rf-on-surface-variant);">Transfer dan transaksi pinjaman (utang-piutang) tidak ikut terhitung — keduanya tidak pernah tercatat di tabel transaksi biasa.</p>
    @endif

</div>
