<?php

namespace App\Mail;

use Illuminate\Mail\Mailable;
use Illuminate\Mail\Mailables\Content;
use Illuminate\Mail\Mailables\Envelope;

/**
 * Dikirim ke pemilik email yang sudah punya akun ketika ada yang mencoba Daftar dengan email itu.
 * Halaman Daftar tidak pernah mengatakan "email sudah terdaftar" (anti-enumerasi); pemiliknya
 * diberi tahu lewat email ini dan diarahkan masuk.
 */
class AccountExistsMail extends Mailable
{
    public function __construct(public string $name, public bool $hasPassword) {}

    public function envelope(): Envelope
    {
        return new Envelope(subject: 'Kamu sudah punya akun Rizqflow');
    }

    public function content(): Content
    {
        return new Content(view: 'mail.account-exists-html', text: 'mail.account-exists');
    }
}
