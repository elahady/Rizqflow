@php
    use App\Livewire\Ruang;
    use App\Support\Rupiah;

    $slotClass = $room->slot >= 1 && $room->slot <= 8 ? 'c'.$room->slot : 'cn';
    $percent = $roomMonth->percent();
    $typeLabel = ['menunaikan' => 'Menunaikan', 'menumbuhkan' => 'Menumbuhkan', 'mencukupi' => 'Mencukupi'][$room->type] ?? $room->type;
    $warn = $roomMonth->status() === 'perlu_perhatian';
    $safePerDay = $roomMonth->safePerDayMinor();

    // "Sisa Rp · aman ≈ Rp per hari untuk N hari tersisa · Tipe"
    $detail = [];
    if ($roomMonth->allocatedMinor > 0) {
        $detail[] = $roomMonth->remainingMinor() >= 0
            ? 'Sisa '.Rupiah::format($roomMonth->remainingMinor())
            : 'Melewati jatah '.Rupiah::format(-$roomMonth->remainingMinor());
    }
    if ($safePerDay !== null) {
        $detail[] = 'aman ≈ '.Rupiah::format($safePerDay).' per hari untuk '.$ledger->daysLeft().' hari tersisa';
    }
    $detail[] = $typeLabel;

    $weightTotal = $categories->sum(fn ($pos) => $pos['category']->is_system ? 0 : (int) ($pos['category']->weight ?? 0));
    $roomQuery = ['bulan' => $month->format('Y-m')];
@endphp

@push('styles')
<style>
    /* Ruang detail: grafik laju belanja dan riwayat. */
    .rr-chart { width: 100%; height: auto; overflow: visible; }
    .rr-pace-dot { fill: var(--rf-outline); stroke: var(--rf-surface-container-lowest); stroke-width: 2; }
    .rr-slider { display: flex; align-items: center; gap: 14px; margin-top: 6px; }
    .rr-slider label { white-space: nowrap; }
    .rr-trackcell { display: flex; align-items: center; gap: 10px; }
    .rr-trackcell .rf-track { width: 120px; flex: none; }
    .rr-barlbl b { color: var(--rf-on-background); }
    .rr-tx { color: inherit; }
    .rr-tx:hover .rr-tx-title { text-decoration: underline; }
    .rr-kv { display: flex; flex-wrap: wrap; gap: 14px 28px; }
    .rr-kv .l { font-size: 12px; font-weight: 700; color: var(--rf-on-surface-variant); margin-bottom: 2px; }
    .rr-kv .v { font-size: 16px; font-weight: 700; }
    @media (max-width: 560px) { .rr-trackcell .rf-track { width: 72px; } }
</style>
@endpush

