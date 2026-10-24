@push('styles')
<style>
    .ie-card { display: flex; flex-direction: column; gap: 14px; }
    .ie-head { display: flex; align-items: flex-start; gap: 12px; }
    .ie-ic {
        width: 40px; height: 40px; border-radius: 12px; flex: none; display: flex; align-items: center; justify-content: center;
        background: var(--rf-primary-fixed); color: var(--rf-on-primary-container);
    }
    .ie-head > div { flex: 1 1 0; min-width: 0; }
    .ie-head .rf-tagpro { margin-left: auto; font-family: var(--rf-font-ui); }
    .ie-card > p { font-size: 13.5px; color: var(--rf-on-surface-variant); flex: 1 1 auto; }
</style>
@endpush

<div class="rf-page">
    <x-rf.page-head sub="Pengaturan" title="Impor dan ekspor"
        lead="Bawa datamu masuk dan keluar. Ekspor data selalu gratis; yang berbayar hanya impor massal dan laporan PDF." />

    <div class="rf-grid">
        <section class="rf-card ie-card" aria-labelledby="ie-ekspor">
            <div class="ie-head">
                <span class="ie-ic"><x-rf.icon name="upload" /></span>
                <div>
                    <h2 class="rf-h2" id="ie-ekspor">Ekspor CSV</h2>
                    <span class="rf-sub">Gratis</span>
                </div>
            </div>
            <p>Semua transaksimu dalam satu berkas CSV, siap dibuka di Excel atau Google Sheets.</p>
            <div class="rf-actions">
                <a class="rf-btn primary sm" href="{{ route('transaksi.ekspor') }}"><x-rf.icon name="download" />Unduh CSV</a>
            </div>
        </section>

        <section class="rf-card ie-card" aria-labelledby="ie-impor">
            <div class="ie-head">
                <span class="ie-ic"><x-rf.icon name="download" /></span>
                <div>
                    <h2 class="rf-h2" id="ie-impor">Impor CSV mutasi bank</h2>
                    <span class="rf-sub">Dari bank atau e-wallet</span>
                </div>
                @unless ($isPro)<span class="rf-tagpro" title="Fitur Pro">PRO</span>@endunless
            </div>
            <p>Unggah mutasi, periksa pratinjaunya, lalu masukkan banyak transaksi sekaligus ke satu akun.</p>
            <div class="rf-actions">
                <a class="rf-btn {{ $isPro ? 'primary' : 'tonal' }} sm" href="{{ route('laporan.impor-csv') }}">
                    @unless ($isPro)<x-rf.icon name="lock" />@endunless Buka impor CSV
                </a>
            </div>
        </section>

        <section class="rf-card ie-card" aria-labelledby="ie-pdf">
            <div class="ie-head">
                <span class="ie-ic"><x-rf.icon name="chart" /></span>
                <div>
                    <h2 class="rf-h2" id="ie-pdf">Laporan PDF</h2>
                    <span class="rf-sub">Bandingkan periode</span>
                </div>
                @unless ($isPro)<span class="rf-tagpro" title="Fitur Pro">PRO</span>@endunless
            </div>
            <p>Bandingkan dua bulan atau dua tahun berdampingan, lalu unduh hasilnya sebagai PDF.</p>
            <div class="rf-actions">
                <a class="rf-btn {{ $isPro ? 'primary' : 'tonal' }} sm" href="{{ route('laporan.bandingkan') }}">
                    @unless ($isPro)<x-rf.icon name="lock" />@endunless Buka bandingkan periode
                </a>
            </div>
        </section>

        <section class="rf-card ie-card" aria-labelledby="ie-zakat">
            <div class="ie-head">
                <span class="ie-ic"><x-rf.icon name="heart" /></span>
                <div>
                    <h2 class="rf-h2" id="ie-zakat">Rekap zakat</h2>
                    <span class="rf-sub">Gratis, tahun {{ $year }}</span>
                </div>
            </div>
            <p>Zakat yang sudah ditunaikan sepanjang tahun, untuk catatan atau lampiran SPT.</p>
            <div class="rf-actions">
                <a class="rf-btn primary sm" href="{{ route('zakat.rekap.pdf', ['year' => $year]) }}"><x-rf.icon name="download" />PDF {{ $year }}</a>
                <a class="rf-btn ghost sm" href="{{ route('zakat') }}">Buka Zakat</a>
            </div>
        </section>

        <section class="rf-card ie-card" aria-labelledby="ie-cadangan">
            <div class="ie-head">
                <span class="ie-ic"><x-rf.icon name="lock" /></span>
                <div>
                    <h2 class="rf-h2" id="ie-cadangan">Cadangan terenkripsi</h2>
                    <span class="rf-sub">Menyusul</span>
                </div>
            </div>
            <p>Belum tersedia di web: menunggu keputusan metode enkripsi di web. Untuk sekarang, cadangan dibuat dari aplikasi Android.</p>
            <div class="rf-actions">
                <x-rf.pill tone="neutral" icon="clock">Menyusul</x-rf.pill>
            </div>
        </section>
    </div>
</div>
