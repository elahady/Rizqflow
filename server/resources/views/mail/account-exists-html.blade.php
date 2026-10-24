{{-- Email tidak bisa memakai token CSS halaman, jadi warnanya disalin dari design system (rizqflow.css). --}}
<!DOCTYPE html>
<html lang="id">
<body style="margin:0;padding:24px;background:#faf9f7;font-family:Arial,Helvetica,sans-serif;color:#1b1c1b;">
<div style="max-width:420px;margin:0 auto;background:#ffffff;border:1px solid #c2c8c3;border-radius:16px;padding:28px;">
    <p style="margin:0 0 16px;font-size:18px;font-weight:bold;color:#4d6359;">Rizqflow</p>
    <p style="margin:0 0 12px;font-size:15px;line-height:1.5;">Halo {{ $name }},</p>
    <p style="margin:0 0 12px;font-size:15px;line-height:1.5;">Seseorang (mungkin kamu) mencoba mendaftar di Rizqflow dengan email ini, padahal email ini sudah punya akun. Tidak ada yang berubah di akunmu.</p>
    <p style="margin:0 0 12px;font-size:15px;line-height:1.5;">
        @if ($hasPassword)
            Silakan <a href="{{ route('login') }}" style="color:#4d6359;">masuk</a>. Kalau lupa kata sandi, pakai "Lupa kata sandi?" di halaman masuk, atau masuk dengan Google.
        @else
            Akun ini masuk lewat Google. Silakan <a href="{{ route('login') }}" style="color:#4d6359;">masuk dengan Google</a>. Kalau mau, tambahkan kata sandi di halaman Profil setelah masuk, supaya bisa masuk dengan email dan kata sandi juga.
        @endif
    </p>
    <p style="margin:0;font-size:13px;line-height:1.5;color:#424845;">Kalau bukan kamu yang mencoba, abaikan email ini.</p>
</div>
</body>
</html>
