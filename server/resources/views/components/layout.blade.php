{{--
    Kerangka aplikasi: sidebar bergrup + bilah atas + isi, mengikuti docs/design/web-mockup/project/Main.dc.html
    tanpa unsur fase 3 (saklar Pribadi/Keluarga, Keluarga, Keamanan dan kunci, pil Tersinkron, kotak
    "Data terenkripsi"). Dipakai halaman Livewire:

        ->layout('components.layout', ['title' => 'Transaksi — Rizqflow'])
        ->layout('components.layout', ['title' => 'Denah — Rizqflow', 'period' => $bulan])

    'period' (CarbonInterface, opsional, mis. App\Support\Period::fromQuery($this->bulan)) memunculkan
    pemilih bulan di bilah atas; tombolnya tautan ke URL yang sama dengan ?bulan=Y-m. Tanpa 'period'
    pemilih tidak tampil. Pintasan keyboard dan kotak perintah ada di public/js/rizqflow.js.
--}}
@use('App\Support\Feature')
@use('App\Support\Period')
@use('App\Support\Plan')
@use('App\Support\ThemePalette')
@use('Carbon\CarbonImmutable')
@php
    $user = auth()->user();
    // Palet aksen dirender dari server (bukan JS) supaya halaman langsung berwarna benar tanpa berkedip.
    $palette = $user ? $user->effectiveThemePalette() : ThemePalette::Sage;

    $period = isset($period) && $period instanceof \DateTimeInterface ? CarbonImmutable::instance($period)->startOfMonth() : null;
    $periodNav = null;
    if ($period !== null) {
        $previous = Period::previous($period);
        $next = Period::next($period);
        $periodNav = [
            'label' => Period::label($period),
            'prevLabel' => Period::label($previous),
            'nextLabel' => Period::label($next),
            'prevUrl' => Period::query($period) > Period::EARLIEST ? request()->fullUrlWithQuery(['bulan' => Period::query($previous)]) : null,
            // Bulan berjalan atau sesudahnya: belum ada bulan berikutnya untuk dibuka dari sini.
            'nextUrl' => Period::isPast($period) ? request()->fullUrlWithQuery(['bulan' => Period::query($next)]) : null,
        ];
    }

    $navGroups = [];
    if ($user) {
        $is = fn (string ...$patterns) => request()->routeIs(...$patterns);
        // Tag PRO = fitur analisa masih terkunci untuk akun ini (lewat lapisan entitlement).
        $analisaLocked = ! $user->entitlements()->isEnabled(Feature::ReportsAndInsights);

        $navGroups = [
            ['id' => null, 'label' => null, 'items' => [
                ['label' => 'Denah', 'route' => 'denah', 'icon' => 'home', 'on' => $is('denah') || request()->is('/')],
            ]],
            ['id' => 'rf-nav-uang', 'label' => 'Uang', 'items' => [
                ['label' => 'Transaksi', 'route' => 'transaksi', 'icon' => 'list', 'on' => $is('transaksi', 'transaksi.detail', 'transaksi.rezeki')],
                ['label' => 'Jadwal', 'route' => 'jadwal', 'icon' => 'calendar', 'on' => $is('jadwal', 'tagihan', 'transaksi.berulang')],
                ['label' => 'Utang-piutang', 'route' => 'utang-piutang', 'icon' => 'swap', 'on' => $is('utang-piutang')],
                ['label' => 'Akun', 'route' => 'kelola', 'icon' => 'card', 'on' => $is('kelola')],
            ]],
            ['id' => 'rf-nav-ruang', 'label' => 'Ruang', 'items' => [
                ['label' => 'Ruang dan pos', 'route' => 'ruang', 'icon' => 'grid', 'on' => $is('ruang', 'ruang.detail', 'aturan-alokasi')],
                ['label' => 'Zakat', 'route' => 'zakat', 'icon' => 'heart', 'on' => $is('zakat')],
            ]],
            ['id' => 'rf-nav-analisa', 'label' => 'Analisa', 'items' => [
                ['label' => 'Analisa', 'route' => 'laporan', 'icon' => 'chart', 'pro' => $analisaLocked, 'on' => $is('laporan', 'laporan.*') && ! $is('laporan.impor-csv')],
            ]],
            ['id' => 'rf-nav-pengaturan', 'label' => 'Pengaturan', 'items' => [
                ['label' => 'Impor dan ekspor', 'route' => 'impor-ekspor', 'icon' => 'download', 'on' => $is('impor-ekspor', 'laporan.impor-csv')],
                ['label' => 'Profil dan paket', 'route' => 'profil', 'icon' => 'user', 'on' => $is('profil')],
            ]],
        ];

        $plans = $user->plans ?? [];
        $hasPro = in_array(Plan::Pro->value, $plans, true);
        $hasSync = in_array(Plan::Sync->value, $plans, true);
        $planLabel = match (true) {
            $hasPro && $hasSync => 'Pro + Sync',
            $hasPro => 'Paket Pro',
            $hasSync => 'Paket Sync',
            default => 'Paket Gratis',
        };
        $displayName = trim((string) $user->name) !== '' ? trim((string) $user->name) : (string) $user->email;
        $initial = mb_strtoupper(mb_substr($displayName, 0, 1));
    }

    $statusTone = in_array(session('status_tone'), ['good', 'info', 'warn'], true) ? session('status_tone') : 'good';
