<x-layout title="Daftar — Rizqflow">
    <div style="max-width:360px; width:100%; margin:48px auto;">
        <div style="text-align:center; margin-bottom:28px;">
            <span style="display:inline-flex; width:48px; height:48px; border-radius:14px; background:#4d6359; color:#fff; align-items:center; justify-content:center; font-family:'Libre Caslon Text',serif; font-size:24px;">R</span>
            <h1 class="rf-serif" style="margin-top:16px; font-size:24px;">Buat akun Rizqflow</h1>
        </div>

        <div class="rf-card">
            <form method="POST" action="{{ route('register') }}">
                @csrf
                <div class="rf-field">
                    <label for="name">Nama</label>
                    <input id="name" type="text" name="name" value="{{ old('name') }}" required autofocus>
                    @error('name') <div class="error-text">{{ $message }}</div> @enderror
                </div>
                <div class="rf-field">
                    <label for="email">Email</label>
                    <input id="email" type="email" name="email" value="{{ old('email') }}" required>
                    @error('email') <div class="error-text">{{ $message }}</div> @enderror
                </div>
                <div class="rf-field">
                    <label for="password">Kata sandi</label>
                    <input id="password" type="password" name="password" required>
                    @error('password') <div class="error-text">{{ $message }}</div> @enderror
                </div>
                <div class="rf-field">
                    <label for="password_confirmation">Ulangi kata sandi</label>
                    <input id="password_confirmation" type="password" name="password_confirmation" required>
                </div>
                <button type="submit" class="rf-btn primary" style="width:100%;">Daftar</button>
            </form>
        </div>

        <p style="text-align:center; font-size:13px; color:#424845; margin-top:18px;">
            Sudah punya akun? <a href="{{ route('login') }}">Masuk</a>
        </p>
    </div>
</x-layout>
