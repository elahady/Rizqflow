@use('App\Support\Rupiah')

<div class="rf-page">
    <x-rf.page-head :sub="$monthLabel" title="Ringkasan" lead="Pemasukan, pengeluaran, dan sisa bulan ini dibandingkan bulan lalu. Gratis untuk semua akun; tren dan bandingkan periode ada di Analisa (Pro)." />

    @if (! $hasData)
        <section class="rf-card">
            <div class="rf-empty">
                <x-rf.icon name="chart" />
                <b>Belum ada transaksi bulan ini.</b>
                <p>Ringkasan pemasukan, pengeluaran, dan perbandingan bulan lalu akan muncul di sini begitu ada transaksi.</p>
            </div>
        </section>
    @else
        <div class="rf-stats">
            @foreach ($comparisons as $row)
                <div class="rf-stat">
                    <div class="l">{{ $row['label'] }}</div>
                    <div class="v">{{ Rupiah::format($row['current']) }}</div>
                    <div class="d">
                        @if ($row['direction'] === 'naik')
                            <x-rf.icon name="arrow-up" />
                        @elseif ($row['direction'] === 'turun')
                            <x-rf.icon name="arrow-down" />
                        @else
                            <i class="rf-dot cn" aria-hidden="true"></i>
                        @endif
                        <span>{{ $row['text'] }}</span>
                    </div>
                </div>
            @endforeach
        </div>

        <section class="rf-card" style="margin-top: 16px">
            <div class="rf-card-head">
                <h2 class="rf-h2">Pengeluaran per ruang</h2>
            </div>
            @forelse ($roomRows as $row)
                <div style="padding: 8px 0">
                    <div class="rf-row" style="justify-content: space-between; gap: 12px">
                        <x-rf.room-name :room="$row['room']" />
                        <span class="rf-sub" style="font-variant-numeric: tabular-nums">{{ Rupiah::format($row['amount']) }}</span>
                    </div>
                    <x-rf.track :percent="$row['percent']" :slot="$row['room']->slot" style="margin-top: 6px" />
                </div>
            @empty
                <p class="rf-sub" style="margin: 0">Belum ada pengeluaran per ruang bulan ini.</p>
            @endforelse
        </section>

        <section class="rf-card" style="margin-top: 16px">
            <div class="rf-card-head">
                <h2 class="rf-h2">Kategori terbesar</h2>
            </div>
            @forelse ($categoryRows as $row)
                <div style="padding: 8px 0">
                    <div class="rf-row" style="justify-content: space-between; gap: 12px">
                        <x-rf.room-name :room="$row['room']" :pos="$row['name']" />
                        <span class="rf-sub" style="font-variant-numeric: tabular-nums">{{ Rupiah::format($row['amount']) }}</span>
                    </div>
                    <x-rf.track :percent="$row['percent']" :slot="$row['room']->slot" style="margin-top: 6px" />
                </div>
            @empty
                <p class="rf-sub" style="margin: 0">Belum ada pengeluaran berkategori bulan ini.</p>
            @endforelse
        </section>
    @endif
</div>
