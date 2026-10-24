/*
 * Rizqflow web — perilaku bersama kerangka aplikasi dan halaman masuk (tanpa build, tanpa dependensi).
 * Dimuat di akhir <body> sesudah @livewireScripts (Alpine ikut livewire.js dan baru mulai saat
 * DOMContentLoaded, jadi fungsi di sini sudah ada saat Alpine membaca x-data).
 *
 * - Tema: rizqflowCurrentTheme(), rizqflowSetTheme(), rizqflowToggleTheme(); tombol bertanda
 *   [data-rf-theme-toggle]. Pilihan disimpan di localStorage 'rizqflow-theme' (dibaca
 *   partials/theme-init sebelum CSS dimuat) lalu event 'rizqflow:theme' dikirim di document.
 * - Pintasan: Ctrl/Cmd+K fokus kotak perintah, C catat pengeluaran, R rezeki masuk. URL tujuan dibaca
 *   dari data-catat-url dan data-rezeki-url di <body>.
 * - Kotak perintah bilah atas: rizqflowCmd() untuk x-data.
 * - Menu <details class="rf-menu"> tertutup saat klik di luar atau Esc.
 */
(function () {
    'use strict';

    var STORAGE_KEY = 'rizqflow-theme';
    var root = document.documentElement;
    var darkQuery = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

    /** Tema efektif: pilihan pengguna (data-theme) bila ada, kalau tidak preferensi sistem. */
    function currentTheme() {
        var chosen = root.getAttribute('data-theme');
        if (chosen === 'light' || chosen === 'dark') {
            return chosen;
        }
        return darkQuery && darkQuery.matches ? 'dark' : 'light';
    }

    function announceTheme() {
        document.dispatchEvent(new CustomEvent('rizqflow:theme', { detail: { theme: currentTheme() } }));
    }

    function setTheme(theme) {
        if (theme !== 'light' && theme !== 'dark') {
            return;
        }
        root.setAttribute('data-theme', theme);
        try {
            localStorage.setItem(STORAGE_KEY, theme);
        } catch (e) {
            // Penyimpanan diblokir (mode privat, dsb.): tema tetap berganti untuk halaman ini saja.
        }
        announceTheme();
    }

    function toggleTheme() {
        setTheme(currentTheme() === 'dark' ? 'light' : 'dark');
    }

    window.rizqflowCurrentTheme = currentTheme;
    window.rizqflowSetTheme = setTheme;
    window.rizqflowToggleTheme = toggleTheme;

    // Tanpa pilihan sendiri, tema mengikuti sistem: beri tahu grafik dan tombol Google saat sistem berganti.
    if (darkQuery) {
        var onSystemChange = function () {
            if (!root.hasAttribute('data-theme')) {
                announceTheme();
            }
        };
        if (darkQuery.addEventListener) {
            darkQuery.addEventListener('change', onSystemChange);
        } else if (darkQuery.addListener) {
            darkQuery.addListener(onSystemChange);
        }
    }

    document.addEventListener('click', function (event) {
        var target = event.target instanceof Element ? event.target : null;
        if (!target) {
            return;
        }

        if (target.closest('[data-rf-theme-toggle]')) {
            toggleTheme();
        }

        // Tutup menu <details class="rf-menu"> yang terbuka bila klik terjadi di luarnya.
        var menus = document.querySelectorAll('details.rf-menu[open]');
        for (var i = 0; i < menus.length; i++) {
            if (!menus[i].contains(target)) {
                menus[i].removeAttribute('open');
            }
        }
    });

    /* ---------- Pintasan keyboard ---------- */

    function isEditable(el) {
        if (!el || !(el instanceof Element)) {
            return false;
        }
        if (el.isContentEditable) {
            return true;
        }
        var tag = el.tagName;
        return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';
    }

    function bodyUrl(name) {
        return document.body ? document.body.getAttribute('data-' + name + '-url') : null;
    }

    document.addEventListener('keydown', function (event) {
        if (event.defaultPrevented || event.isComposing || event.repeat) {
            return;
        }

        var key = (event.key || '').toLowerCase();
        var active = document.activeElement;

        if (key === 'escape') {
            var open = document.querySelectorAll('details.rf-menu[open]');
            for (var i = 0; i < open.length; i++) {
                open[i].removeAttribute('open');
                var summary = open[i].querySelector('summary');
                if (summary && open[i].contains(active)) {
                    summary.focus();
                }
            }
            return;
        }

        // Ctrl/Cmd+K: fokus kotak perintah. Tetap jalan dari dalam isian biasa (bukan contenteditable),
        // karena bermodifier dan tidak bentrok dengan mengetik.
        if (key === 'k' && (event.ctrlKey || event.metaKey) && !event.altKey && !event.shiftKey) {
            var cmd = document.getElementById('rf-cmd-input');
            if (!cmd || (active && active.isContentEditable)) {
                return;
            }
            event.preventDefault();
            cmd.focus();
            if (typeof cmd.select === 'function') {
                cmd.select();
            }
            return;
        }

        // Pintasan satu huruf: hanya tanpa modifier dan tidak sedang mengetik di isian.
        if (event.ctrlKey || event.metaKey || event.altKey || event.shiftKey || isEditable(active)) {
            return;
        }

        if (key === 'c') {
            var note = document.getElementById('catat-catatan');
            if (note) {
                event.preventDefault();
                note.focus();
                if (typeof note.scrollIntoView === 'function') {
                    note.scrollIntoView({ block: 'center' });
                }
                return;
            }
            var catatUrl = bodyUrl('catat');
            if (catatUrl) {
                event.preventDefault();
                window.location.href = catatUrl;
            }
            return;
        }

        if (key === 'r') {
            var rezekiUrl = bodyUrl('rezeki');
            if (rezekiUrl) {
                event.preventDefault();
                window.location.href = rezekiUrl;
            }
        }
    });

    /* ---------- Kotak perintah bilah atas (Alpine) ---------- */

    function withQuery(base, name, value) {
        try {
            var url = new URL(base, window.location.href);
            url.searchParams.set(name, value);
            return url.toString();
        } catch (e) {
            return base + (base.indexOf('?') === -1 ? '?' : '&') + encodeURIComponent(name) + '=' + encodeURIComponent(value);
        }
    }

    /**
     * Data Alpine untuk <form class="rf-cmd" x-data="rizqflowCmd()">. Saat ada teks, dropdown
     * menawarkan "Catat pengeluaran" dan "Cari transaksi"; Catat lebih dulu bila teks mengandung
     * angka (mis. "kopi 25rb gopay"). Enter memilih yang disorot (awalnya yang pertama), panah
     * atas/bawah berpindah, Esc menutup. Tanpa JavaScript form tetap mengirim GET ?cari=.
     */
    window.rizqflowCmd = function () {
        return {
            q: '',
            open: false,
            active: 0,

            get text() {
                return this.q.trim();
            },

            get options() {
                var text = this.text;
                if (text === '') {
                    return [];
                }
                var base = this.$root.getAttribute('action') || window.location.href;
                var catat = { key: 'catat', label: 'Catat pengeluaran', href: withQuery(base, 'catat', text) };
                var cari = { key: 'cari', label: 'Cari transaksi', href: withQuery(base, 'cari', text) };
                return /\d/.test(text) ? [catat, cari] : [cari, catat];
            },

            get expanded() {
                return this.open && this.options.length > 0;
            },

            optionId(option) {
                return 'rf-cmd-opt-' + option.key;
            },

            activeId() {
                return this.expanded ? this.optionId(this.options[this.active] || this.options[0]) : null;
            },

            onInput() {
                this.active = 0;
                this.open = this.text !== '';
            },

            move(step) {
                var count = this.options.length;
                if (count === 0) {
                    return;
                }
                this.open = true;
                this.active = (this.active + step + count) % count;
            },

            close() {
                this.open = false;
            },

            submit(event) {
                event.preventDefault();
                var options = this.options;
                if (options.length === 0) {
                    return;
                }
                window.location.href = (options[this.active] || options[0]).href;
            },
        };
    };
})();
