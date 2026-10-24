<div class="rf-page">
    <x-rf.page-head sub="Pengaturan" title="Impor dan ekspor" lead="Impor CSV, ekspor CSV, dan PDF di satu tempat." />

    <section class="rf-card">
        <div class="rf-empty">
            <x-rf.icon name="download" />
            <b>Halaman ini sedang dibangun.</b>
            <p>Sementara itu, impor dan ekspor CSV masih bisa dipakai dari halamannya masing-masing.</p>
            <div class="rf-actions">
                <a class="rf-btn tonal sm" href="{{ route('laporan.impor-csv') }}"><x-rf.icon name="download" />Impor CSV</a>
                <a class="rf-btn tonal sm" href="{{ route('transaksi.ekspor') }}"><x-rf.icon name="upload" />Ekspor CSV</a>
            </div>
        </div>
    </section>
</div>
