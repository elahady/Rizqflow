{{--
    Jalur progres tipis (.rf-track) seperti baris ruang di Alirkan.dc.html.

    <x-rf.track :percent="30" :slot="2" />
    <x-rf.track :percent="112" :slot="3" label="Anak dan sekolah terpakai 112 persen dari jatah" />

    percent: int (lebar dibatasi 0..100). slot: 1..8 → c1..c8, selain itu cn; kosong = ikut --c induk.
    label: aria-label (role="img"); tanpa label = hiasan (aria-hidden), tulis angkanya di dekatnya.
    Catatan: "slot" dibaca dari $attributes karena $slot di Blade adalah isi komponen.
--}}
@props(['percent' => 0, 'label' => null])
@php
    $width = max(0, min(100, (int) $percent));
    $colorSlot = $attributes->get('slot');
    $colorClass = $colorSlot === null ? null : (is_numeric($colorSlot) && (int) $colorSlot >= 1 && (int) $colorSlot <= 8 ? 'c'.(int) $colorSlot : 'cn');
@endphp
<div {{ $attributes->except('slot')->class(array_filter(['rf-track', $colorClass]))->merge([
    'role' => $label ? 'img' : null,
    'aria-label' => $label ?: null,
    'aria-hidden' => $label ? null : 'true',
]) }}><i style="width: {{ $width }}%"></i></div>