<div class="rf-page">

    <div class="rf-sub"><a class="rf-link" href="{{ route('denah', $roomQuery) }}">Denah</a> › <a class="rf-link" href="{{ route('ruang') }}">Ruang</a></div>

    @if ($room->archived)
        <x-rf.banner tone="info" role="status">Ruang ini sudah diarsipkan. Riwayatnya tetap bisa dilihat; pulihkan dari halaman Ruang bila ingin dipakai lagi.</x-rf.banner>
    @endif

    {{-- Kepala ruang --}}
    <section class="rf-card {{ $slotClass }}" style="display:flex; align-items:center; gap:28px; flex-wrap:wrap" aria-labelledby="rr-judul">
        <x-rf.ring :percent="$percent ?? 0" :warn="$warn" :size="104" :text="$percent === null ? '—' : $percent.'%'"
            :label="$percent === null ? $room->name.' belum punya jatah bulan ini' : $room->name.' terpakai '.$percent.' persen dari jatah'" />
        <div style="flex:1 1 260px; min-width:0">
            <div class="rf-row" style="gap:10px; flex-wrap:wrap">
                <x-rf.room-icon :room="$room" />
                <h1 class="rf-h1" id="rr-judul" style="font-size:28px">{{ $room->name }}</h1>
                <x-rf.pill :tone="$roomMonth->statusTone()" :icon="$roomMonth->status() === 'berjalan' || $roomMonth->status() === 'belum_tercapai' ? 'clock' : null">{{ $roomMonth->statusLabel() }}</x-rf.pill>
            </div>
            <p style="margin-top:10px; font-size:15px; color:var(--rf-on-surface-variant)">
                Terpakai <b style="color:var(--rf-on-background)">{{ Rupiah::format($roomMonth->spentMinor) }}</b>
                dari jatah <b style="color:var(--rf-on-background)">{{ Rupiah::format($roomMonth->allocatedMinor) }}</b>
            </p>
            <p class="rf-sub" style="margin-top:4px">{{ implode(' · ', $detail) }}</p>
        </div>
        <div class="rf-row" style="gap:10px">
            @if ($room->allocation_percent)
                <x-rf.pill icon="">{{ $room->allocation_percent }}% dari alokasi</x-rf.pill>
            @endif
            <a class="rf-btn tonal sm" href="{{ route('aturan-alokasi') }}">Atur aturan</a>
            <a class="rf-btn ghost sm" href="{{ route('kelola', ['tab' => 'kategori']) }}"><x-rf.icon name="plus" />Pos</a>
        </div>
    </section>

    <div class="rf-split">

        <div class="a" style="display:flex; flex-direction:column; gap:20px">

            {{-- Laju belanja --}}
            <section class="rf-card {{ $slotClass }}" aria-labelledby="rr-laju">
                <div class="rf-card-head">
                    <div>
                        <h2 class="rf-h2" id="rr-laju">Laju belanja bulan ini</h2>
                        <p class="rf-sub" style="margin-top:4px">Pengeluaran kumulatif {{ $room->name }} dibanding laju jatah harian.</p>
                    </div>
                </div>

                @if ($chart['reason'] === 'tanpa_jatah')
                    <p class="rf-sub">Ruang ini belum punya jatah di {{ \App\Support\Period::label($month) }}, jadi laju belanjanya belum bisa dibandingkan. Alirkan rezeki ke ruang ini lebih dulu.</p>
                @elseif ($chart['reason'] === 'belum_mulai')
                    <p class="rf-sub">{{ \App\Support\Period::label($month) }} belum dimulai, jadi belum ada laju belanja untuk dilihat.</p>
                @else
                    <div x-data="{ day: {{ $chart['last'] }}, rows: @js($chart['rows']), get r() { return this.rows[this.day - 1]; } }">
                        <svg class="rr-chart" viewBox="0 0 680 290" role="img" aria-label="{{ $chart['aria'] }}">
                            @foreach ($chart['ticks'] as $tick)
                                <line class="ch-grid" x1="{{ $chart['left'] }}" y1="{{ $tick['y'] }}" x2="{{ $chart['right'] }}" y2="{{ $tick['y'] }}"/>
                                <text class="ch-axis" x="48" y="{{ $tick['text_y'] }}" text-anchor="end">{{ $tick['label'] }}</text>
                            @endforeach
                            @foreach ($chart['x_labels'] as $label)
                                <text class="ch-axis" x="{{ $label['x'] }}" y="272" text-anchor="middle">{{ $label['label'] }}</text>
                            @endforeach
                            <line class="ch-ref" x1="{{ $chart['left'] }}" y1="{{ $chart['allocated_y'] }}" x2="{{ $chart['right'] }}" y2="{{ $chart['allocated_y'] }}"/>
                            <text class="ch-lbl-mut" x="{{ $chart['right'] }}" y="{{ $chart['allocated_label_y'] }}" text-anchor="end">{{ $chart['allocated_label'] }}</text>
                            <polyline class="ch-ref" fill="none" points="{{ $chart['pace_from'] }} {{ $chart['pace_to'] }}"/>
                            <text class="ch-lbl-mut" x="{{ $chart['right'] - 10 }}" y="{{ $chart['pace_label_y'] }}" text-anchor="end">Laju jatah</text>
                            <polygon class="ch-area" points="{{ $chart['area'] }}"/>
                            <polyline class="ch-line" points="{{ $chart['line'] }}"/>
                            <line class="ch-cross" x1="{{ $chart['last_row']['x'] }}" :x1="r.x" y1="{{ $chart['top'] }}" x2="{{ $chart['last_row']['x'] }}" :x2="r.x" y2="{{ $chart['bottom'] }}"/>
                            <circle class="rr-pace-dot" cx="{{ $chart['last_row']['x'] }}" :cx="r.x" cy="{{ $chart['last_row']['py'] }}" :cy="r.py" r="4"/>
                            <circle class="ch-dot" cx="{{ $chart['last_row']['x'] }}" :cx="r.x" cy="{{ $chart['last_row']['cy'] }}" :cy="r.cy" r="5"/>
                        </svg>

                        @if ($chart['last'] > 1)
                            <div class="rr-slider">
                                <label for="rr-hari" class="rf-sub">Hari ke-<span x-text="day">{{ $chart['last'] }}</span></label>
                                <input id="rr-hari" class="rf-range" type="range" min="1" max="{{ $chart['last'] }}" step="1" x-model.number="day" value="{{ $chart['last'] }}" aria-label="Pilih hari untuk melihat nilai">
                            </div>
                        @endif

                        <div class="rf-readout" style="margin-top:12px" aria-live="polite">
                            <span><b x-text="r.label">{{ $chart['last_row']['label'] }}</b> · terpakai <b x-text="r.spent">{{ $chart['last_row']['spent'] }}</b> · laju jatah <span x-text="r.pace">{{ $chart['last_row']['pace'] }}</span> · <span x-text="r.diff">{{ $chart['last_row']['diff'] }}</span></span>
                        </div>
                    </div>

                    <div class="rf-legend" style="margin-top:12px">
                        <span><i class="rf-key"></i>Terpakai</span>
                        <span><i class="rf-key gray"></i>Laju jatah dan jatah</span>
                    </div>
                @endif
            </section>

            {{-- Pos --}}
            <section class="rf-card {{ $slotClass }}" aria-labelledby="rr-pos">
                <div class="rf-card-head"><h2 class="rf-h2" id="rr-pos">Pos</h2><span class="rf-sub">Jatah pos mengikuti bobot, dihitung dari jatah ruang</span></div>

                @if ($categories->isEmpty())
                    <p class="rf-sub">Belum ada pos di ruang ini.</p>
                @else
                    <div class="rf-scroll">
                        <table class="rf-table" style="min-width:600px">
                            <thead>
                                <tr><th>Pos</th><th class="num">Bobot</th><th class="num">Jatah pos</th><th class="num">Terpakai</th><th style="width:200px">Terpakai dari jatah pos</th></tr>
                            </thead>
                            <tbody>
                                @foreach ($categories as $pos)
                                    @php
                                        $category = $pos['category'];
                                        $untracked = $category->is_system && $category->name === \App\Models\Category::UNTRACKED;
                                        $posPercent = $pos['percent'];
                                    @endphp
                                    <tr>
                                        @if ($untracked)
                                            <td><span style="font-weight:700">{{ $category->name }}</span> <span class="rf-sub">(pos sistem)</span></td>
                                            <td class="num">—</td><td class="num">—</td>
                                            <td class="num">{{ Rupiah::plain($pos['spent_minor']) }}</td>
                                            <td>
                                                @if ($pos['count'] > 0)
                                                    <a class="rf-link" href="{{ route('transaksi', ['tab' => 'tak', 'ruang' => $room->id]) }}">Lihat {{ $pos['count'] }} transaksi</a>
                                                @else
                                                    <span class="rf-sub">Belum ada</span>
                                                @endif
                                            </td>
                                        @else
                                            <td style="font-weight:700">{{ $category->name }}@if ($category->archived) <span class="rf-sub">(diarsipkan)</span>@endif</td>
                                            <td class="num">{{ $category->weight ?? '—' }}</td>
                                            <td class="num">{{ Rupiah::plain($pos['budget_minor']) }}</td>
                                            <td class="num">{{ Rupiah::plain($pos['spent_minor']) }}</td>
                                            <td>
                                                <div class="rr-trackcell">
                                                    <x-rf.track :percent="$posPercent ?? 0" />
                                                    @if ($posPercent !== null && $posPercent > 100)
                                                        <x-rf.pill tone="warn">{{ $posPercent }}%</x-rf.pill>
                                                    @else
                                                        <span class="rf-money">{{ $posPercent === null ? '—' : $posPercent.'%' }}</span>
                                                    @endif
                                                </div>
                                            </td>
                                        @endif
                                    </tr>
                                @endforeach
                            </tbody>
                            <tfoot>
                                <tr>
                                    <td>Total</td>
                                    <td class="num">{{ $weightTotal > 0 ? $weightTotal : '—' }}</td>
                                    <td class="num">{{ Rupiah::plain($roomMonth->allocatedMinor) }}</td>
                                    <td class="num">{{ Rupiah::plain($roomMonth->spentMinor) }}</td>
                                    <td class="rf-money">{{ $percent === null ? '—' : $percent.'%' }}</td>
                                </tr>
                            </tfoot>
                        </table>
                    </div>
                @endif
            </section>

        </div>

        <div class="b" style="display:flex; flex-direction:column; gap:20px">

            {{-- Riwayat 6 bulan --}}
            <section class="rf-card {{ $slotClass }}" aria-labelledby="rr-riwayat">
                <div class="rf-card-head"><h2 class="rf-h2" id="rr-riwayat">Riwayat 6 bulan</h2></div>
                <div class="rf-bars">
                    @foreach ($history['bars'] as $bar)
                        <div class="rf-barcol" role="img" aria-label="{{ $bar['full_name'] }}: terpakai {{ $bar['spent'] }} dari jatah {{ $bar['allocated'] }}{{ $bar['percent'] === null ? '' : ', '.$bar['percent'].' persen' }}">
                            <div class="rf-plot" aria-hidden="true">
                                <div @class(['rf-bar', 'run' => $bar['running']]) style="height: {{ $bar['bar_px'] }}px"></div>
                                @if ($bar['tick_px'] !== null)
                                    <div class="rf-tick" style="bottom: {{ $bar['tick_px'] }}px"></div>
                                @endif
                            </div>
                            <div class="rf-barlbl rr-barlbl" aria-hidden="true">{{ $bar['name'] }}<br>@if ($bar['percent'] === null)—@elseif ($bar['over'])<b>{{ $bar['percent'] }}%</b>@else{{ $bar['percent'] }}%@endif</div>
                        </div>
                    @endforeach
                </div>
                <div class="rf-legend" style="margin-top:12px">
                    <span><i class="rf-dot"></i>Terpakai</span>
                    <span><i class="rf-key dark"></i>Jatah bulan itu</span>
                </div>
                <p class="rf-sub" style="margin-top:10px">{{ $history['summary'] }}</p>
            </section>

            {{-- Transaksi terbaru --}}
            <section class="rf-card" aria-labelledby="rr-terbaru">
                <div class="rf-card-head"><h2 class="rf-h2" id="rr-terbaru">Transaksi terbaru</h2><a class="rf-link" href="{{ route('transaksi', ['ruang' => $room->id]) }}">Lihat semua →</a></div>
                <div>
                    @forelse ($transactions as $t)
                        <a class="rf-rowline rr-tx" href="{{ route('transaksi.detail', $t) }}">
                            <div class="rf-grow">
                                <div class="rr-tx-title" style="font-weight:700">{{ $t->note ?? $t->category }}</div>
                                <div class="rf-sub">{{ $t->occurred_on->day }} {{ Ruang::shortMonth($t->occurred_on) }} · {{ $t->category }}</div>
                            </div>
                            <div class="rf-money">{{ Rupiah::signed($t->amount_minor, 'pengeluaran') }}</div>
                        </a>
                    @empty
                        <p class="rf-sub">Belum ada transaksi di ruang ini pada {{ \App\Support\Period::label($month) }}.</p>
                    @endforelse
                </div>
            </section>

            {{-- Peran (Pro) --}}
            @if ($room->type !== 'menunaikan')
                <section class="rf-card" aria-labelledby="rr-peran">
                    <div class="rf-card-head" style="margin-bottom:8px">
                        <h2 class="rf-h2" id="rr-peran">Peran</h2>
                        @unless ($canUseRoles)<span class="rf-tagpro" style="margin-left:0">PRO</span>@endunless
                    </div>

                    @if ($roleError)
                        <p class="rf-error" role="alert" style="margin-bottom:12px"><x-rf.icon name="warn" />{{ $roleError }}</p>
                    @endif

                    @if (! $role && ! $showRoleForm)
                        <p class="rf-sub" style="margin-bottom:14px">Modul opsional: Trader (batas risiko per trade) atau Investor (jadwal DCA bulanan). Alat bantu disiplin, bukan saran investasi atau trading.</p>
                        <div class="rf-actions">
                            <button type="button" wire:click="startPickRole('trader')" class="rf-btn tonal sm">Trader @unless ($canUseRoles) (Pro) @endunless</button>
                            <button type="button" wire:click="startPickRole('investor')" class="rf-btn tonal sm">Investor @unless ($canUseRoles) (Pro) @endunless</button>
                        </div>
                    @endif

                    @if ($showRoleForm)
                        <form wire:submit="saveRole" class="rf-form-grid">
                            @if ($roleKind === 'trader')
                                <div class="rf-field full">
                                    <label for="role-capital">Modal trading</label>
                                    <input type="number" id="role-capital" class="rf-input" wire:model="capital" min="1" autofocus>
                                </div>
                                <div class="rf-field full">
                                    <label for="role-risk">Risiko per trade (%)</label>
                                    <input type="number" id="role-risk" class="rf-input" wire:model="riskPercent" min="0.25" max="10" step="0.25">
                                </div>
                            @else
                                <div class="rf-field full">
                                    <label for="role-dca-amount">Nominal DCA bulanan</label>
                                    <input type="number" id="role-dca-amount" class="rf-input" wire:model="dcaAmount" min="1" autofocus>
                                </div>
                                <div class="rf-field full">
                                    <label for="role-dca-day">Tanggal tiap bulan (1–28)</label>
                                    <input type="number" id="role-dca-day" class="rf-input" wire:model="dcaDay" min="1" max="28">
                                </div>
                                <div class="rf-field full">
                                    <label for="role-dca-account">Akun sumber</label>
                                    <select id="role-dca-account" class="rf-input" wire:model="dcaAccountId">
                                        @foreach ($accounts as $account)
                                            <option value="{{ $account->id }}">{{ $account->name }}</option>
                                        @endforeach
                                    </select>
                                </div>
                                <div class="rf-field full">
                                    <label for="role-dca-category">Kategori</label>
                                    <select id="role-dca-category" class="rf-input" wire:model="dcaCategoryId">
                                        @foreach ($roomCategories as $category)
                                            <option value="{{ $category->id }}">{{ $category->name }}</option>
                                        @endforeach
                                    </select>
                                </div>
                            @endif
                            <div class="rf-actions full">
                                <button type="submit" class="rf-btn primary">Simpan</button>
                                <button type="button" wire:click="cancelRoleForm" class="rf-btn tonal">Batal</button>
                            </div>
                        </form>
                    @endif

                    @if ($role && ! $showRoleForm)
                        @if ($role->kind === 'trader')
                            <div class="rr-kv" style="margin-bottom:14px">
                                <div><div class="l">Modal</div><div class="v">{{ Rupiah::format($role->capital_minor) }}</div></div>
                                <div><div class="l">Risiko per trade</div><div class="v">{{ $role->risk_bp / 100 }}%</div></div>
                                <div><div class="l">Batas rupiah per trade</div><div class="v">{{ Rupiah::format($role->maxRiskPerTradeMinor()) }}</div></div>
                            </div>
                        @else
                            @php $dcaStatus = $role->dcaStatusOn($today); @endphp
                            <div class="rf-row" style="justify-content:space-between; gap:16px; margin-bottom:14px">
                                <div>
                                    <div style="font-weight:700; font-size:16px">{{ Rupiah::format($role->dca_amount_minor) }} <span class="rf-sub" style="font-weight:400">tiap tanggal {{ $role->dca_day_of_month }}</span></div>
                                    <div class="rf-sub" style="margin-top:2px">{{ $role->dcaAccount->name }} · {{ $role->dcaCategory->name }}</div>
                                </div>
                                <x-rf.pill :tone="$dcaStatus === 'selesai' ? 'good' : ($dcaStatus === 'jatuh_tempo' ? 'warn' : 'neutral')" :icon="$dcaStatus === 'akan_datang' ? 'clock' : null">
                                    {{ ['selesai' => 'Selesai', 'jatuh_tempo' => 'Jatuh tempo', 'akan_datang' => 'Akan datang'][$dcaStatus] }}
                                </x-rf.pill>
                                @if ($dcaStatus === 'jatuh_tempo')
                                    <button type="button" wire:click="recordDcaNow" class="rf-btn primary sm">Catat sekarang</button>
                                @endif
                            </div>
                        @endif
                        <button type="button" wire:click="removeRole" wire:confirm="Lepas peran ini? Transaksi yang sudah tercatat tidak berubah." class="rf-btn ghost sm">Lepas peran</button>
                    @endif
                </section>
            @endif

        </div>

    </div>

</div>
