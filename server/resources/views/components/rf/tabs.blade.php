{{--
    Tab berupa tautan (.rf-tabs/.rf-tab); tab aktif bergaris bawah dan diberi aria-current="page".

    <x-rf.tabs label="Analisa" :items="[
        ['label' => 'Aliran', 'href' => route('laporan'), 'active' => true],
        ['label' => 'Tren', 'href' => route('laporan.tren')],
        ['label' => 'Tak terlacak', 'href' => route('transaksi', ['tab' => 'tak']), 'count' => 3],
        ['label' => 'Semua', 'href' => route('transaksi'), 'count' => 47, 'muted' => true],
    ]" />

    items: list ['label' => string, 'href' => string, 'active' => bool (default false),
      'count' => ?int (badge .rf-count; null = tanpa angka), 'muted' => bool (angka sebagai teks redup
      .rf-sub, seperti "Semua 47" di Transaksi.dc.html)].
    label: aria-label untuk <nav> (default "Tab"). Atribut lain (class, style) diteruskan ke <nav>.
--}}
@props(['items' => [], 'label' => 'Tab'])
<nav {{ $attributes->class(['rf-tabs'])->merge(['aria-label' => $label]) }}>
    @foreach ($items as $item)
        @php $active = (bool) ($item['active'] ?? false); @endphp
        <a href="{{ $item['href'] ?? '#' }}" @class(['rf-tab', 'on' => $active]) @if ($active) aria-current="page" @endif>{{ $item['label'] ?? '' }}@if (isset($item['count']) && $item['count'] !== null)<span class="{{ ($item['muted'] ?? false) ? 'rf-sub' : 'rf-count' }}">{{ $item['count'] }}</span>@endif</a>
    @endforeach
</nav>
