{{-- Email tidak bisa memakai token CSS halaman, jadi warnanya disalin dari design system (rizqflow.css). --}}
<!DOCTYPE html>
<html lang="id">
<body style="margin:0;padding:24px;background:#faf9f7;font-family:Arial,Helvetica,sans-serif;color:#1b1c1b;">
<div style="max-width:420px;margin:0 auto;background:#ffffff;border:1px solid #c2c8c3;border-radius:16px;padding:28px;">
    <p style="margin:0 0 16px;font-size:18px;font-weight:bold;color:#4d6359;">Rizqflow</p>
    <p style="margin:0 0 12px;font-size:15px;line-height:1.5;">Halo {{ $name }},</p>
    <p style="margin:0 0 16px;font-size:15px;line-height:1.5;">
        @if ($purpose === \App\Models\EmailCode::PURPOSE_RESET)
            Kode untuk mengatur ulang kata sandi Rizqflow kamu:
        @else
            Kode untuk memverifikasi email akun Rizqflow kamu:
        @endif
    </p>
    <p style="margin:0 0 16px;padding:14px;background:#f5f3f1;border-radius:12px;text-align:center;font-size:30px;font-weight:bold;letter-spacing:8px;">{{ $code }}</p>
    <p style="margin:0 0 12px;font-size:13px;line-height:1.5;color:#424845;">Kode berlaku {{ $minutes }} menit dan hanya bisa dipakai sekali. Jangan bagikan ke siapa pun.</p>
    <p style="margin:0;font-size:13px;line-height:1.5;color:#424845;">Kalau bukan kamu yang meminta, abaikan email ini. Tidak ada yang berubah di akunmu.</p>
</div>
</body>
</html>
