Halo {{ $name }},

Seseorang (mungkin kamu) mencoba mendaftar di Rizqflow dengan email ini, padahal email ini sudah punya akun. Tidak ada yang berubah di akunmu.

@if ($hasPassword)
Silakan masuk di {{ route('login') }}. Kalau lupa kata sandi, pakai "Lupa kata sandi?" di halaman itu, atau masuk dengan Google.
@else
Akun ini masuk lewat Google. Silakan masuk dengan Google di {{ route('login') }}. Kalau mau, tambahkan kata sandi di halaman Profil setelah masuk, supaya bisa masuk dengan email dan kata sandi juga.
@endif

Kalau bukan kamu yang mencoba, abaikan email ini.

Rizqflow
