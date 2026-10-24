{{-- Verifikasi email dengan kode 6 angka. "Kirim ulang kode" memakai form yang sama (formaction) supaya
     emailnya ikut walau sesi flash sudah hilang. --}}
<x-auth-layout title="Verifikasi email" tagline="Masukkan kode 6 angka yang kami kirim ke emailmu. Kode berlaku 10 menit.">
    <form method="POST" action="{{ route('verification.verify') }}">
        @csrf
        <div class="au-field">
            <label for="email">Email</label>
            <input class="au-input" id="email" type="email" name="email" value="{{ $email }}" autocomplete="email" required>
            @error('email')
                <p class="au-error" role="alert"><x-rf.icon name="alert" /><span>{{ $message }}</span></p>
            @enderror
        </div>
        <div class="au-field">
            <label for="code">Kode verifikasi</label>
            <input class="au-input au-code" id="code" type="text" name="code" inputmode="numeric" pattern="[0-9]*" maxlength="6" autocomplete="one-time-code" required autofocus>
            @error('code')
                <p class="au-error" role="alert"><x-rf.icon name="alert" /><span>{{ $message }}</span></p>
            @enderror
        </div>
        <button type="submit" class="au-primary">Verifikasi</button>
        <p class="au-switch">
            <button type="submit" class="au-linkbtn" formaction="{{ route('verification.resend') }}" formnovalidate>Kirim ulang kode</button>
        </p>
    </form>

    <p class="au-switch"><a href="{{ route('login') }}">Kembali ke Masuk</a></p>
</x-auth-layout>
