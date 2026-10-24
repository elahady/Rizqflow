@php
    use App\Support\Rupiah;
    use App\Models\Transaction;

    $user = auth()->user();
@endphp

@push('styles')
<style>
    /* Denah: kartu ruang dan baris yang bisa diklik. */
    .dn-card { display: flex; flex-direction: column; gap: 16px; color: inherit; transition: border-color var(--rf-motion-fast, 120ms) ease; }
    .dn-card:hover { border-color: var(--rf-outline); }
    .dn-card:focus-visible { outline: 2px solid var(--rf-primary); outline-offset: 2px; }
    .dn-title { font-weight: 700; color: inherit; }
    a.dn-title:hover { text-decoration: underline; }
    .dn-state { display: flex; align-items: center; flex-wrap: nowrap; justify-content: space-between; gap: 10px; }
    .dn-bold { color: var(--rf-on-background); font-weight: 700; }
    .dn-attn-link { color: var(--rf-on-background); }
    .dn-attn-link:hover { text-decoration: underline; }
</style>
@endpush

<div class="rf-page">

    <x-rf.page-head :sub="$dateLabel" :title="'Selamat datang, '.$user->name" :lead="$lead" />

    @if ($recurringBanner)
        <x-rf.banner :tone="$recurringTone" role="status">{{ $recurringBanner }}</x-rf.banner>
    @endif

    <div class="rf-split">

        {{-- Kartu rezeki --}}
        <section class="rf-card a" style="display: flex; flex-direction: column; gap: 20px" aria-labelledby="dn-rezeki">
            <div class="rf-row" style="justify-content: space-between; align-items: flex-start">
                <div>
                    <div class="rf-eyebrow" id="dn-rezeki">Rezeki {{ $isCurrent ? 'bulan ini' : $monthName }}</div>
                    <div class="rf-hero-amt" style="margin-top: 8px">{{ Rupiah::format($ledger->incomeMinor) }}</div>
                    <div class="rf-sub" style="margin-top: 6px">
                        @if ($income['caption'])
                            {{ $income['caption'] }}
                        @else
                            Belum ada rezeki yang dicatat di {{ $monthName }}.
                        @endif
                    </div>
                </div>
                <div class="rf-row" style="gap: 12px">
                    <a class="rf-link" href="{{ route('laporan', $periodQuery) }}">Lihat aliran →</a>
                    <a class="rf-btn tonal sm" href="{{ route('transaksi.rezeki') }}"><x-rf.icon name="plus" />Catat rezeki masuk</a>
                </div>
            </div>

            @if ($income['segments'])
                <x-rf.stack :segments="$income['segments']" label="Pembagian rezeki" legend />
            @endif

            <div class="rf-stats">
                <div class="rf-stat">
                    <div class="l">Terpakai</div>
                    <div class="v">{{ Rupiah::format($ledger->spentMinor) }}</div>
                    <div class="d">
                        @if ($isCurrent)
                            @if ($income['compare'])
                                @if ($income['compare']['icon'])<x-rf.icon :name="$income['compare']['icon']" />@endif
                                {{ $income['compare']['text'] }}
                            @else
                                Belum ada pengeluaran bulan ini
                            @endif
                        @else
                            {{ $ledger->expenseCount }} pengeluaran
                        @endif
                    </div>
                </div>
                <div class="rf-stat">
                    <div class="l">{{ $income['remainingLabel'] }}</div>
                    <div class="v">{{ Rupiah::format($income['remaining']) }}</div>
                    <div class="d">{{ $income['remainingNote'] }}</div>
                </div>
                <div class="rf-stat">
                    <div class="l">Belum dialirkan</div>
                    <div class="v">{{ Rupiah::format($ledger->unallocatedMinor) }}</div>
                    <div class="d">
                        @if ($ledger->unallocatedMinor > 0)
                            <a class="rf-link" href="{{ route('aturan-alokasi') }}" style="font-weight: 600">Atur di Aturan alokasi</a>
                        @elseif ($ledger->incomeMinor > 0)
                            <x-rf.icon name="check" />Semua rezeki sudah punya tujuan
                        @else
                            Belum ada rezeki yang perlu dialirkan
                        @endif
                    </div>
                </div>
            </div>
        </section>

        {{-- Jadwal 7 hari: hanya bulan berjalan --}}
        @if ($schedule !== null)
            <section class="rf-card b" aria-labelledby="dn-jadwal">
                <div class="rf-card-head">
                    <h2 class="rf-h2" id="dn-jadwal">Jadwal 7 hari</h2>
                    <a class="rf-link" href="{{ route('jadwal') }}">Buka jadwal →</a>
                </div>
                @if ($schedule['rows'])
                    <div>
                        @foreach ($schedule['rows'] as $row)
                            <div class="rf-rowline">
                                <div class="rf-datebox"><div class="d1">{{ $row['day'] }}</div><div class="d2">{{ $row['dayNumber'] }}</div></div>
                                <div class="rf-grow">
                                    <div style="font-weight: 700">{{ $row['title'] }}</div>
                                    <div class="rf-sub">{{ $row['kind'] }}</div>
                                </div>
                                <div style="text-align: right">
                                    <div class="rf-money {{ $row['income'] ? 'rf-in' : '' }}">{{ $row['amount'] }}</div>
                                    <x-rf.pill :tone="$row['pill']['tone']" :icon="$row['pill']['icon']" style="margin-top: 4px">{{ $row['pill']['label'] }}</x-rf.pill>
                                </div>
                            </div>
                        @endforeach
                    </div>
                    @if ($schedule['more'] > 0)
                        <p class="rf-sub" style="margin-top: 10px">+{{ $schedule['more'] }} lagi di <a class="rf-link" href="{{ route('jadwal') }}">Jadwal</a></p>
                    @endif
                @else
                    <div class="rf-empty">
                        <x-rf.icon name="calendar" />
                        <b>Tidak ada yang jatuh tempo</b>
                        <p>Tidak ada tagihan atau transaksi berulang dalam 7 hari ke depan.</p>
                    </div>
                @endif
            </section>
        @endif
    </div>

    {{-- Ruang --}}
    <section style="display: flex; flex-direction: column; gap: 14px" aria-labelledby="dn-ruang">
        <div class="rf-sechead">
            <h2 class="rf-h2" id="dn-ruang">Ruang</h2>
            <div class="rf-row" style="gap: 18px">
                <a class="rf-link" href="{{ route('ruang') }}">Lihat detail ruang →</a>
                <a class="rf-link" href="{{ route('ruang') }}">+ Ruang baru</a>
            </div>
        </div>

        @if ($roomCards)
            <div class="rf-grid-3">
                @foreach ($roomCards as $card)
                    @php
                        $roomMonth = $card['month'];
                        $room = $card['room'];
                        $footer = $card['footer'];
                    @endphp
                    <a class="rf-card dn-card {{ $card['colorClass'] }}" href="{{ route('ruang.detail', ['room' => $room] + $periodQuery) }}">
                        <div class="dn-state">
                            <div class="rf-row" style="gap: 10px; flex-wrap: nowrap">
                                <x-rf.room-icon :room="$room" />
                                <div>
                                    <div class="rf-h3">{{ $room->name }}</div>
                                    <div class="rf-sub">{{ $card['caption'] }}</div>
                                </div>
                            </div>
                            <x-rf.pill :tone="$roomMonth->statusTone()" :icon="$card['pillIcon']">{{ $roomMonth->statusLabel() }}</x-rf.pill>
                        </div>

                        <div class="rf-row" style="gap: 16px; flex-wrap: nowrap">
                            <x-rf.ring :percent="$card['percent']" :warn="$card['warn']" />
                            <div>
                                <div style="font-size: 20px; font-weight: 700">{{ $card['percent'] }}%</div>
                                <div class="rf-sub">{{ Rupiah::format($roomMonth->spentMinor) }} dari {{ Rupiah::format($roomMonth->allocatedMinor) }}</div>
                            </div>
                        </div>

                        @if ($card['showPace'])
                            <div>
                                <x-rf.pace :used="$card['percent']" :time="$card['paceTime']" />
                                <div class="rf-sub">{{ $card['paceText'] }}</div>
                            </div>
                        @endif

                        <div class="rf-sub">
                            @switch ($footer['kind'])
                                @case ('menunggu')
                                    Menunggu rezeki dialirkan ke ruang ini.
                                    @break
                                @case ('terpenuhi')
                                    Seluruh jatah bulan ini sudah tersalurkan.
                                    @break
                                @case ('terpenuhi_hemat')
                                    Pemakaian bulan ini tidak melewati jatah.
                                    @break
                                @case ('lewat')
                                    Melewati jatah <span class="dn-bold">{{ Rupiah::format($footer['amount']) }}</span>
                                    @break
                                @case ('sisa_aman')
                                    Sisa <span class="dn-bold">{{ Rupiah::format($footer['amount']) }}</span> · aman ≈ {{ Rupiah::format($footer['safe']) }} per hari
                                    @break
                                @default
                                    Sisa <span class="dn-bold">{{ Rupiah::format($footer['amount']) }}</span>
                            @endswitch
                        </div>
                    </a>
                @endforeach
            </div>
        @else
            <div class="rf-card">
                <div class="rf-empty">
                    <x-rf.icon name="grid" />
                    <b>Belum ada ruang</b>
                    <p>Ruang menampung bagian rezekimu: Memberi, Diri, Keluarga, atau yang kamu buat sendiri. Susun ruang dan aturan alokasinya dulu.</p>
                    <div class="rf-actions">
                        <a class="rf-btn primary sm" href="{{ route('ruang') }}">Buat ruang</a>
                        <a class="rf-btn ghost sm" href="{{ route('aturan-alokasi') }}">Atur alokasi</a>
                    </div>
                </div>
            </div>
        @endif
    </section>

    <div class="rf-split">

        {{-- Transaksi terbaru --}}
        <section class="rf-card a" aria-labelledby="dn-terbaru">
            <div class="rf-card-head">
                <h2 class="rf-h2" id="dn-terbaru">Transaksi terbaru</h2>
                <a class="rf-link" href="{{ route('transaksi', $periodQuery) }}">Buka Transaksi →</a>
            </div>
            @if ($recent->isNotEmpty())
                <div>
                    @foreach ($recent as $t)
                        @php
                            $title = $t->note ?: ($t->category ?: ($t->isIncome() ? 'Pemasukan' : 'Pengeluaran'));
                        @endphp
                        <div class="rf-rowline">
                            <div class="rf-grow">
                                <a class="dn-title" href="{{ route('transaksi.detail', $t) }}">{{ $title }}</a>
                                <div class="rf-sub">
                                    {{ $t->occurred_on->locale('id')->translatedFormat('j M') }} ·
                                    @if ($t->isIncome())
                                        @php($entries = $t->sortedAllocationEntries()->filter(fn ($e) => $e->share_bp > 0 || $e->amount_minor > 0))
                                        @if ($entries->isNotEmpty())
                                            <span class="rf-roomname">
                                                @foreach ($entries as $entry)
                                                    <i class="rf-dot {{ $entry->room && $entry->room->slot >= 1 && $entry->room->slot <= 8 ? 'c'.$entry->room->slot : 'cn' }}" aria-hidden="true"></i>
                                                @endforeach
                                                Dialirkan {{ $t->allocationLabel() ?? '' }}
                                            </span>
                                        @else
                                            <span>Belum dialirkan</span>
                                        @endif
                                    @elseif ($t->room)
                                        <x-rf.room-name :room="$t->room" :pos="$t->pos" />
                                    @else
                                        <span>Tanpa ruang</span>
                                    @endif
                                    @if ($t->account) · {{ $t->account->name }} @endif
                                </div>
                            </div>
                            <div class="rf-money {{ $t->isIncome() ? 'rf-in' : '' }}">{{ Rupiah::signed($t->amount_minor, $t->type) }}</div>
                        </div>
                    @endforeach
                </div>
            @else
                <div class="rf-empty">
                    <x-rf.icon name="list" />
                    <b>Belum ada transaksi di {{ $monthName }}</b>
                    <p>Catat pengeluaran atau rezeki masuk, dan ringkasannya muncul di sini.</p>
                </div>
            @endif
        </section>

        {{-- Perlu perhatian --}}
        <section class="rf-card b" aria-labelledby="dn-perhatian">
            <h2 class="rf-h2" id="dn-perhatian" style="margin-bottom: 6px">Perlu perhatian</h2>
            @if ($attention)
                <div>
                    @foreach ($attention as $item)
                        <div class="rf-rowline">
                            <a class="rf-grow dn-attn-link" href="{{ $item['href'] }}">{{ $item['text'] }}</a>
                            <x-rf.pill :tone="$item['tone']" :icon="$item['icon']">{{ $item['label'] }}</x-rf.pill>
                        </div>
                    @endforeach
                </div>
            @else
                <div class="rf-empty">
                    <x-rf.icon name="check" />
                    <b>Semuanya tenang</b>
                    <p>Tidak ada ruang, pos, atau transaksi yang perlu kamu rapikan sekarang.</p>
                </div>
            @endif
        </section>
    </div>
</div>
