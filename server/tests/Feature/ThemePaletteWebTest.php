<?php

namespace Tests\Feature;

use App\Livewire\Profil;
use App\Models\User;
use App\Support\ThemePalette;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

/**
 * Palet warna aksen web (Pro, Feature::Themes): penguncian lewat Entitlements, pilihan tersimpan per
 * akun, dan palet Pro yang kembali ke Sage saat paket turun tanpa menghapus pilihannya.
 */
class ThemePaletteWebTest extends TestCase
{
    use RefreshDatabase;

    public function test_a_free_user_cannot_choose_a_pro_palette(): void
    {
        $user = User::factory()->create();

        Livewire::actingAs($user)
            ->test(Profil::class)
            ->call('choosePalette', 'laut')
            ->assertSet('paletteError', 'Palet Laut adalah fitur Pro.');

        $this->assertSame('sage', $user->fresh()->theme_palette);
    }

    public function test_a_free_user_can_always_choose_sage(): void
    {
        $user = User::factory()->create(['theme_palette' => 'laut']);

        Livewire::actingAs($user)
            ->test(Profil::class)
            ->call('choosePalette', 'sage')
            ->assertSet('paletteError', null);

        $this->assertSame('sage', $user->fresh()->theme_palette);
    }

    public function test_a_pro_user_saves_the_palette_on_the_account(): void
    {
        $user = User::factory()->create(['plans' => ['pro']]);

        Livewire::actingAs($user)
            ->test(Profil::class)
            ->call('choosePalette', 'anggur')
            ->assertSet('paletteError', null);

        $this->assertSame('anggur', $user->fresh()->theme_palette);
    }

    public function test_an_unknown_palette_value_is_ignored(): void
    {
        $user = User::factory()->create(['plans' => ['pro'], 'theme_palette' => 'pasir']);

        Livewire::actingAs($user)
            ->test(Profil::class)
            ->call('choosePalette', 'merah')
            ->assertSet('paletteError', null);

        $this->assertSame('pasir', $user->fresh()->theme_palette);
    }

    public function test_the_chosen_palette_is_rendered_on_the_html_element_from_the_account(): void
    {
        $user = User::factory()->create(['plans' => ['pro'], 'theme_palette' => 'laut']);

        // Halaman baru (perangkat lain) memakai pilihan dari akun, bukan dari localStorage.
        $this->actingAs($user)->get(route('profil'))
            ->assertOk()
            ->assertSee('data-palette="laut"', false);
    }

    public function test_a_free_account_renders_sage_even_when_a_pro_palette_is_stored(): void
    {
        $user = User::factory()->create(['theme_palette' => 'laut']);

        $this->actingAs($user)->get(route('profil'))
            ->assertOk()
            ->assertSee('data-palette="sage"', false)
            ->assertDontSee('data-palette="laut"', false);
    }

    public function test_downgrade_keeps_the_stored_palette_and_upgrade_brings_it_back(): void
    {
        $user = User::factory()->create(['plans' => ['pro'], 'theme_palette' => 'pasir']);

        $this->actingAs($user)->get(route('profil'))->assertSee('data-palette="pasir"', false);

        // Paket turun: tampilan balik ke Sage, pilihan di database tidak berubah.
        $user->update(['plans' => []]);

        $this->actingAs($user->fresh())->get(route('profil'))
            ->assertSee('data-palette="sage"', false)
            ->assertSee('kembali berlaku begitu Pro aktif', false);
        $this->assertSame('pasir', $user->fresh()->theme_palette);

        // Pro dipasang lagi: palet lama langsung kembali.
        $user->update(['plans' => ['pro']]);

        $this->actingAs($user->fresh())->get(route('profil'))
            ->assertSee('data-palette="pasir"', false)
            ->assertDontSee('kembali berlaku begitu Pro aktif', false);
    }

    public function test_a_downgraded_user_cannot_newly_pick_a_pro_palette_but_can_pick_sage(): void
    {
        $user = User::factory()->create(['plans' => [], 'theme_palette' => 'pasir']);

        Livewire::actingAs($user)
            ->test(Profil::class)
            ->call('choosePalette', 'anggur')
            ->assertSet('paletteError', 'Palet Anggur adalah fitur Pro.');

        $this->assertSame('pasir', $user->fresh()->theme_palette);

        Livewire::actingAs($user)
            ->test(Profil::class)
            ->call('choosePalette', 'sage');

        $this->assertSame('sage', $user->fresh()->theme_palette);
    }

    public function test_the_palette_section_marks_pro_palettes_for_free_users(): void
    {
        $this->actingAs(User::factory()->create())->get(route('profil'))
            ->assertOk()
            ->assertSee('Warna aksen')
            ->assertSee('Laut (Pro)')
            ->assertSee('Pasir (Pro)')
            ->assertSee('Anggur (Pro)')
            ->assertSee('Sage');
    }

    public function test_the_palette_section_drops_the_pro_mark_for_pro_users(): void
    {
        $this->actingAs(User::factory()->create(['plans' => ['pro']]))->get(route('profil'))
            ->assertOk()
            ->assertSee('Laut')
            ->assertDontSee('Laut (Pro)');
    }

    public function test_the_stored_palette_defaults_to_sage_for_new_accounts(): void
    {
        $user = User::factory()->create();

        $this->assertSame(ThemePalette::Sage, $user->themePalette());
        $this->assertSame(ThemePalette::Sage, $user->effectiveThemePalette());
    }
}
