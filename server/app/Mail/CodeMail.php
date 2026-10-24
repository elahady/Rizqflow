<?php

namespace App\Mail;

use App\Models\EmailCode;
use Illuminate\Mail\Mailable;
use Illuminate\Mail\Mailables\Content;
use Illuminate\Mail\Mailables\Envelope;

/**
 * Email kode 6 angka (verifikasi email daftar dan atur ulang kata sandi). Dikirim sinkron: web ini
 * tidak menjalankan worker antrean, dan kode asli tidak boleh mampir di tabel jobs.
 */
class CodeMail extends Mailable
{
    public function __construct(
        public string $purpose,
        public string $code,
        public string $name,
        public int $minutes,
    ) {}

    public function envelope(): Envelope
    {
        return new Envelope(subject: match ($this->purpose) {
            EmailCode::PURPOSE_RESET => 'Kode atur ulang kata sandi Rizqflow',
            default => 'Kode verifikasi email Rizqflow',
        });
    }

    public function content(): Content
    {
        return new Content(view: 'mail.code-html', text: 'mail.code');
    }
}
