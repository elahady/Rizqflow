{{--
    Kepala halaman: rf-sub (opsional) + judul rf-h1 32px + rf-lead (opsional), aksi rata kanan
    seperti Transaksi.dc.html. Satu per halaman (judul adalah <h1>).

    <x-rf.page-head sub="Oktober 2026 · 47 transaksi" title="Transaksi">
        <x-slot:actions>
            <a class="rf-btn ghost sm" href="...">...</a>
        </x-slot:actions>
    </x-rf.page-head>

    <x-rf.page-head title="Catat rezeki masuk" lead="Rezeki yang masuk dialirkan ke ruang sesuai aturanmu.">
        <x-slot:sub><a class="rf-link" href="{{ route('transaksi') }}">← Kembali ke Transaksi</a></x-slot:sub>
    </x-rf.page-head>

    title: string (wajib). sub, lead: string atau slot bernama (<x-slot:sub>, <x-slot:lead>) bila perlu
    HTML. Slot actions (opsional): tombol/tautan di kanan. Isi slot bawaan (opsional) tampil di bawah lead.
--}}
@props(['title' => '', 'sub' => null, 'lead' => null])
<div {{ $attributes->class(['rf-page-head']) }}>
    <div>
        @if (filled((string) $sub))
            <div class="rf-sub">{{ $sub }}</div>
        @endif
        <h1 class="rf-h1">{{ $title }}</h1>
        @if (filled((string) $lead))
            <p class="rf-lead">{{ $lead }}</p>
        @endif
        @if ($slot->isNotEmpty())
            {{ $slot }}
        @endif
    </div>
    @isset($actions)
        <div {{ $actions->attributes->class(['rf-page-actions']) }}>{{ $actions }}</div>
    @endisset
</div>
