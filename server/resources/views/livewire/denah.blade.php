@php
    use App\Support\Rupiah;

    $roomColors = [1 => '#3b6fa8', 2 => '#c4653a', 3 => '#2a9a80', 4 => '#af6dec', 5 => '#a04177', 6 => '#099acf', 7 => '#998214', 8 => '#de4f74'];
@endphp

<div style="display:flex; flex-direction:column; gap:32px;">

    <div>
        <div style="font-size:13px; color:var(--rf-on-surface-variant); margin-bottom:4px;">{{ $dateLabel }}</div>
        <h1 class="rf-serif" style="font-size:32px;">Selamat datang, {{ auth()->user()->name }}</h1>
    </div>

    @if ($recurringBanner)
        <div class="rf-card" style="padding:14px 20px; font-size:13.5px;">{{ $recurringBanner }}</div>
    @endif

    @if ($summary)
        <div class="rf-card" style="display:flex; flex-direction:column; gap:18px;">
            <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:16px; flex-wrap:wrap;">
                <div>
                    <div style="font-size:12px; font-weight:700; letter-spacing:0.04em; text-transform:uppercase; color:var(--rf-on-surface-variant); margin-bottom:8px;">Rezeki bulan ini</div>
                    <div style="font-size:40px; font-weight:700;">{{ Rupiah::format($summary->income_minor) }}</div>
                </div>
            </div>

            <div>
                <div style="display:flex; height:10px; border-radius:999px; overflow:hidden; background:var(--rf-surface-container-highest);">
                    @foreach ($rooms as $room)
                        @continue(!$room->allocation_percent)
                        <div style="width:{{ $room->allocation_percent }}%; background:{{ $roomColors[$room->slot] ?? '#727874' }};" title="{{ $room->name }}"></div>
                    @endforeach
                </div>
                <div style="display:flex; gap:20px; margin-top:12px; flex-wrap:wrap; font-size:13px; color:var(--rf-on-surface-variant);">
                    @foreach ($rooms as $room)
                        <span style="display:inline-flex; align-items:center; gap:6px;">
                            <i style="width:10px; height:10px; border-radius:3px; background:{{ $roomColors[$room->slot] ?? '#727874' }}; display:inline-block;"></i>{{ $room->name }}
                        </span>
                    @endforeach
                </div>
            </div>

            <div style="font-size:14px; color:var(--rf-on-surface-variant);">
                Belum dialirkan: <span style="font-weight:700; color:var(--rf-on-background);">{{ Rupiah::format($summary->unallocated_minor) }}</span>
            </div>
        </div>
    @endif

    <div style="display:flex; flex-direction:column; gap:16px;">
        <div style="display:flex; align-items:baseline; justify-content:space-between;">
            <h2 class="rf-serif" style="font-size:22px;">Ruang</h2>
            <a href="{{ route('ruang') }}" style="font-size:13px; font-weight:600;">Lihat detail ruang →</a>
        </div>

        <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(240px, 1fr)); gap:16px;">
            @foreach ($rooms as $room)
                @php
                    $percent = $room->usedPercent();
                    $circumference = 163.36;
                    $dash = round($circumference * min($percent, 100) / 100, 1);
                    $gap = round($circumference - $dash, 1);
                    $status = $room->status();
                    $color = $roomColors[$room->slot] ?? '#727874';
                    $ringColor = $status === 'perlu_perhatian' ? 'var(--rf-status-warning)' : $color;
                @endphp
                <a href="{{ route('ruang.detail', $room) }}" style="display:block; background:var(--rf-surface-lowest); border:1px solid var(--rf-outline-variant); border-radius:16px; padding:20px; color:inherit;">
                    <div style="display:flex; align-items:center; gap:10px; margin-bottom:14px;">
                        <div style="width:34px; height:34px; border-radius:10px; background:{{ $color }}; color:#fff; display:flex; align-items:center; justify-content:center; font-weight:700;">{{ mb_substr($room->name, 0, 1) }}</div>
                        <span style="font-weight:700; font-size:15px;">{{ $room->name }}</span>
                    </div>
                    <div style="display:flex; align-items:center; gap:16px;">
                        <svg width="64" height="64" viewBox="0 0 64 64" style="transform:rotate(-90deg); flex-shrink:0;" aria-hidden="true">
                            <circle cx="32" cy="32" r="26" fill="none" stroke="var(--rf-surface-container-highest)" stroke-width="7"></circle>
                            <circle cx="32" cy="32" r="26" fill="none" stroke="{{ $ringColor }}" stroke-width="7" stroke-linecap="round" stroke-dasharray="{{ $dash }} {{ $gap }}"></circle>
                        </svg>
                        <div>
                            <div style="font-size:18px; font-weight:700;">{{ $percent }}%</div>
                            <div style="font-size:12px; color:var(--rf-on-surface-variant);">
                                @if ($status === 'terpenuhi') Terpenuhi
                                @else Sisa {{ Rupiah::short($room->remainingMinor()) }}
                                @endif
                            </div>
                        </div>
                    </div>
                    <div class="rf-pill {{ $status === 'terpenuhi' ? 'good' : ($status === 'perlu_perhatian' ? 'warn' : 'neutral') }}" style="margin-top:14px;">
                        @if ($status === 'terpenuhi') Terpenuhi
                        @elseif ($status === 'perlu_perhatian') Perlu perhatian
                        @else Berjalan
                        @endif
                    </div>
                </a>
            @endforeach

            <a href="{{ route('ruang') }}" style="display:flex; align-items:center; justify-content:center; border:1.5px dashed var(--rf-outline-variant); border-radius:16px; padding:20px; min-height:148px; color:var(--rf-on-surface-variant); font-size:13px; font-weight:600;">
                + Ruang baru
            </a>
        </div>
    </div>

    @if (count($attention))
        <div class="rf-card">
            <h2 class="rf-serif" style="font-size:18px; margin-bottom:6px;">Perlu perhatian</h2>
            @foreach ($attention as $item)
                <div style="display:flex; align-items:center; justify-content:space-between; gap:14px; padding:13px 0; border-bottom:1px solid var(--rf-outline-variant);">
                    @if (!empty($item['href']))
                        <a href="{{ $item['href'] }}" style="font-size:14px; color:inherit;">{{ $item['text'] }}</a>
                    @else
                        <span style="font-size:14px;">{{ $item['text'] }}</span>
                    @endif
                    <span class="rf-pill warn">{{ $item['pill'] }}</span>
                </div>
            @endforeach
        </div>
    @endif

    <div style="display:flex; align-items:center; gap:14px; flex-wrap:wrap;">
        <a href="{{ route('transaksi') }}" class="rf-btn primary">+ Catat transaksi</a>
        <a href="{{ route('ruang') }}" class="rf-btn tonal">Lihat ruang</a>
    </div>

</div>