@endphp
<!DOCTYPE html>
<html lang="id" data-palette="{{ $palette->value }}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    @include('partials.theme-init')
    <title>{{ $title ?? 'Rizqflow' }}</title>
    <link rel="icon" href="{{ asset('favicon.svg') }}" type="image/svg+xml">
    <link rel="icon" href="{{ asset('favicon.ico') }}" sizes="any">
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Libre+Caslon+Text:wght@400;700&family=Manrope:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <link rel="stylesheet" href="{{ asset('css/rizqflow.css') }}?v={{ filemtime(public_path('css/rizqflow.css')) }}">
    @livewireStyles
    @stack('styles')
</head>
<body data-catat-url="{{ route('transaksi') }}#catat" data-rezeki-url="{{ route('transaksi.rezeki') }}">
<a class="rf-skip" href="#isi">Langsung ke isi</a>
<div class="rf-app">
    @if ($user)
        <aside class="rf-side">
            <a class="rf-brand" href="{{ route('denah') }}"><span class="rf-brand-mark" aria-hidden="true">R</span><span class="rf-brand-name">Rizqflow</span></a>

            <nav class="rf-nav" aria-label="Menu utama">
                @foreach ($navGroups as $group)
                    <div class="rf-nav-group" @if ($group['id']) role="group" aria-labelledby="{{ $group['id'] }}" @endif>
                        @if ($group['label'])
                            <div class="rf-nav-label" id="{{ $group['id'] }}">{{ $group['label'] }}</div>
                        @endif
                        @foreach ($group['items'] as $item)
                            <a href="{{ route($item['route']) }}" class="rf-nav-item{{ $item['on'] ? ' on' : '' }}"{!! $item['on'] ? ' aria-current="page"' : '' !!}><x-rf.icon :name="$item['icon']" />{{ $item['label'] }}@if (! empty($item['pro']))<span class="rf-tagpro" title="Fitur Pro">PRO</span>@endif</a>
                        @endforeach
                    </div>
                @endforeach
            </nav>

            <div class="rf-side-foot">
                <div class="rf-user">
                    <span class="rf-avatar" aria-hidden="true">{{ $initial }}</span>
                    <div>
                        <a href="{{ route('profil') }}"><b>{{ $displayName }}</b></a>
                        <span>{{ $planLabel }}</span>
                    </div>
                    @include('partials.theme-toggle')
                    <form method="POST" action="{{ route('logout') }}">
                        @csrf
                        <button type="submit" class="rf-link">Keluar</button>
                    </form>
                </div>
            </div>
        </aside>
    @endif

    <div class="rf-main">
        @if ($user)
            <header class="rf-topbar">
                @if ($periodNav)
                    <nav class="rf-period" aria-label="Periode">
                        @if ($periodNav['prevUrl'])
                            <a class="rf-iconbtn" href="{{ $periodNav['prevUrl'] }}" aria-label="Bulan sebelumnya, {{ $periodNav['prevLabel'] }}" title="{{ $periodNav['prevLabel'] }}"><x-rf.icon name="chevron-left" /></a>
                        @else
                            <span class="rf-iconbtn" role="link" aria-disabled="true" aria-label="Bulan sebelumnya"><x-rf.icon name="chevron-left" /></span>
                        @endif
                        <span>{{ $periodNav['label'] }}</span>
                        @if ($periodNav['nextUrl'])
                            <a class="rf-iconbtn" href="{{ $periodNav['nextUrl'] }}" aria-label="Bulan berikutnya, {{ $periodNav['nextLabel'] }}" title="{{ $periodNav['nextLabel'] }}"><x-rf.icon name="chevron-right" /></a>
                        @else
                            <span class="rf-iconbtn" role="link" aria-disabled="true" aria-label="Bulan berikutnya"><x-rf.icon name="chevron-right" /></span>
                        @endif
                    </nav>
                @endif

                {{-- Kotak perintah: tanpa JavaScript tetap mencari (GET ?cari=); dengan Alpine menawarkan Catat atau Cari. --}}
                <form class="rf-cmd" method="GET" action="{{ route('transaksi') }}" role="search"
                      x-data="rizqflowCmd()" x-on:submit="submit($event)" x-on:keydown.escape="close()" x-on:click.outside="close()">
                    <x-rf.icon name="search" />
                    <input id="rf-cmd-input" type="search" name="cari" autocomplete="off" spellcheck="false"
                           placeholder="Cari transaksi, atau catat cepat: kopi 25rb gopay" aria-label="Cari atau catat cepat"
                           role="combobox" aria-autocomplete="list" aria-controls="rf-cmd-list" aria-expanded="false"
                           x-model="q" x-on:input="onInput()" x-on:focus="open = text !== ''"
                           x-on:keydown.arrow-down.prevent="move(1)" x-on:keydown.arrow-up.prevent="move(-1)"
                           x-bind:aria-expanded="expanded ? 'true' : 'false'" x-bind:aria-activedescendant="activeId()">
                    <span class="rf-kbd" aria-hidden="true">Ctrl K</span>
                    <div class="rf-cmd-pop" id="rf-cmd-list" role="listbox" aria-label="Pilihan" x-show="expanded" x-cloak>
                        <template x-for="(option, index) in options" x-bind:key="option.key">
                            <a class="rf-cmd-opt" role="option" x-bind:id="optionId(option)" x-bind:href="option.href"
                               x-bind:class="{ on: index === active }" x-bind:aria-selected="index === active ? 'true' : 'false'"
                               x-on:mouseenter="active = index" x-on:mousedown.prevent>
                                <span x-show="option.key === 'catat'"><x-rf.icon name="plus" /></span>
                                <span x-show="option.key === 'cari'"><x-rf.icon name="search" /></span>
                                <span x-text="option.label + ': “' + text + '”'"></span>
                            </a>
                        </template>
                    </div>
                </form>

                <details class="rf-menu">
                    <summary class="rf-btn primary"><x-rf.icon name="plus" />Catat</summary>
                    <div class="rf-menu-pop">
                        <a href="{{ route('transaksi') }}#catat">Pengeluaran<span class="rf-kbd" aria-hidden="true">C</span></a>
                        <a href="{{ route('transaksi.rezeki') }}">Rezeki masuk<span class="rf-kbd" aria-hidden="true">R</span></a>
                    </div>
                </details>
            </header>
        @endif

        <main class="rf-content" id="isi" tabindex="-1">
            @if (session('status'))
                <x-rf.banner :tone="$statusTone" role="status">{{ session('status') }}</x-rf.banner>
            @endif

            {{ $slot }}

            <p class="rf-sub">Rizqflow adalah bantuan hitung zakat, bukan fatwa.</p>
        </main>
    </div>
</div>

@livewireScripts
<script src="{{ asset('js/rizqflow.js') }}?v={{ filemtime(public_path('js/rizqflow.js')) }}"></script>
@stack('scripts')
</body>
</html>
