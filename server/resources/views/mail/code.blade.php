Halo {{ $name }},

@if ($purpose === \App\Models\EmailCode::PURPOSE_RESET)
Kode untuk mengatur ulang kata sandi Rizqflow kamu:
@else
Kode untuk memverifikasi email akun Rizqflow kamu:
@endif

{{ $code }}

Kode berlaku {{ $minutes }} menit dan hanya bisa dipakai sekali. Jangan bagikan ke siapa pun.

Kalau bukan kamu yang meminta, abaikan email ini. Tidak ada yang berubah di akunmu.

Rizqflow
