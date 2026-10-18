@php
    use Illuminate\Support\Carbon;

    $levelColors = ['var(--rf-surface-container-highest)', '#cfe8db', '#8fc7a8', '#4d9d6f', '#275c3f'];
@endphp

<div style="display:flex; flex-direction:column; gap:22px; max-width:880px; margin:0 auto; width:100%;">

    <div><a href="{{ route('laporan') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Laporan</a></div>

    <h1 class="rf-serif" style="font-size:26px;">Heatmap pencatatan</h1>

    @if (! $canUseReports)
        <div class="rf-card">
            <p style="font-size:14px; color:var(--rf-on-surface-variant);">Heatmap pencatatan adalah fitur Pro. Upgrade untuk melihat kebiasaan mencatat setahun terakhir, gaya kontribusi GitHub.</p>
        </div>
    @else
        <div class="rf-card" style="display:flex; gap:28px; flex-wrap:wrap;">
            <div>
                <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-bottom:4px;">Hari dengan catatan</div>
                <div style="font-weight:700; font-size:20px;">{{ $grid['totalDays'] }} <span style="font-weight:400; font-size:13px; color:var(--rf-on-surface-variant);">dari setahun terakhir</span></div>
            </div>
            <div>
                <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-bottom:4px;">Beruntun sampai hari ini</div>
                <div style="font-weight:700; font-size:20px;">{{ $grid['streak'] }} <span style="font-weight:400; font-size:13px; color:var(--rf-on-surface-variant);">hari</span></div>
            </div>
        </div>

        <div class="rf-card" style="overflow-x:auto;">
            <div style="display:inline-flex; flex-direction:column; gap:4px; min-width:760px;">
                <div style="display:flex; gap:3px; padding-left:28px;">
                    @foreach ($grid['weeks'] as $weekIndex => $week)
                        @php $label = collect($grid['monthLabels'])->firstWhere('index', $weekIndex); @endphp
                        <div style="width:11px; font-size:10px; color:var(--rf-on-surface-variant); flex-shrink:0;">{{ $label['label'] ?? '' }}</div>
                    @endforeach
                </div>
                <div style="display:flex; gap:3px;">
                    <div style="display:flex; flex-direction:column; gap:3px; width:24px; flex-shrink:0;">
                        @foreach (['Min', '', 'Sel', '', 'Kam', '', 'Sab'] as $dayLabel)
                            <div style="height:11px; font-size:9px; color:var(--rf-on-surface-variant);">{{ $dayLabel }}</div>
                        @endforeach
                    </div>
                    @foreach ($grid['weeks'] as $week)
                        <div style="display:flex; flex-direction:column; gap:3px;">
                            @foreach ($week as $day)
                                @if ($day['inFuture'])
                                    <div style="width:11px; height:11px;"></div>
                                @else
                                    <div
                                        title="{{ Carbon::parse($day['date'])->translatedFormat('j F Y') }} · {{ $day['count'] }} transaksi"
                                        style="width:11px; height:11px; border-radius:2px; background:{{ $levelColors[$day['level']] }};"
                                    ></div>
                                @endif
                            @endforeach
                        </div>
                    @endforeach
                </div>
            </div>
        </div>

        <p style="font-size:12.5px; color:var(--rf-on-surface-variant);">Makin gelap, makin banyak transaksi dicatat hari itu (jenis apa pun). Arahkan kursor ke kotak untuk melihat jumlahnya.</p>
    @endif

</div>
