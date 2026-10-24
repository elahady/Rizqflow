{{--
    Tab bersama hub Analisa (Aliran, Tren, Bandingkan, Kekayaan bersih, Proyeksi cashflow, Kebiasaan).
    Pakai: <x-analisa.tabs active="aliran" /> — nilai active: aliran|tren|bandingkan|kekayaan|proyeksi|kebiasaan.
--}}
@props(['active' => 'aliran'])

<x-rf.tabs label="Jenis analisa" :items="[
    ['label' => 'Aliran', 'href' => route('laporan'), 'active' => $active === 'aliran'],
    ['label' => 'Tren', 'href' => route('laporan.tren'), 'active' => $active === 'tren'],
    ['label' => 'Bandingkan', 'href' => route('laporan.bandingkan'), 'active' => $active === 'bandingkan'],
    ['label' => 'Kekayaan bersih', 'href' => route('laporan.kekayaan-bersih'), 'active' => $active === 'kekayaan'],
    ['label' => 'Proyeksi cashflow', 'href' => route('laporan.cashflow'), 'active' => $active === 'proyeksi'],
    ['label' => 'Kebiasaan', 'href' => route('laporan.heatmap'), 'active' => $active === 'kebiasaan'],
]" />
