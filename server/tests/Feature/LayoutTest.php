<?php

namespace Tests\Feature;

use App\Models\User;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Blade;
use Illuminate\Support\Facades\Route;
use Tests\TestCase;

/** Kerangka aplikasi baru (sidebar + bilah atas), halaman masuk, dan route halaman baru (fase 1 desain web). */
class LayoutTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        CarbonImmutable::setTestNow(CarbonImmutable::create(2026, 10, 24, 10));
    }

    protected function tearDown(): void
    {
        CarbonImmutable::setTestNow();
        parent::tearDown();
    }

    /** Tautan menu (<a class="rf-nav-item">) yang menuju $path, lengkap dengan atribut aktifnya. */
    private function navItem(string $html, string $path): string
    {
        preg_match('#<a href="[^"]*'.preg_quote($path, '#').'" class="rf-nav-item[^"]*"[^>]*>.*?</a>#s', $html, $match);

        return $match[0] ?? '';
    }

    public function test_sidebar_shows_every_menu_link_for_a_logged_in_user(): void
    {
        $user = User::factory()->create(['name' => 'Ayu Lestari']);

        $html = $this->actingAs($user)->get(route('jadwal'))->assertOk()->getContent();

        foreach (['Denah', 'Transaksi', 'Jadwal', 'Utang-piutang', 'Akun', 'Ruang dan pos', 'Zakat', 'Analisa', 'Impor dan ekspor', 'Profil dan paket'] as $label) {
            $this->assertMatchesRegularExpression('#<a href="[^"]*" class="rf-nav-item[^"]*"[^>]*>(?:<svg.*?</svg>)?'.preg_quote($label, '#').'#s', $html, "Menu {$label} tidak ada");
        }
        foreach (['Uang', 'Ruang', 'Analisa', 'Pengaturan'] as $group) {
            $this->assertMatchesRegularExpression('#<div class="rf-nav-label"[^>]*>'.$group.'</div>#', $html, "Grup {$group} tidak ada");
        }

        $this->assertStringContainsString('Ayu Lestari', $html);
        $this->assertStringContainsString('Paket Gratis', $html);
        $this->assertStringContainsString('action="'.route('logout').'"', $html);
        $this->assertStringContainsString('Rizqflow adalah bantuan hitung zakat, bukan fatwa.', $html);
    }

    public function test_only_the_matching_menu_item_is_marked_current(): void
    {
        $user = User::factory()->create();

        $html = $this->actingAs($user)->get(route('jadwal'))->getContent();
        // Hanya di menu sidebar; tab di dalam halaman (mis. Agenda di Jadwal) boleh punya aria-current sendiri.
        preg_match('#<nav class="rf-nav".*?</nav>#s', $html, $nav);
        $this->assertSame(1, preg_match_all('#aria-current="page"#', $nav[0] ?? ''));
        $this->assertStringContainsString('aria-current="page"', $this->navItem($html, '/jadwal'));
        $this->assertStringNotContainsString('aria-current', $this->navItem($html, '/profil'));

        // Halaman bersaudara ikut menyalakan menu yang sama.
        $this->assertStringContainsString('aria-current="page"', $this->navItem($this->actingAs($user)->get(route('tagihan'))->getContent(), '/jadwal'));
        $this->assertStringContainsString('aria-current="page"', $this->navItem($this->actingAs($user)->get(route('laporan.impor-csv'))->getContent(), '/impor-ekspor'));
    }

    public function test_pro_tag_shows_for_free_users_and_not_for_pro_users(): void
    {
        $free = User::factory()->create();
        $pro = User::factory()->create(['plans' => ['pro']]);

        $freeHtml = $this->actingAs($free)->get(route('jadwal'))->getContent();
        $this->assertStringContainsString('class="rf-tagpro"', $freeHtml);
        $this->assertStringContainsString('Paket Gratis', $freeHtml);

        $proHtml = $this->actingAs($pro)->get(route('jadwal'))->getContent();
        $this->assertStringNotContainsString('rf-tagpro', $proHtml);
        $this->assertStringContainsString('Paket Pro', $proHtml);
    }

    public function test_plan_label_in_sidebar_covers_every_combination(): void
    {
        $this->actingAs(User::factory()->create(['plans' => ['sync']]))->get(route('jadwal'))->assertSee('Paket Sync');
        $this->actingAs(User::factory()->create(['plans' => ['pro', 'sync']]))->get(route('jadwal'))->assertSee('Pro + Sync');
    }

    public function test_period_picker_is_absent_when_the_page_sends_no_period(): void
    {
        $this->actingAs(User::factory()->create())->get(route('jadwal'))
            ->assertOk()
            ->assertDontSee('class="rf-period"', false);
    }

    public function test_period_picker_links_to_the_neighbouring_months_for_a_past_month(): void
    {
        $this->actingAs(User::factory()->create())->get(route('laporan', ['bulan' => '2026-08']))
            ->assertOk()
            ->assertSee('class="rf-period"', false)
            ->assertSee('Agustus 2026')
            ->assertSee('bulan=2026-07', false)
            ->assertSee('bulan=2026-09', false);
    }

    public function test_period_picker_disables_next_at_the_current_month(): void
    {
        $html = $this->actingAs(User::factory()->create())->get(route('laporan'))->assertOk()->getContent();

        $this->assertStringContainsString('Oktober 2026', $html);
        $this->assertStringContainsString('bulan=2026-09', $html);
        $this->assertStringNotContainsString('bulan=2026-11', $html);
        $this->assertMatchesRegularExpression('#<span class="rf-iconbtn" role="link" aria-disabled="true" aria-label="Bulan berikutnya">#', $html);
    }

    public function test_command_box_and_shortcut_hooks_are_present(): void
    {
        $html = $this->actingAs(User::factory()->create())->get(route('jadwal'))->getContent();

        $this->assertStringContainsString('id="rf-cmd-input"', $html);
        $this->assertStringContainsString('placeholder="Cari transaksi, atau catat cepat: kopi 25rb gopay"', $html);
        $this->assertStringContainsString('Ctrl K', $html);
        $this->assertStringContainsString('action="'.route('transaksi').'"', $html);
        $this->assertStringContainsString('data-catat-url="'.route('transaksi').'#catat"', $html);
        $this->assertStringContainsString('data-rezeki-url="'.route('transaksi.rezeki').'"', $html);
        $this->assertStringContainsString('href="'.route('transaksi.rezeki').'"', $html);
        $this->assertStringContainsString('js/rizqflow.js?v=', $html);
        $this->assertStringContainsString('css/rizqflow.css?v=', $html);
    }

    public function test_layout_has_no_phase_three_claims(): void
    {
        $user = User::factory()->create();

        foreach ([route('jadwal'), route('transaksi.rezeki'), route('impor-ekspor'), route('laporan')] as $url) {
            $this->actingAs($user)->get($url)->assertOk()
                ->assertDontSee('Tersinkron')
                ->assertDontSee('Data terenkripsi')
                ->assertDontSee('Keamanan dan kunci')
                ->assertDontSee('kode pemulihan', false);
        }
    }

    public function test_flash_status_is_shown_as_a_banner(): void
    {
        $this->actingAs(User::factory()->create())
            ->withSession(['status' => 'Aturan alokasi disimpan.'])
            ->get(route('jadwal'))
            ->assertSee('Aturan alokasi disimpan.')
            ->assertSee('rf-banner good', false);
    }

    public function test_layout_renders_pushed_styles_in_the_head(): void
    {
        $html = Blade::render('@push("styles")<style id="gaya-halaman">.x{}</style>@endpush<x-layout title="Uji">isi</x-layout>');

        $this->assertMatchesRegularExpression('#<head>.*<style id="gaya-halaman">.*</head>#s', $html);
    }

    public function test_layout_without_a_user_has_no_sidebar_or_top_bar(): void
    {
        $html = Blade::render('<x-layout title="Uji">isi</x-layout>');

        $this->assertStringContainsString('isi', $html);
        $this->assertStringNotContainsString('class="rf-side"', $html);
        $this->assertStringNotContainsString('class="rf-topbar"', $html);
    }

    public function test_login_page_is_ok_for_guests_and_shows_the_demo_form_in_testing(): void
    {
        config(['services.google.client_id' => 'abc.apps.googleusercontent.com']);

        $this->get(route('login'))
            ->assertOk()
            ->assertSee('<h1>Masuk</h1>', false)
            ->assertSee('Pakai akun Google yang sama dengan aplikasi Android.')
            ->assertSee('rf-google-button', false)
            ->assertSee('accounts.google.com/gsi/client', false)
            ->assertSee('rizqflow:theme', false)
            ->assertSee('action="'.route('login.demo').'"', false)
            ->assertSee('Masuk demo')
            ->assertSee('Hanya di lingkungan lokal')
            ->assertSee('data-rf-theme-toggle', false)
            ->assertSee('Rizqflow adalah bantuan hitung zakat, bukan fatwa.')
            ->assertDontSee('Daftar')
            ->assertDontSee('Lupa')
            ->assertDontSee('24 kata');
    }

    public function test_login_page_explains_when_google_is_not_configured(): void
    {
        config(['services.google.client_id' => null]);

        $this->get(route('login'))->assertOk()->assertSee('GOOGLE_CLIENT_ID kosong');
    }

    public function test_logging_out_redirects_to_login_with_a_notice(): void
    {
        $this->actingAs(User::factory()->create())
            ->post(route('logout'))
            ->assertRedirect(route('login'))
            ->assertSessionHas('status', 'Kamu sudah keluar.');

        $this->assertGuest();
        $this->get(route('login'))->assertSee('Kamu sudah keluar.');
    }

    public function test_new_routes_are_registered_and_send_guests_to_login(): void
    {
        $expected = [
            'transaksi.rezeki' => '/transaksi/rezeki',
            'jadwal' => '/jadwal',
            'impor-ekspor' => '/impor-ekspor',
            'laporan' => '/laporan',
        ];
        foreach ($expected as $name => $path) {
            $this->assertTrue(Route::has($name), "Route {$name} belum terdaftar");
            $this->assertSame($path, route($name, [], false));
            $this->get($path)->assertRedirect(route('login'));
        }

        $this->assertSame('/zakat/rekap/2026/pdf', route('zakat.rekap.pdf', 2026, false));
        $this->assertSame('/zakat/rekap/2026/csv', route('zakat.rekap.csv', 2026, false));
        $this->get('/zakat/rekap/2026/pdf')->assertRedirect(route('login'));
        $this->get('/zakat/rekap/2026/csv')->assertRedirect(route('login'));
    }

    public function test_rezeki_route_is_not_swallowed_by_the_transaction_detail_route(): void
    {
        $this->actingAs(User::factory()->create())->get('/transaksi/rezeki')
            ->assertOk()
            ->assertSee('Catat rezeki masuk');
    }

    public function test_zakat_recap_year_must_be_four_digits(): void
    {
        $user = User::factory()->create();

        $this->actingAs($user)->get('/zakat/rekap/26/pdf')->assertNotFound();
        $this->actingAs($user)->get('/zakat/rekap/2026/pdf')->assertOk();
        $this->actingAs($user)->get('/zakat/rekap/2026/csv')->assertOk();
    }

    public function test_new_pages_render_inside_the_new_layout(): void
    {
        $user = User::factory()->create();

        foreach ([route('jadwal'), route('impor-ekspor'), route('transaksi.rezeki'), route('laporan')] as $url) {
            $this->actingAs($user)->get($url)->assertOk()
                ->assertSee('class="rf-side"', false)
                ->assertSee('<h1 class="rf-h1"', false);
        }
    }
}
