{{--
    Pembantu Chart.js bersama halaman Analisa (Tren, Kekayaan bersih, Proyeksi cashflow).
    Warna, font, dan garis kisi dibaca dari token CSS lewat getComputedStyle supaya ikut terang/gelap;
    grafik digambar ulang saat event data Livewire datang dan saat tema berganti ('rizqflow:theme').

    Pakai: @include('partials.analisa-chart') lalu di @push('scripts') halaman:
        rizqflowAnalisaChart.mount({ canvasId: 'trend-chart', event: 'trend-chart-data', build: (series, t) => ({ type: 'line', ... }) });
    build(series, t) mengembalikan konfigurasi Chart.js; t = token { primary, outline, muted, grid, room1..room8, font }
    dan t.alpha(warna, 0..1) untuk warna transparan.
--}}
@once
    @push('scripts')
        <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js"></script>
        <script>
            window.rizqflowAnalisaChart = window.rizqflowAnalisaChart || (function () {
                function css(name) {
                    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
                }

                // Token berupa hex (#rrggbb / #rgb); selain itu dipakai apa adanya tanpa transparansi.
                function alpha(color, a) {
                    var m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(color);
                    if (!m) return color;
                    var h = m[1].length === 3 ? m[1].replace(/./g, '$&$&') : m[1];
                    return 'rgba(' + parseInt(h.slice(0, 2), 16) + ',' + parseInt(h.slice(2, 4), 16) + ',' + parseInt(h.slice(4, 6), 16) + ',' + a + ')';
                }

                function tokens() {
                    var t = {
                        primary: css('--rf-primary'),
                        outline: css('--rf-outline'),
                        muted: css('--rf-on-surface-variant'),
                        grid: css('--rf-surface-container-high'),
                        font: css('--rf-font-ui'),
                        alpha: alpha,
                    };
                    for (var i = 1; i <= 8; i++) t['room' + i] = css('--rf-room-' + i);
                    return t;
                }

                function rupiah(v) {
                    return 'Rp ' + Number(v).toLocaleString('id-ID');
                }

                // Sumbu dan legenda yang sama untuk semua grafik Analisa; halaman boleh menimpa lewat options.
                function baseOptions(t, opts) {
                    opts = opts || {};
                    return {
                        responsive: true,
                        maintainAspectRatio: false,
                        interaction: { mode: 'index', intersect: false },
                        scales: {
                            x: { grid: { display: false }, border: { color: t.outline }, ticks: { color: t.muted, maxRotation: 0, autoSkip: true } },
                            y: {
                                beginAtZero: opts.beginAtZero !== false,
                                grid: { color: t.grid }, border: { display: false },
                                ticks: { color: t.muted, callback: function (v) { return rupiah(v); } },
                            },
                        },
                        plugins: {
                            legend: opts.legend === false ? { display: false } : { position: 'bottom', labels: { color: t.muted, usePointStyle: true, boxWidth: 8, padding: 16 } },
                            tooltip: { callbacks: { label: function (c) { return (c.dataset.label ? c.dataset.label + ': ' : '') + rupiah(c.parsed.y); } } },
                        },
                    };
                }

                function mount(cfg) {
                    var chart = null;
                    var series = null;

                    function draw() {
                        var canvas = document.getElementById(cfg.canvasId);
                        if (chart) {
                            chart.destroy();
                            chart = null;
                        }
                        if (!canvas || !series || typeof Chart === 'undefined') return;

                        var t = tokens();
                        Chart.defaults.font.family = t.font;
                        Chart.defaults.color = t.muted;
                        chart = new Chart(canvas, cfg.build(series, t));
                    }

                    window.addEventListener(cfg.event, function (event) {
                        series = event.detail.series;
                        draw();
                    });
                    document.addEventListener('rizqflow:theme', draw);
                }

                return { mount: mount, baseOptions: baseOptions, rupiah: rupiah };
            })();
        </script>
    @endpush
@endonce
