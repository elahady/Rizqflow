{{--
    Tab bersama hub Analisa (Aliran, Tren, Bandingkan, Tahunan, Kekayaan bersih, Proyeksi cashflow, Kebiasaan).
    Pakai: <x-analisa.tabs active="aliran" /> — nilai active: aliran|tren|bandingkan|tahunan|kekayaan|proyeksi|kebiasaan.
--}}
@props(['active' => 'aliran'])

<x-rf.tabs label="Jenis analisa" :items="[
    ['label' => 'Aliran', 'href' => route('laporan'), 'active' => $active === 'aliran'],
    ['label' => 'Tren', 'href' => route('laporan.tren'), 'active' => $active === 'tren'],
    ['label' => 'Bandingkan', 'href' => route('laporan.bandingkan'), 'active' => $active === 'bandingkan'],
    ['label' => 'Tahunan', 'href' => route('laporan.tahunan'), 'active' => $active === 'tahunan'],
    ['label' => 'Kekayaan bersih', 'href' => route('laporan.kekayaan-bersih'), 'active' => $active === 'kekayaan'],
    ['label' => 'Proyeksi cashflow', 'href' => route('laporan.cashflow'), 'active' => $active === 'proyeksi'],
    ['label' => 'Kebiasaan', 'href' => route('laporan.heatmap'), 'active' => $active === 'kebiasaan'],
]" />
