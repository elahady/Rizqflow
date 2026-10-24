@php $googleClientId = config('services.google.client_id'); @endphp

{{-- Masuk (docs/design/web-mockup/project/Masuk.dc.html, metode "google-saja"). Web hanya Masuk dengan
     Google (keputusan 2026-10-18); form email+sandi hanya untuk demo di APP_ENV local/testing. --}}
<x-auth-layout title="Masuk" tagline="Rezeki yang masuk dialirkan ke ruang-ruangmu: Memberi, Diri, Keluarga, supaya setiap hak terpenuhi.">
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
        <p class="au-hint">Pakai akun Google yang sama dengan aplikasi Android.</p>

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

    @if (Route::has('login.demo'))
        <div class="au-divider">atau</div>
        <form method="POST" action="{{ route('login.demo') }}">
            @csrf
            <div class="au-field">
                <label for="email">Email</label>
                <input class="au-input" id="email" type="email" name="email" value="{{ old('email', 'demo@rizqflow.test') }}" autocomplete="username" required>
            </div>
            @error('email')
                <p class="au-error" role="alert"><x-rf.icon name="alert" /><span>{{ $message }}</span></p>
            @enderror
            <div class="au-field">
                <label for="password">Kata sandi</label>
                <input class="au-input" id="password" type="password" name="password" autocomplete="current-password" required>
            </div>
            <button type="submit" class="au-primary">Masuk demo</button>
        </form>
        <p class="au-hint">Hanya di lingkungan lokal · sandi akun demo: password</p>
    @endif
</x-auth-layout>
