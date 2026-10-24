{{-- Tema pilihan pengguna dipasang sebelum CSS dimuat supaya halaman tidak berkedip terang lalu gelap.
     Tanpa pilihan, CSS mengikuti preferensi sistem. Pola dari habitflow (partials/theme-init);
     penggantinya ada di public/js/rizqflow.js (rizqflowToggleTheme). --}}
<script>
(function () {
    try {
        var theme = localStorage.getItem('rizqflow-theme');
        if (theme === 'light' || theme === 'dark') {
            document.documentElement.setAttribute('data-theme', theme);
        }
    } catch (e) {}
})();
</script>
