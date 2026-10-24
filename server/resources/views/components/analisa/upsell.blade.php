{{--
    Kartu ajakan Pro untuk halaman Analisa saat pengguna belum punya fitur Laporan dan Wawasan.
    Belum ada pembayaran di web, jadi sengaja tanpa tombol beli.

    <x-analisa.upsell title="Grafik tren adalah fitur Pro." text="Lihat pemasukan dan pengeluaran ..." />
--}}
@props(['title' => '', 'text' => ''])
<div {{ $attributes->class(['rf-upsell']) }} role="note">
    <span class="rf-upsell-ic"><x-rf.icon name="lock" /></span>
    <div>
        <b>{{ $title }}</b>
        <p>{{ $text }} Fitur ini terbuka di paket Pro; belum ada pembelian lewat web.</p>
    </div>
</div>
