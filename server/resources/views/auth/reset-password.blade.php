{{-- Atur ulang kata sandi: kode 6 angka dari email + kata sandi baru. --}}
<x-auth-layout title="Kata sandi baru" tagline="Masukkan kode 6 angka dari email, lalu kata sandi barumu. Setelah diganti, semua perangkat perlu masuk lagi.">
    <form method="POST" action="{{ route('password.update') }}">
        @csrf
        <div class="au-field">
            <label for="email">Email</label>
            <input class="au-input" id="email" type="email" name="email" value="{{ $email }}" autocomplete="email" required>
            @error('email')
                <p class="au-error" role="alert"><x-rf.icon name="alert" /><span>{{ $message }}</span></p>
            @enderror
        </div>
        <div class="au-field">
            <label for="code">Kode</label>
            <input class="au-input au-code" id="code" type="text" name="code" inputmode="numeric" pattern="[0-9]*" maxlength="6" autocomplete="one-time-code" required autofocus>
            @error('code')
                <p class="au-error" role="alert"><x-rf.icon name="alert" /><span>{{ $message }}</span></p>
            @enderror
        </div>
        <div class="au-field">
            <label for="password">Kata sandi baru</label>
            <input class="au-input" id="password" type="password" name="password" autocomplete="new-password" required>
            <p class="au-field-hint">Minimal 8 karakter, dan bukan kata sandi yang umum dipakai.</p>
            @error('password')
                <p class="au-error" role="alert"><x-rf.icon name="alert" /><span>{{ $message }}</span></p>
            @enderror
        </div>
        <div class="au-field">
            <label for="password_confirmation">Ulangi kata sandi baru</label>
            <input class="au-input" id="password_confirmation" type="password" name="password_confirmation" autocomplete="new-password" required>
        </div>
        <button type="submit" class="au-primary">Ganti kata sandi</button>
    </form>

    <p class="au-switch"><a href="{{ route('password.forgot') }}">Minta kode baru</a> · <a href="{{ route('login') }}">Kembali ke Masuk</a></p>
</x-auth-layout>
