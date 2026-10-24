<?php

namespace Tests\Feature;

use App\Livewire\ImporEkspor;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

/** Halaman Impor dan ekspor: satu pintu ke semua jalur data keluar-masuk. */
class ImporEksporTest extends TestCase
{
    use RefreshDatabase;

    public function test_guest_is_redirected_to_login(): void
    {
        $this->get(route('impor-ekspor'))->assertRedirect(route('login'));
    }

    public function test_renders_all_cards_with_links(): void
    {
        $user = User::factory()->create(['plans' => ['pro']]);
        $year = now()->year;

        $this->actingAs($user)->get(route('impor-ekspor'))
            ->assertOk()
            ->assertSee('Ekspor CSV')
            ->assertSee('Impor CSV mutasi bank')
            ->assertSee('Laporan PDF')
            ->assertSee('Rekap zakat')
            ->assertSee('Cadangan terenkripsi')
            ->assertSee('menunggu keputusan metode enkripsi')
            ->assertSee(route('transaksi.ekspor'), false)
            ->assertSee(route('laporan.impor-csv'), false)
            ->assertSee(route('laporan.bandingkan'), false)
            ->assertSee(route('zakat'), false)
            ->assertSee(route('zakat.rekap.pdf', ['year' => $year]), false);
    }

    public function test_pro_tag_shown_only_for_non_pro_users(): void
    {
        $free = User::factory()->create(['plans' => []]);
        $pro = User::factory()->create(['plans' => ['pro']]);

        Livewire::actingAs($free)->test(ImporEkspor::class)
            ->assertSeeHtml('class="rf-tagpro"');

        Livewire::actingAs($pro)->test(ImporEkspor::class)
            ->assertDontSeeHtml('class="rf-tagpro"');
    }

    public function test_export_stays_free_without_pro(): void
    {
        $free = User::factory()->create(['plans' => []]);

        $this->actingAs($free)->get(route('impor-ekspor'))
            ->assertOk()
            ->assertSee(route('transaksi.ekspor'), false)
            ->assertSee('Ekspor CSV');
    }
}
