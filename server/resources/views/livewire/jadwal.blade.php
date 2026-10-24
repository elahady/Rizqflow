@php
    use App\Support\Rupiah;

    $statusMap = [
        'terlambat' => ['Terlambat', 'warn', 'clock'],
        'hari_ini' => ['Hari ini', 'warn', 'clock'],
        'segera' => ['Segera', 'neutral', 'clock'],
        'mendatang' => ['Mendatang', 'neutral', ''],
        'perlu_dicek' => ['Perlu dicek', 'warn', null],
        'menunggu' => ['Menunggu dicatat', 'info', 'clock'],
        'tertahan' => ['Tertahan', 'warn', null],
        'terjadwal' => ['Terjadwal', 'neutral', ''],
    ];
@endphp

@push('styles')
<style>
    /* Jadwal: agenda per tanggal. */
    .jd-group { display: flex; gap: 16px; padding: 16px 0; border-bottom: 1px solid var(--rf-surface-container-high); }
    .jd-group:first-child { padding-top: 0; }
    .jd-group:last-child { border-bottom: 0; padding-bottom: 0; }
    .jd-group .rf-datebox { align-self: flex-start; }
    .jd-body { flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 12px; }
    .jd-date { font-size: 12.5px; font-weight: 700; color: var(--rf-on-surface-variant); }
    .jd-item { display: flex; align-items: center; gap: 8px 14px; flex-wrap: wrap; }
    .jd-main { flex: 1 1 220px; min-width: 0; }
    .jd-title { font-weight: 700; }
    a.jd-title:hover { text-decoration: underline; }
    .jd-note { margin-top: 2px; font-size: 12.5px; color: var(--rf-on-surface-variant); }
    .jd-in { color: var(--rf-income); }
</style>
@endpush

<div class="rf-page">

    <x-rf.page-head sub="Uang" title="Jadwal" lead="Tagihan dan transaksi berulang dalam satu agenda." />

    <x-jadwal.tabs active="agenda" />

    @if ($items->isEmpty())
        <section class="rf-card">
            <div class="rf-empty">
                <x-rf.icon name="calendar" />
                <b>Belum ada jadwal 30 hari ke depan</b>
                <p>Tambahkan tagihan (dibayar manual) atau transaksi berulang (dicatat otomatis) dan kemunculannya akan tampil di sini.</p>
                <div class="rf-actions">
                    <a class="rf-btn primary sm" href="{{ route('tagihan') }}"><x-rf.icon name="plus" />Tagihan</a>
                    <a class="rf-btn tonal sm" href="{{ route('transaksi.berulang') }}"><x-rf.icon name="plus" />Transaksi berulang</a>
                </div>
            </div>
        </section>
    @else
        <section class="rf-stats" aria-label="Ringkasan 30 hari ke depan">
            <div class="rf-stat">
                <div class="l">Keluar 30 hari ke depan</div>
                <div class="v">{{ Rupiah::format($outMinor) }}</div>
                <div class="d">{{ $outCount }} kemunculan{{ $lateCount > 0 ? ', termasuk yang terlambat' : '' }}</div>
            </div>
            <div class="rf-stat">
                <div class="l">Masuk 30 hari ke depan</div>
                <div class="v">{{ Rupiah::format($inMinor) }}</div>
                <div class="d">{{ $inCount }} kemunculan</div>
            </div>
            <div class="rf-stat">
                <div class="l">Tagihan terlambat</div>
                <div class="v">{{ $lateCount }}</div>
                <div class="d">
                    @if ($lateCount > 0)
                        <x-rf.icon name="warn" /> Bayar lewat tab Tagihan
                    @else
                        <x-rf.icon name="check" /> Tidak ada yang terlambat
                    @endif
                </div>
            </div>
        </section>

        <section class="rf-card" aria-labelledby="jd-agenda">
            <div class="rf-card-head"><h2 class="rf-h2" id="jd-agenda">Agenda 30 hari ke depan</h2><span class="rf-sub">Mulai {{ $today->day }} {{ \App\Livewire\Ruang::shortMonth($today) }}</span></div>

            @foreach ($groups as $group)
                <div class="jd-group">
                    <div class="rf-datebox" aria-hidden="true"><div class="d1">{{ $group['day_name'] }}</div><div class="d2">{{ $group['date']->day }}</div></div>
                    <div class="jd-body">
                        <div class="jd-date">{{ $group['label'] }}</div>
                        @foreach ($group['items'] as $item)
                            @php [$statusLabel, $statusTone, $statusIcon] = $statusMap[$item['status']]; @endphp
                            <div class="jd-item">
                                <div class="jd-main">
                                    <a class="jd-title" href="{{ $item['url'] }}">{{ $item['title'] }}</a>
                                    <div class="rf-sub">{{ $item['kind_label'] }}</div>
                                    <div class="rf-sub">{{ $item['sub'] }}</div>
                                    @if ($item['note'])
                                        <div class="jd-note">{{ $item['note'] }}</div>
                                    @endif
                                </div>
                                <span @class(['rf-money', 'jd-in' => $item['direction'] === 'masuk'])>{{ Rupiah::signed($item['amount'], $item['direction'] === 'masuk' ? 'pemasukan' : 'pengeluaran') }}</span>
                                <x-rf.pill :tone="$statusTone" :icon="$statusIcon">{{ $statusLabel }}</x-rf.pill>
                            </div>
                        @endforeach
                    </div>
                </div>
            @endforeach
        </section>

        <div class="rf-actions">
            <a class="rf-btn tonal sm" href="{{ route('tagihan') }}"><x-rf.icon name="plus" />Tambah tagihan</a>
            <a class="rf-btn ghost sm" href="{{ route('transaksi.berulang') }}"><x-rf.icon name="plus" />Tambah transaksi berulang</a>
        </div>
    @endif

</div>
