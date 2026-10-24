{{-- Daftar email+sandi (alur habitflow, gaya Masuk.dc.html). Jawabannya sama untuk email baru dan email
     yang sudah punya akun; setelah kirim, pemakai ke halaman kode verifikasi. --}}
<x-auth-layout title="Daftar" tagline="Buat akun Rizqflow dengan email dan kata sandi. Kami kirim kode 6 angka untuk memverifikasi emailmu.">
    <form method="POST" action="{{ route('register.store') }}">
        @csrf
        <div class="au-field">
            <label for="name">Nama</label>
            <input class="au-input" id="name" type="text" name="name" value="{{ old('name') }}" autocomplete="name" maxlength="255" required autofocus>
            @error('name')
                <p class="au-error" role="alert"><x-rf.icon name="alert" /><span>{{ $message }}</span></p>
            @enderror
        </div>
        <div class="au-field">
            <label for="email">Email</label>
            <input class="au-input" id="email" type="email" name="email" value="{{ old('email') }}" autocomplete="email" maxlength="255" required>
            @error('email')
                <p class="au-error" role="alert"><x-rf.icon name="alert" /><span>{{ $message }}</span></p>
            @enderror
        </div>
        <div class="au-field">
            <label for="password">Kata sandi</label>
            <input class="au-input" id="password" type="password" name="password" autocomplete="new-password" required>
            <p class="au-field-hint">Minimal 8 karakter, dan bukan kata sandi yang umum dipakai.</p>
            @error('password')
                <p class="au-error" role="alert"><x-rf.icon name="alert" /><span>{{ $message }}</span></p>
            @enderror
        </div>
        <div class="au-field">
            <label for="password_confirmation">Ulangi kata sandi</label>
            <input class="au-input" id="password_confirmation" type="password" name="password_confirmation" autocomplete="new-password" required>
        </div>
        <button type="submit" class="au-primary">Daftar</button>
    </form>

    <p class="au-switch">Sudah punya akun? <a href="{{ route('login') }}">Masuk</a></p>
</x-auth-layout>
