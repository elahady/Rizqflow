{{-- Lupa kata sandi: minta kode 6 angka ke email. Jawabannya sama untuk email terdaftar atau tidak. --}}
<x-auth-layout title="Lupa kata sandi" tagline="Masukkan email akunmu. Kami kirim kode 6 angka untuk mengatur ulang kata sandi.">
    <form method="POST" action="{{ route('password.send') }}">
        @csrf
        <div class="au-field">
            <label for="email">Email</label>
            <input class="au-input" id="email" type="email" name="email" value="{{ old('email') }}" autocomplete="email" required autofocus>
            @error('email')
                <p class="au-error" role="alert"><x-rf.icon name="alert" /><span>{{ $message }}</span></p>
            @enderror
        </div>
        <button type="submit" class="au-primary">Kirim kode</button>
    </form>

    <p class="au-switch"><a href="{{ route('login') }}">Kembali ke Masuk</a></p>
</x-auth-layout>
