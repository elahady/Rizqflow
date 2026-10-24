{{--
    Laju pemakaian vs laju waktu (.rf-pace) seperti kartu ruang di Main.dc.html: isi = terpakai,
    garis tegak = hari yang sudah berjalan di bulan itu.

    <x-rf.pace :used="58" :time="77.4" :slot="2" />
    <x-rf.pace :used="87" :time="77.4" :slot="3" label="Keluarga terpakai 87 persen; waktu berjalan 77 persen" />

    used: persen terpakai (int, boleh > 100; lebar dibatasi 100). time: persen waktu berjalan (int/float,
    dibatasi 0..100). slot: 1..8 → c1..c8, selain itu cn; kosong = ikut --c induk.
    label: aria-label; default "Terpakai {used} persen; waktu berjalan {time} persen". Selalu role="img".
    Catatan: "slot" dibaca dari $attributes karena $slot di Blade adalah isi komponen.
--}}
@props(['used' => 0, 'time' => 0, 'label' => null])
@php
    $usedValue = (int) $used;
    $usedWidth = max(0, min(100, $usedValue));
    // Posisi garis waktu bukan uang, jadi pecahan satu desimal boleh.
    $timeValue = max(0, min(100, round((float) $time, 1)));
    $colorSlot = $attributes->get('slot');
    $colorClass = $colorSlot === null ? null : (is_numeric($colorSlot) && (int) $colorSlot >= 1 && (int) $colorSlot <= 8 ? 'c'.(int) $colorSlot : 'cn');
    $ariaLabel = $label ?: 'Terpakai '.$usedValue.' persen; waktu berjalan '.(int) round($timeValue).' persen';
@endphp
<div {{ $attributes->except('slot')->class(array_filter(['rf-pace', $colorClass]))->merge(['role' => 'img', 'aria-label' => $ariaLabel]) }}><i style="width: {{ $usedWidth }}%"></i><b style="left: {{ $timeValue }}%"></b></div>
