{{-- Tombol ganti tema terang/gelap. $class: kelas tombol (default rf-iconbtn; halaman masuk memakai au-theme).
     Ikon matahari tampil saat gelap dan bulan saat terang (CSS .rf-theme-sun/.rf-theme-moon);
     kliknya ditangani public/js/rizqflow.js lewat atribut data-rf-theme-toggle. --}}
<button type="button" class="{{ $class ?? 'rf-iconbtn' }}" data-rf-theme-toggle aria-label="Ganti tema terang/gelap" title="Ganti tema terang/gelap">
    <x-rf.icon name="sun" class="rf-theme-sun" />
    <x-rf.icon name="moon" class="rf-theme-moon" />
</button>
