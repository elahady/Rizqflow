{{--
    Pil status: ikon + teks, tidak pernah warna saja. Peringatan memakai warn (kuning), tidak ada merah.

    <x-rf.pill tone="good">Terpenuhi</x-rf.pill>                      ikon bawaan per tone
    <x-rf.pill tone="warn">Perlu perhatian</x-rf.pill>
    <x-rf.pill tone="warn" icon="clock">Segera</x-rf.pill>            ganti ikon
    <x-rf.pill tone="info" icon="">Rp 25.000</x-rf.pill>              icon="" = tanpa ikon
    <x-rf.pill>Mendatang</x-rf.pill>                                  neutral, tanpa ikon

    tone: good | warn | info | neutral (default neutral; nilai lain dianggap neutral).
    icon: nama x-rf.icon; null = bawaan (good=check, warn=warn, info=info, neutral=tanpa ikon);
          "" atau false = tanpa ikon. Atribut lain (class, style, title) diteruskan ke <span>.
--}}
@props(['tone' => 'neutral', 'icon' => null])
@php
    $tone = in_array($tone, ['good', 'warn', 'info', 'neutral'], true) ? $tone : 'neutral';
    $iconName = match (true) {
        $icon === null => ['good' => 'check', 'warn' => 'warn', 'info' => 'info', 'neutral' => null][$tone],
        $icon === '' || $icon === false => null,
        default => $icon,
    };
@endphp
<span {{ $attributes->class(['rf-pill', $tone]) }}>@if ($iconName)<x-rf.icon :name="$iconName" />@endif{{ $slot }}</span>
