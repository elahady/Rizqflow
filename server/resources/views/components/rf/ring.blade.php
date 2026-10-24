{{--
    Cincin progres ruang seperti kartu ruang di Main.dc.html (viewBox 64, r=26, lingkar 163,4, tebal 7).
    Warna dari --c: pasang kelas c1..c8 di elemen induk (mis. kartu .rf-card.c2) atau isi slot.

    <x-rf.ring :percent="58" />                                   hiasan (angka ditulis di sebelahnya)
    <x-rf.ring :percent="87" warn />                              warna peringatan (kuning)
    <x-rf.ring :percent="87" warn :size="104" :slot="3" text="87%" label="Keluarga terpakai 87 persen dari jatah" />

    percent: int, boleh > 100 (isi dibatasi 100%, angka asli tetap untuk teks). warn: bool.
    size: px (default 64; geometri ikut membesar). slot: 1..8 → c1..c8, selain itu cn; kosong = ikut induk.
    text: teks di tengah cincin (opsional). label: aria-label (role="img"); tanpa label = aria-hidden.
--}}
@props(['percent' => 0, 'warn' => false, 'size' => 64, 'text' => null, 'label' => null])
@php
    $circumference = 163.4;
    $clamped = max(0, min(100, (int) $percent));
    $filled = round($circumference * $clamped / 100, 1);
    $rest = round($circumference - $filled, 1);
    $colorSlot = $attributes->get('slot');
    $colorClass = $colorSlot === null ? null : (is_numeric($colorSlot) && (int) $colorSlot >= 1 && (int) $colorSlot <= 8 ? 'c'.(int) $colorSlot : 'cn');
    $svgAttributes = $attributes->except('slot');
    if ($colorClass !== null) {
        $svgAttributes = $svgAttributes->class([$colorClass]);
    }
@endphp
<svg {{ $svgAttributes->merge([
    'width' => (string) (int) $size,
    'height' => (string) (int) $size,
    'viewBox' => '0 0 64 64',
    'style' => 'transform: rotate(-90deg); flex: none',
    'role' => $label ? 'img' : null,
    'aria-label' => $label ?: null,
    'aria-hidden' => $label ? null : 'true',
]) }}><circle class="rf-ring-bg" cx="32" cy="32" r="26" stroke-width="7"/><circle @class(['rf-ring-fg', 'warn' => $warn]) cx="32" cy="32" r="26" stroke-width="7" stroke-dasharray="{{ $filled }} {{ $rest }}"/>@if ($text !== null && $text !== '')<text class="rf-ring-txt" x="32" y="32" transform="rotate(90 32 32)" text-anchor="middle" dominant-baseline="central" font-size="13.5">{{ $text }}</text>@endif</svg>
