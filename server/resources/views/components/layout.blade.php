<!DOCTYPE html>
<html lang="id">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>{{ $title ?? 'Rizqflow' }}</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Libre+Caslon+Text:wght@400;700&family=Manrope:wght@400;500;600;700&display=swap" rel="stylesheet">
    @livewireStyles
    <style>
        /* Design tokens disalin dari docs/design/tokens.css (arah visual opsi A). Ubah di sana dulu, lalu samakan di sini. */
        :root {
            --rf-background: #faf9f7;
            --rf-on-background: #1b1c1b;
            --rf-surface-lowest: #ffffff;
            --rf-surface-container-low: #f5f3f1;
            --rf-surface-container: #efeeec;
            --rf-surface-container-highest: #e3e2e0;
            --rf-on-surface-variant: #424845;
            --rf-outline: #727874;
            --rf-outline-variant: #c2c8c3;
            --rf-primary: #4d6359;
            --rf-on-primary: #ffffff;
            --rf-primary-fixed: #cfe8db;
            --rf-on-primary-container: #273c33;
            --rf-room-1: #3b6fa8;
            --rf-room-2: #c4653a;
            --rf-room-3: #2a9a80;
            --rf-status-good: #0ca30c;
            --rf-status-warning: #fab219;
            --rf-status-serious: #ec835a;
        }
        * { box-sizing: border-box; }
        body { margin: 0; font-family: 'Manrope', system-ui, sans-serif; background: var(--rf-background); color: var(--rf-on-background); }
        a { color: var(--rf-primary); text-decoration: none; }
        a:hover { color: var(--rf-on-primary-container); }
        header.rf-hd { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; padding: 16px 32px; background: var(--rf-surface-lowest); border-bottom: 1px solid var(--rf-outline-variant); }
        .rf-brand { display: flex; align-items: center; gap: 10px; }
        .rf-brand-mark { width: 32px; height: 32px; border-radius: 9px; background: var(--rf-primary); color: var(--rf-on-primary); display: flex; align-items: center; justify-content: center; font-family: 'Libre Caslon Text', serif; font-size: 17px; }
        .rf-brand-name { font-family: 'Libre Caslon Text', serif; font-size: 19px; }
        nav.rf-nav { display: flex; gap: 22px; }
        nav.rf-nav a { font-size: 14px; font-weight: 600; color: var(--rf-on-surface-variant); padding-bottom: 2px; }
        nav.rf-nav a.on { font-weight: 700; color: var(--rf-primary); border-bottom: 2px solid var(--rf-primary); }
        main.rf-main { max-width: 1200px; width: 100%; margin: 0 auto; padding: 36px 24px 64px; display: flex; flex-direction: column; gap: 28px; }
        h1.rf-serif { margin: 0; font-family: 'Libre Caslon Text', serif; font-weight: 400; font-size: 28px; }
        h2.rf-serif { margin: 0; font-family: 'Libre Caslon Text', serif; font-weight: 400; font-size: 18px; }
        .rf-card { background: var(--rf-surface-lowest); border: 1px solid var(--rf-outline-variant); border-radius: 16px; padding: 22px 26px; }
        .rf-pill { display: inline-flex; align-items: center; gap: 6px; border-radius: 999px; padding: 4px 10px; font-size: 12px; font-weight: 600; }
        .rf-pill.good { color: #1a5c1a; background: #e6f5e6; }
        .rf-pill.warn { color: #8a6d13; background: #fef3d6; }
        .rf-pill.neutral { color: var(--rf-on-surface-variant); background: var(--rf-surface-container); }
        .rf-btn { display: inline-flex; align-items: center; justify-content: center; gap: 8px; border: none; border-radius: 999px; padding: 12px 22px; font-family: 'Manrope', sans-serif; font-size: 14px; font-weight: 700; cursor: pointer; }
        .rf-btn.primary { background: var(--rf-primary); color: var(--rf-on-primary); }
        .rf-btn.tonal { background: var(--rf-primary-fixed); color: var(--rf-on-primary-container); }
        .rf-field { margin-bottom: 16px; }
        .rf-field label { display: block; font-size: 12px; font-weight: 700; color: var(--rf-on-surface-variant); margin-bottom: 6px; }
        .rf-field input, .rf-field select { width: 100%; padding: 11px 14px; border: 1px solid var(--rf-outline-variant); border-radius: 12px; background: var(--rf-surface-container-low); color: var(--rf-on-background); font-family: 'Manrope', sans-serif; font-size: 14px; }
        .error-text { color: #b3261e; font-size: 13px; margin-top: 6px; }
        footer.rf-ft { border-top: 1px solid var(--rf-outline-variant); padding: 20px 24px 32px; max-width: 1200px; margin: 0 auto; font-size: 13px; color: var(--rf-on-surface-variant); display: flex; gap: 16px; flex-wrap: wrap; justify-content: space-between; }
    </style>
</head>
<body>
@auth
    <header class="rf-hd">
        <div style="display:flex; align-items:center; gap:28px; flex-wrap:wrap;">
            <div class="rf-brand">
                <span class="rf-brand-mark">R</span>
                <span class="rf-brand-name">Rizqflow</span>
            </div>
            <nav class="rf-nav">
                <a href="{{ route('denah') }}" class="{{ request()->routeIs('denah') ? 'on' : '' }}">Denah</a>
                <a href="{{ route('transaksi') }}" class="{{ request()->routeIs('transaksi*') ? 'on' : '' }}">Transaksi</a>
                <a href="{{ route('ruang') }}" class="{{ request()->routeIs('ruang*') ? 'on' : '' }}">Ruang</a>
                <a href="{{ route('zakat') }}" class="{{ request()->routeIs('zakat') ? 'on' : '' }}">Zakat</a>
                <a href="{{ route('kelola') }}" class="{{ request()->routeIs('kelola') ? 'on' : '' }}">Akun</a>
                <a href="{{ route('laporan') }}" class="{{ request()->routeIs('laporan*') ? 'on' : '' }}">Laporan</a>
            </nav>
        </div>
        <div style="display:flex; align-items:center; gap:14px;">
            <a href="{{ route('profil') }}" style="font-size:13px; color:var(--rf-on-surface-variant); {{ request()->routeIs('profil') ? 'font-weight:700; color:var(--rf-primary);' : '' }}">{{ auth()->user()->name }}</a>
            <form method="POST" action="{{ route('logout') }}">
                @csrf
                <button type="submit" style="background:none; border:none; color:var(--rf-on-surface-variant); font-family:'Manrope',sans-serif; font-size:13px; cursor:pointer;">Keluar</button>
            </form>
        </div>
    </header>
@endauth

<main class="rf-main">
    @if (session('status'))
        <div class="rf-card" role="status">{{ session('status') }}</div>
    @endif
    {{ $slot }}
</main>

@auth
<footer class="rf-ft">
    <span>Rizqflow — bantuan hitung zakat, bukan fatwa.</span>
</footer>
@endauth

@livewireScripts
@stack('scripts')
</body>
</html>
