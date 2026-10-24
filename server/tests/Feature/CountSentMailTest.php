<?php

namespace Tests\Feature;

use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Mail;
use Tests\TestCase;

/** Hitungan email terkirim per hari dan peringatan ke pemilik saat 80% batas harian tercapai. */
class CountSentMailTest extends TestCase
{
    private function send(string $to): void
    {
        Mail::raw('uji', fn ($m) => $m->to($to)->subject('Uji'));
    }

    private function sentTo(string $to): int
    {
        $transport = app('mailer')->getSymfonyTransport();

        return collect($transport->messages())
            ->filter(fn ($m) => collect($m->getEnvelope()->getRecipients())->contains(fn ($r) => $r->getAddress() === $to))
            ->count();
    }

    public function test_sent_mail_is_counted_per_day(): void
    {
        config(['rizqflow.mail_daily_limit' => 100, 'rizqflow.owner_email' => 'pemilik@example.com']);

        $this->send('a@example.com');
        $this->send('b@example.com');

        $this->assertSame(2, Cache::get('mail-sent:'.now()->format('Y-m-d')));
        $this->assertSame(0, $this->sentTo('pemilik@example.com'));
    }

    public function test_owner_is_warned_once_when_eighty_percent_of_the_limit_is_reached(): void
    {
        config(['rizqflow.mail_daily_limit' => 5, 'rizqflow.owner_email' => 'pemilik@example.com']);

        foreach (range(1, 6) as $i) {
            $this->send("u{$i}@example.com");
        }

        $this->assertSame(1, $this->sentTo('pemilik@example.com'));
    }

    public function test_no_counting_when_the_limit_is_zero(): void
    {
        config(['rizqflow.mail_daily_limit' => 0]);

        $this->send('a@example.com');

        $this->assertNull(Cache::get('mail-sent:'.now()->format('Y-m-d')));
    }
}
