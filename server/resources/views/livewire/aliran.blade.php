@use('App\Support\Period')

<div class="rf-page">
    <x-rf.page-head :sub="'Analisa · '.Period::label($month)" title="Aliran" lead="Ke mana rezeki bulan ini mengalir: dari pemasukan ke ruang, lalu ke pos." />

    {{-- Tab sementara ke halaman analisa yang sudah ada; hub Analisa bertab menyusul di fase berikutnya. --}}
    <x-rf.tabs label="Analisa" :items="[
        ['label' => 'Aliran', 'href' => route('laporan'), 'active' => true],
        ['label' => 'Tren', 'href' => route('laporan.tren')],
        ['label' => 'Bandingkan', 'href' => route('laporan.bandingkan')],
        ['label' => 'Kekayaan bersih', 'href' => route('laporan.kekayaan-bersih')],
        ['label' => 'Proyeksi', 'href' => route('laporan.cashflow')],
        ['label' => 'Kebiasaan', 'href' => route('laporan.heatmap')],
    ]" />

    <section class="rf-card">
        <div class="rf-empty">
            <x-rf.icon name="chart" />
            <b>Halaman ini sedang dibangun.</b>
        </div>
    </section>
</div>
