@php $googleClientId = config('services.google.client_id'); @endphp

{{-- Masuk (docs/design/web-mockup/project/Masuk.dc.html, metode "email-google"): email+kata sandi dan
     Google. Akun dengan email yang sama digabung (lihat App\Auth\GoogleAccountResolver). Notice
     "Kamu sudah keluar" dan "Sesimu berakhir" datang lewat session('status') (auth-layout). --}}
<x-auth-layout title="Masuk" tagline="Rezeki yang masuk dialirkan ke ruang-ruangmu: Memberi, Diri, Keluarga, supaya setiap hak terpenuhi.">
    <form method="POST" action="{{ route('login.attempt') }}">
        @csrf
        <div class="au-field">
            <label for="email">Email</label>
            <input class="au-input" id="email" type="email" name="email" value="{{ old('email') }}" autocomplete="email" required>
        </div>
        <div class="au-field">
            <label for="password">Kata sandi</label>
            <input class="au-input" id="password" type="password" name="password" autocomplete="current-password" required>
        </div>
        @error('email')
            <p class="au-error" role="alert"><x-rf.icon name="alert" /><span>{{ $message }}</span></p>
        @enderror
        @if (config('rizqflow.email_codes_enabled'))
            <p class="au-link-row"><a href="{{ route('password.forgot') }}">Lupa kata sandi?</a></p>
        @endif
        <button type="submit" class="au-primary">Masuk</button>
    </form>

    <div class="au-divider">atau</div>

    @error('google')
        <p class="au-error au-error-lead" role="alert"><x-rf.icon name="alert" /><span>{{ $message }}</span></p>
    @enderror

    @if ($googleClientId)
        {{-- Google Identity Services: server memverifikasi ID token lewat route login.google. --}}
        <form id="rf-google-form" method="POST" action="{{ route('login.google') }}">
            @csrf
            <input type="hidden" name="credential" id="rf-google-credential">
        </form>
        <div id="rf-google-button" class="au-gsi" data-client_id="{{ $googleClientId }}"></div>
        <noscript><p class="au-hint">Aktifkan JavaScript untuk masuk dengan Google.</p></noscript>

        @push('scripts')
            <script>
                function rfGoogleSignedIn(response) {
                    document.getElementById('rf-google-credential').value = response.credential;
                    document.getElementById('rf-google-form').submit();
                }

                // Tombol resmi Google mengikuti tema (outline saat terang, filled_black saat gelap) dan
                // lebar kartu; digambar ulang saat tema berganti.
                function rfGoogleRender() {
                    var el = document.getElementById('rf-google-button');
                    if (!el || !window.google || !google.accounts || !google.accounts.id) return;
                    var dark = typeof window.rizqflowCurrentTheme === 'function'
                        ? window.rizqflowCurrentTheme() === 'dark'
                        : window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
                    var width = Math.max(200, Math.min(400, Math.floor(el.getBoundingClientRect().width)));
                    el.innerHTML = '';
                    google.accounts.id.renderButton(el, {
                        type: 'standard',
                        shape: 'pill',
                        size: 'large',
                        text: 'continue_with',
                        logo_alignment: 'center',
                        locale: 'id',
                        width: width,
                        theme: dark ? 'filled_black' : 'outline',
                    });
                }

                function rfGoogleInit() {
                    var el = document.getElementById('rf-google-button');
                    if (!el || !window.google || !google.accounts || !google.accounts.id) return;
                    google.accounts.id.initialize({
                        client_id: el.getAttribute('data-client_id'),
                        callback: rfGoogleSignedIn,
                        auto_select: false,
                    });
                    rfGoogleRender();
                }

                document.addEventListener('rizqflow:theme', rfGoogleRender);
            </script>
            <script src="https://accounts.google.com/gsi/client" async onload="rfGoogleInit()"></script>
        @endpush
    @else
        <p class="au-hint">Masuk dengan Google belum disiapkan (GOOGLE_CLIENT_ID kosong).</p>
    @endif

    @if (config('rizqflow.email_codes_enabled'))
        <p class="au-switch">Belum punya akun? <a href="{{ route('register') }}">Daftar</a></p>
    @endif
</x-auth-layout>
