{{--
    Banner pemberitahuan/flash (.rf-banner): ikon + teks. Peringatan memakai warn (kuning), tidak ada merah.

    <x-rf.banner tone="good" role="status">Aturan alokasi disimpan.</x-rf.banner>
    <x-rf.banner tone="warn">Pos ini melewati jatah 13%.</x-rf.banner>
    <x-rf.banner tone="info" icon="lock">Analisa lengkap adalah fitur Pro.</x-rf.banner>

    tone: good | warn | info | neutral (default info). icon: nama x-rf.icon; null = bawaan per tone
    (good=check, warn=warn, info=info, neutral=info); "" atau false = tanpa ikon.
    Atribut lain (role, class, wire:key) diteruskan ke <div>. Isi slot boleh berisi tautan .rf-link.
--}}
@props(['tone' => 'info', 'icon' => null])
@php
    $tone = in_array($tone, ['good', 'warn', 'info', 'neutral'], true) ? $tone : 'info';
    $iconName = match (true) {
        $icon === null => ['good' => 'check', 'warn' => 'warn', 'info' => 'info', 'neutral' => 'info'][$tone],
        $icon === '' || $icon === false => null,
        default => $icon,
    };
@endphp
<div {{ $attributes->class(['rf-banner', $tone => $tone !== 'neutral']) }}>@if ($iconName)<x-rf.icon :name="$iconName" />@endif<div>{{ $slot }}</div></div>
