{{--
    Ikon garis 24x24 (kelas .ic: 18px, stroke currentColor). Path disalin persis dari mockup
    docs/design/web-mockup/project/*.dc.html; x, pencil, trash, arrow-right, arrow-up, logout, refresh
    digambar dengan gaya yang sama karena belum ada di mockup.

    <x-rf.icon name="check" />
    <x-rf.icon name="warn" class="rf-theme-sun" style="width: 14px" />   atribut lain diteruskan ke <svg>
    <x-rf.icon name="search" aria-label="Cari" />                         dengan aria-label: role="img"

    Tanpa aria-label ikon dianggap hiasan (aria-hidden). Nama tak dikenal tidak merender apa pun.
    Nama: home list calendar swap card grid heart people user chart download upload lock key search
    plus check chevron-left chevron-right chevron-down warn info alert clock eye eye-off sun moon x
    pencil trash arrow-right arrow-up arrow-down logout refresh
--}}
@props(['name' => null])
@php
    $paths = [
        'home' => '<path d="M3 11l9-8 9 8"/><path d="M5 10v10h14V10"/><path d="M10 20v-6h4v6"/>',
        'list' => '<path d="M8 6h13M8 12h13M8 18h13"/><path d="M3 6h.01M3 12h.01M3 18h.01"/>',
        'calendar' => '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/>',
        'swap' => '<path d="M5 8h14M15 4l4 4-4 4"/><path d="M19 16H5M9 12l-4 4 4 4"/>',
        'card' => '<rect x="3" y="6" width="18" height="14" rx="2"/><path d="M3 10h18"/><path d="M16 15h2"/>',
        'grid' => '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
        'heart' => '<path d="M12 20.5C6.5 16.5 3 13 3 9.2 3 6.8 4.8 5 7.2 5c1.8 0 3.5 1 4.8 2.7C13.3 6 15 5 16.8 5 19.2 5 21 6.8 21 9.2c0 3.8-3.5 7.3-9 11.3z"/>',
        'people' => '<circle cx="9" cy="8" r="3.2"/><path d="M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6"/><circle cx="17.5" cy="9" r="2.5"/><path d="M17 14.2c2.4.2 4.5 2.1 4.5 5.3"/>',
        'user' => '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-7 8-7s8 3 8 7"/>',
        'chart' => '<path d="M4 20V4"/><path d="M4 20h16"/><path d="M8 15l4-4 3 3 5-6"/>',
        'download' => '<path d="M12 4v11M7.5 10.5L12 15l4.5-4.5"/><path d="M4 18v2h16v-2"/>',
        'upload' => '<path d="M12 15V4M7.5 8.5L12 4l4.5 4.5"/><path d="M4 18v2h16v-2"/>',
        'lock' => '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 018 0v3"/>',
        'key' => '<circle cx="8" cy="14" r="4"/><path d="M11 11l9-8M16 6l3 3"/>',
        'search' => '<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2"/>',
        'plus' => '<path d="M12 5v14M5 12h14"/>',
        'check' => '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
        'chevron-left' => '<path d="M15 6l-6 6 6 6"/>',
        'chevron-right' => '<path d="M9 6l6 6-6 6"/>',
        'chevron-down' => '<path d="M6 9l6 6 6-6"/>',
        'warn' => '<path d="M12 4l9.5 16.5h-19z"/><path d="M12 10v4.5M12 17.5h.01"/>',
        'info' => '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
        'alert' => '<circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.5h.01"/>',
        'clock' => '<circle cx="12" cy="12" r="9"/><path d="M12 7.5V12l3 2"/>',
        'eye' => '<path d="M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12z"/><circle cx="12" cy="12" r="2.8"/>',
        'eye-off' => '<path d="M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12z"/><circle cx="12" cy="12" r="2.8"/><path d="M4 4l16 16"/>',
        'sun' => '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>',
        'moon' => '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>',
        'x' => '<path d="M6 6l12 12M18 6L6 18"/>',
        'pencil' => '<path d="M4 20h4L19 9a2.8 2.8 0 00-4-4L4 16v4z"/><path d="M13.5 6.5l4 4"/>',
        'trash' => '<path d="M4 7h16"/><path d="M9 7V4h6v3"/><path d="M6 7l1 13h10l1-13"/><path d="M10 11v6M14 11v6"/>',
        'arrow-right' => '<path d="M5 12h14M13 6l6 6-6 6"/>',
        'arrow-up' => '<path d="M12 19V5M6 11l6-6 6 6"/>',
        'arrow-down' => '<path d="M12 5v14M6 13l6 6 6-6"/>',
        'logout' => '<path d="M9 20H6a2 2 0 01-2-2V6a2 2 0 012-2h3"/><path d="M15 16l4-4-4-4"/><path d="M19 12H9"/>',
        'refresh' => '<path d="M20 11a8 8 0 00-14.5-4.5L4 8"/><path d="M4 4v4h4"/><path d="M4 13a8 8 0 0014.5 4.5L20 16"/><path d="M20 20v-4h-4"/>',
    ];
    $svg = is_string($name) ? ($paths[$name] ?? null) : null;
    $labelled = $attributes->has('aria-label');
    // Tanpa baris baru di sekitar <svg> (lihat @endif di bawah): ikon sering dipakai di dalam teks sebaris.
@endphp
@if ($svg !== null)
<svg {{ $attributes->class(['ic'])->merge([
    'viewBox' => '0 0 24 24',
    'role' => $labelled ? 'img' : null,
    'aria-hidden' => $labelled ? null : 'true',
]) }}>{!! $svg !!}</svg>@endif
