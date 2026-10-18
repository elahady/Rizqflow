<x-layout title="Masuk — Rizqflow">
    <div style="max-width:360px; width:100%; margin:48px auto;">
        <div style="text-align:center; margin-bottom:28px;">
            <span style="display:inline-flex; width:48px; height:48px; border-radius:14px; background:#4d6359; color:#fff; align-items:center; justify-content:center; font-family:'Libre Caslon Text',serif; font-size:24px;">R</span>
            <h1 class="rf-serif" style="margin-top:16px; font-size:24px;">Masuk ke Rizqflow</h1>
        </div>

        <div class="rf-card">
            <form method="POST" action="{{ route('login') }}">
                @csrf
                <div class="rf-field">
                    <label for="email">Email</label>
                    <input id="email" type="email" name="email" value="{{ old('email') }}" required autofocus>
                    @error('email') <div class="error-text">{{ $message }}</div> @enderror
                </div>
                <div class="rf-field">
                    <label for="password">Kata sandi</label>
                    <input id="password" type="password" name="password" required>
                </div>
                <button type="submit" class="rf-btn primary" style="width:100%;">Masuk</button>
            </form>
        </div>

        <p style="text-align:center; font-size:13px; color:#424845; margin-top:18px;">
            Belum punya akun? <a href="{{ route('register') }}">Daftar</a>
        </p>
        <p style="text-align:center; font-size:12px; color:#727874; margin-top:8px;">
            Demo: demo@rizqflow.test / password
        </p>
    </div>
</x-layout>
