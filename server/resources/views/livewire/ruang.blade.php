@php
    use App\Support\Rupiah;

    $roomColors = [1 => '#3b6fa8', 2 => '#c4653a', 3 => '#2a9a80', 4 => '#af6dec', 5 => '#a04177', 6 => '#099acf', 7 => '#998214', 8 => '#de4f74'];
    $color = $roomColors[$room->slot] ?? '#727874';
    $percent = $roomMonth->percent() ?? 0;
    $status = $roomMonth->status();
    $circumference = 263.76; // r=42
    $dash = round($circumference * min($percent, 100) / 100, 1);
    $gap = round($circumference - $dash, 1);
    $ringColor = $status === 'perlu_perhatian' ? 'var(--rf-status-warning)' : $color;
@endphp

<div style="display:flex; flex-direction:column; gap:28px; max-width:880px; margin:0 auto; width:100%;">

    <div><a href="{{ route('denah') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Denah</a></div>

    <div class="rf-card" style="display:flex; align-items:center; gap:28px; flex-wrap:wrap;">
        <svg width="104" height="104" viewBox="0 0 104 104" style="transform:rotate(-90deg); flex-shrink:0;" aria-hidden="true">
            <circle cx="52" cy="52" r="42" fill="none" stroke="var(--rf-surface-container-highest)" stroke-width="11"></circle>
            <circle cx="52" cy="52" r="42" fill="none" stroke="{{ $ringColor }}" stroke-width="11" stroke-linecap="round" stroke-dasharray="{{ $dash }} {{ $gap }}"></circle>
            <text x="52" y="52" transform="rotate(90 52 52)" text-anchor="middle" dominant-baseline="middle" font-family="Manrope, sans-serif" font-size="22" font-weight="700" fill="#1b1c1b">{{ $percent }}%</text>
        </svg>
        <div style="flex:1; min-width:220px;">
            <div style="display:flex; align-items:center; gap:10px; margin-bottom:8px;">
                <div style="width:32px; height:32px; border-radius:9px; background:{{ $color }}; color:#fff; display:flex; align-items:center; justify-content:center; font-weight:700;">{{ mb_substr($room->name, 0, 1) }}</div>
                <h1 class="rf-serif" style="font-size:26px;">{{ $room->name }}</h1>
                <span class="rf-pill {{ $roomMonth->statusTone() }}">{{ $roomMonth->statusLabel() }}</span>
            </div>
            <div style="font-size:15px; color:var(--rf-on-surface-variant);">
                Terpakai <span style="font-weight:700; color:var(--rf-on-background);">{{ Rupiah::format($roomMonth->spentMinor) }}</span>
                dari jatah <span style="font-weight:700; color:var(--rf-on-background);">{{ Rupiah::format($roomMonth->allocatedMinor) }}</span>
            </div>
        </div>
        @if ($room->allocation_percent)
            <span class="rf-pill neutral">{{ $room->allocation_percent }}% dari alokasi</span>
        @endif
    </div>

    <div class="rf-card">
        <h2 class="rf-serif" style="font-size:18px; margin-bottom:6px;">Pos</h2>

        @forelse ($categories as $pos)
            @php $posPercent = $pos['percent']; @endphp
            <div style="padding:13px 0; border-bottom:1px solid var(--rf-outline-variant);" aria-label="{{ $pos['category']->name }}, {{ Rupiah::format($pos['spent_minor']) }}, {{ $posPercent === null ? 'belum ada jatah' : $posPercent.'%' }}">
                <div style="display:flex; align-items:baseline; gap:12px;">
                    <span style="flex:1; min-width:0; font-weight:600; font-size:14px;">{{ $pos['category']->name }}</span>
                    <span style="font-size:13px; color:var(--rf-on-surface-variant);">{{ Rupiah::format($pos['spent_minor']) }}</span>
                    <span style="font-weight:700; font-size:14px; min-width:44px; text-align:right;">{{ $posPercent === null ? '—' : $posPercent.'%' }}</span>
                </div>
                <div style="margin-top:8px; height:8px; border-radius:999px; overflow:hidden; background:color-mix(in srgb, {{ $color }} 20%, transparent);" aria-hidden="true">
                    <div style="width:{{ min($posPercent ?? 0, 100) }}%; height:100%; background:{{ $color }};"></div>
                </div>
            </div>
        @empty
            <p style="padding:16px 0; color:var(--rf-on-surface-variant); font-size:14px;">Belum ada pos di ruang ini.</p>
        @endforelse
    </div>

    <div class="rf-card">
        <div style="display:flex; align-items:baseline; justify-content:space-between; margin-bottom:6px;">
            <h2 class="rf-serif" style="font-size:18px;">Transaksi terbaru</h2>
            <a href="{{ route('transaksi') }}" style="font-size:13px; font-weight:600;">Lihat semua →</a>
        </div>

        @forelse ($transactions as $t)
            <a href="{{ route('transaksi.detail', $t) }}" style="display:flex; align-items:center; justify-content:space-between; gap:14px; padding:13px 0; border-bottom:1px solid var(--rf-outline-variant); color:inherit; text-decoration:none;">
                <div>
                    <div style="font-weight:600; font-size:14px;">{{ $t->note ?? $t->category }}</div>
                    <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-top:2px;">{{ $t->occurred_on->translatedFormat('j M') }} · {{ $t->category }}</div>
                </div>
                <div style="font-weight:700; font-size:14px;">− {{ Rupiah::format($t->amount_minor) }}</div>
            </a>
        @empty
            <p style="padding:16px 0; color:var(--rf-on-surface-variant); font-size:14px;">Belum ada transaksi di ruang ini.</p>
        @endforelse
    </div>

    @if ($room->type !== 'menunaikan')
        <div class="rf-card">
            <h2 class="rf-serif" style="font-size:18px; margin-bottom:14px;">Peran</h2>

            @if ($roleError)
                <p class="error-text">{{ $roleError }}</p>
            @endif

            @if (! $role && ! $showRoleForm)
                <p style="font-size:13px; color:var(--rf-on-surface-variant); margin-bottom:14px;">Modul opsional: Trader (batas risiko per trade) atau Investor (jadwal DCA bulanan). Alat bantu disiplin, bukan saran investasi atau trading.</p>
                <div style="display:flex; gap:10px;">
                    <button type="button" wire:click="startPickRole('trader')" class="rf-btn tonal" style="flex:1;">Trader @unless ($canUseRoles) (Pro) @endunless</button>
                    <button type="button" wire:click="startPickRole('investor')" class="rf-btn tonal" style="flex:1;">Investor @unless ($canUseRoles) (Pro) @endunless</button>
                </div>
            @endif

            @if ($showRoleForm)
                <form wire:submit="saveRole" style="display:flex; flex-direction:column; gap:4px;">
                    @if ($roleKind === 'trader')
                        <div class="rf-field">
                            <label for="role-capital">Modal trading</label>
                            <input type="number" id="role-capital" wire:model="capital" min="1" autofocus>
                        </div>
                        <div class="rf-field">
                            <label for="role-risk">Risiko per trade (%)</label>
                            <input type="number" id="role-risk" wire:model="riskPercent" min="0.25" max="10" step="0.25">
                        </div>
                    @else
                        <div class="rf-field">
                            <label for="role-dca-amount">Nominal DCA bulanan</label>
                            <input type="number" id="role-dca-amount" wire:model="dcaAmount" min="1" autofocus>
                        </div>
                        <div class="rf-field">
                            <label for="role-dca-day">Tanggal tiap bulan (1–28)</label>
                            <input type="number" id="role-dca-day" wire:model="dcaDay" min="1" max="28">
                        </div>
                        <div class="rf-field">
                            <label for="role-dca-account">Akun sumber</label>
                            <select id="role-dca-account" wire:model="dcaAccountId">
                                @foreach ($accounts as $account)
                                    <option value="{{ $account->id }}">{{ $account->name }}</option>
                                @endforeach
                            </select>
                        </div>
                        <div class="rf-field">
                            <label for="role-dca-category">Kategori</label>
                            <select id="role-dca-category" wire:model="dcaCategoryId">
                                @foreach ($roomCategories as $category)
                                    <option value="{{ $category->id }}">{{ $category->name }}</option>
                                @endforeach
                            </select>
                        </div>
                    @endif
                    <div style="display:flex; gap:12px; margin-top:8px;">
                        <button type="submit" class="rf-btn primary">Simpan</button>
                        <button type="button" wire:click="cancelRoleForm" class="rf-btn tonal">Batal</button>
                    </div>
                </form>
            @endif

            @if ($role && ! $showRoleForm)
                @if ($role->kind === 'trader')
                    <div style="display:flex; flex-wrap:wrap; gap:20px; margin-bottom:14px;">
                        <div>
                            <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-bottom:4px;">Modal</div>
                            <div style="font-weight:700; font-size:16px;">{{ Rupiah::format($role->capital_minor) }}</div>
                        </div>
                        <div>
                            <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-bottom:4px;">Risiko per trade</div>
                            <div style="font-weight:700; font-size:16px;">{{ $role->risk_bp / 100 }}%</div>
                        </div>
                        <div>
                            <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-bottom:4px;">Batas rupiah per trade</div>
                            <div style="font-weight:700; font-size:16px;">{{ Rupiah::format($role->maxRiskPerTradeMinor()) }}</div>
                        </div>
                    </div>
                @else
                    @php $dcaStatus = $role->dcaStatusOn($today); @endphp
                    <div style="display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap; margin-bottom:14px;">
                        <div>
                            <div style="font-weight:700; font-size:16px;">{{ Rupiah::format($role->dca_amount_minor) }} <span style="font-weight:400; font-size:13px; color:var(--rf-on-surface-variant);">tiap tanggal {{ $role->dca_day_of_month }}</span></div>
                            <div style="font-size:13px; color:var(--rf-on-surface-variant); margin-top:2px;">{{ $role->dcaAccount->name }} · {{ $role->dcaCategory->name }}</div>
                        </div>
                        <span class="rf-pill {{ $dcaStatus === 'selesai' ? 'good' : ($dcaStatus === 'jatuh_tempo' ? 'warn' : 'neutral') }}">
                            {{ ['selesai' => 'Selesai', 'jatuh_tempo' => 'Jatuh tempo', 'akan_datang' => 'Akan datang'][$dcaStatus] }}
                        </span>
                        @if ($dcaStatus === 'jatuh_tempo')
                            <button type="button" wire:click="recordDcaNow" class="rf-btn primary" style="padding:8px 14px;">Catat sekarang</button>
                        @endif
                    </div>
                @endif
                <button type="button" wire:click="removeRole" wire:confirm="Lepas peran ini? Transaksi yang sudah tercatat tidak berubah." class="rf-btn tonal">Lepas peran</button>
            @endif
        </div>
    @endif

    <div>
        <a href="{{ route('aturan-alokasi') }}" class="rf-btn tonal">Atur aturan</a>
    </div>

</div>
