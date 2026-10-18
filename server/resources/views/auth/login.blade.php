@php $googleClientId = config('services.google.client_id'); @endphp

<x-layout title="Masuk — Rizqflow">
    <div style="max-width:360px; width:100%; margin:48px auto;">
        <div style="text-align:center; margin-bottom:28px;">
            <span style="display:inline-flex; width:48px; height:48px; border-radius:14px; background:#4d6359; color:#fff; align-items:center; justify-content:center; font-family:'Libre Caslon Text',serif; font-size:24px;">R</span>
            <h1 class="rf-serif" style="margin-top:16px; font-size:24px;">Masuk ke Rizqflow</h1>
            <p style="font-size:13px; color:var(--rf-on-surface-variant); margin-top:8px;">Pakai akun Google yang sama dengan aplikasi Android.</p>
        </div>

        <div class="rf-card" style="display:flex; flex-direction:column; align-items:center; gap:12px;">
            @if ($googleClientId)
                <form id="rf-google-form" method="POST" action="{{ route('login.google') }}">
                    @csrf
                    <input type="hidden" name="credential" id="rf-google-credential">
                </form>
                <div id="g_id_onload" data-client_id="{{ $googleClientId }}" data-callback="rfGoogleSignedIn" data-auto_prompt="false"></div>
                <div class="g_id_signin" data-type="standard" data-shape="pill" data-text="continue_with" data-locale="id" data-width="300"></div>
                <script>
                    function rfGoogleSignedIn(response) {
                        document.getElementById('rf-google-credential').value = response.credential;
                        document.getElementById('rf-google-form').submit();
                    }
                </script>
                <script src="https://accounts.google.com/gsi/client" async></script>
            @else
                <p style="font-size:13px; color:var(--rf-on-surface-variant); text-align:center;">Masuk dengan Google belum disiapkan (GOOGLE_CLIENT_ID kosong).</p>
            @endif
            @error('google') <div class="error-text" role="alert">{{ $message }}</div> @enderror
        </div>

        @if (Route::has('login.demo'))
            <div class="rf-card" style="margin-top:18px;">
                <div style="font-size:12px; font-weight:700; color:var(--rf-on-surface-variant); margin-bottom:12px;">Masuk demo (hanya lokal)</div>
                <form method="POST" action="{{ route('login.demo') }}">
                    @csrf
                    <div class="rf-field">
                        <label for="email">Email</label>
                        <input id="email" type="email" name="email" value="{{ old('email', 'demo@rizqflow.test') }}" required>
                        @error('email') <div class="error-text">{{ $message }}</div> @enderror
                    </div>
                    <div class="rf-field">
                        <label for="password">Kata sandi</label>
                        <input id="password" type="password" name="password" required>
                    </div>
                    <button type="submit" class="rf-btn tonal" style="width:100%;">Masuk demo</button>
                </form>
                <p style="font-size:12px; color:#727874; margin-top:10px;">demo@rizqflow.test / password</p>
            </div>
        @endif
    </div>
</x-layout>
