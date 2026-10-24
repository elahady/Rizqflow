{{--
    Layout tamu (halaman masuk), meniru docs/design/web-mockup/project/Masuk.dc.html varian "google-saja";
    ukuran kartu disalin dari habitflow (resources/views/components/auth-card.blade.php).

    <x-auth-layout title="Masuk" tagline="Rezeki yang masuk dialirkan ...">
        ...isi kartu (tombol Google, form)...
    </x-auth-layout>

    title: judul kartu (<h1>) sekaligus <title> "{title} — Rizqflow". tagline: teks di bawah judul.
    Notice session('status') (mis. "Kamu sudah keluar.") tampil di atas isi kartu. Skrip halaman
    boleh di-@push('scripts'); public/js/rizqflow.js (tema) dimuat sebelum stack itu.
--}}
@props(['title' => 'Masuk', 'tagline' => null])
<!DOCTYPE html>
<html lang="id">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    @include('partials.theme-init')
    <title>{{ $title }} — Rizqflow</title>
    <link rel="icon" href="{{ asset('favicon.svg') }}" type="image/svg+xml">
    <link rel="icon" href="{{ asset('favicon.ico') }}" sizes="any">
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Libre+Caslon+Text:wght@400;700&family=Manrope:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <link rel="stylesheet" href="{{ asset('css/rizqflow.css') }}?v={{ filemtime(public_path('css/rizqflow.css')) }}">
</head>
<body>
<div class="au">
    @include('partials.theme-toggle', ['class' => 'au-theme'])

    <main class="au-card">
        <div class="au-brand">
            <span class="au-mark" aria-hidden="true">R</span>
            <h1>{{ $title }}</h1>
            @if (filled($tagline))
                <p>{{ $tagline }}</p>
            @endif
        </div>

        @if (session('status'))
            <p class="au-notice" role="status">{{ session('status') }}</p>
        @endif

        {{ $slot }}
    </main>

    <p class="au-foot"><span>Rizqflow adalah bantuan hitung zakat, bukan fatwa.</span></p>
</div>

<script src="{{ asset('js/rizqflow.js') }}?v={{ filemtime(public_path('js/rizqflow.js')) }}"></script>
@stack('scripts')
</body>
</html>
