<?php

namespace Tests\Feature;

use App\Livewire\Aliran;
use App\Models\Account;
use App\Models\Room;
use App\Models\SavedView;
use App\Models\User;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

/**
 * Analisa › Aliran: "Simpan tampilan" menyimpan kombinasi filter (rentang, bulan, akun, ruang yang
 * disorot) per akun dan memanggilnya kembali. Hanya untuk pengguna Pro, sama dengan halaman ini.
 */
class AliranTampilanTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Account $bca;

    private Account $dana;

    private Room $keluarga;

    protected function setUp(): void
    {
        parent::setUp();

        $this->travelTo(CarbonImmutable::parse('2026-10-24 10:00'));
        $this->user = $this->userWithAccounts();
        $this->bca = $this->user->accounts()->where('name', 'BCA')->firstOrFail();
        $this->dana = $this->user->accounts()->where('name', 'Dana')->firstOrFail();
        $this->keluarga = $this->user->rooms()->where('name', 'Keluarga')->firstOrFail();
    }

    private function userWithAccounts(bool $pro = true): User
    {
        $user = User::factory()->create($pro ? ['plans' => ['pro']] : []);
        Account::factory()->create(['user_id' => $user->id, 'name' => 'BCA', 'sort_order' => 1]);
        Account::factory()->create(['user_id' => $user->id, 'name' => 'Dana', 'sort_order' => 2]);
        Room::factory()->percent(10)->type(Room::TYPE_MENUNAIKAN)->create(['user_id' => $user->id, 'name' => 'Memberi', 'slot' => 1, 'sort_order' => 1, 'icon' => 'memberi']);
        Room::factory()->percent(30)->type(Room::TYPE_MENUMBUHKAN)->create(['user_id' => $user->id, 'name' => 'Diri', 'slot' => 2, 'sort_order' => 2, 'icon' => 'diri']);
        Room::factory()->percent(60)->create(['user_id' => $user->id, 'name' => 'Keluarga', 'slot' => 3, 'sort_order' => 3, 'icon' => 'keluarga']);

        return $user;
    }

    private function aliran(array $query = [], ?User $as = null)
    {
        return Livewire::actingAs($as ?? $this->user)->withQueryParams($query)->test(Aliran::class);
    }

    private function tampilan(array $filters, string $name = 'Tampilan', ?User $owner = null): SavedView
    {
        return SavedView::create([
            'user_id' => ($owner ?? $this->user)->id,
            'page' => 'aliran',
            'name' => $name,
            'filters' => $filters + ['rentang' => '1', 'bulan' => null, 'akun' => 'semua', 'ids' => [], 'sorot' => 'semua'],
        ]);
    }

    public function test_pro_user_saves_the_current_filters_as_a_named_view(): void
    {
        $component = $this->aliran(['rentang' => '3', 'akun' => 'kustom', 'ids' => (string) $this->dana->id])
            ->set('namaTampilan', 'Rezeki Dana')
            ->call('simpanTampilan', 'r'.$this->keluarga->id)
            ->assertHasNoErrors()
            ->assertSet('namaTampilan', '');

        $saved = SavedView::query()->where('user_id', $this->user->id)->where('page', 'aliran')->sole();
        $this->assertSame('Rezeki Dana', $saved->name);
        $this->assertSame([
            'rentang' => '3',
            'bulan' => null,
            'akun' => 'kustom',
            'ids' => [$this->dana->id],
            'sorot' => 'r'.$this->keluarga->id,
        ], $saved->filters);
        $component->assertSee('Rezeki Dana');
    }

    public function test_a_saved_month_is_stored_as_yyyy_mm(): void
    {
        $this->aliran(['bulan' => '2026-08'])
            ->set('namaTampilan', 'Agustus')
            ->call('simpanTampilan', 'semua');

        $this->assertSame('2026-08', SavedView::query()->where('user_id', $this->user->id)->sole()->filters['bulan']);
    }

    public function test_an_empty_name_is_refused_and_nothing_is_saved(): void
    {
        $this->aliran()
            ->set('namaTampilan', '   ')
            ->call('simpanTampilan', 'semua')
            ->assertHasErrors(['namaTampilan' => 'required']);

        $this->assertSame(0, SavedView::query()->count());
    }

    public function test_applying_a_view_restores_the_whole_filter_set(): void
    {
        $view = $this->tampilan([
            'rentang' => '12', 'bulan' => '2026-09', 'akun' => 'kustom', 'ids' => [$this->dana->id],
            'sorot' => 'r'.$this->keluarga->id,
        ], 'Dua belas bulan');

        $component = $this->aliran()
            ->call('terapkanTampilan', $view->id)
            ->assertSet('rentang', '12')
            ->assertSet('bulan', '2026-09')
            ->assertSet('akun', 'kustom')
            ->assertSet('akunIds', (string) $this->dana->id)
            ->assertSet('sorot', 'r'.$this->keluarga->id);

        // Dua belas bulan yang berakhir di September 2026.
        $component->assertSee('Oktober 2025 – September 2026');
    }

    public function test_a_view_pointing_at_a_room_that_no_longer_exists_falls_back_to_every_room(): void
    {
        $view = $this->tampilan(['sorot' => 'r999999']);

        $this->aliran()
            ->call('terapkanTampilan', $view->id)
            ->assertViewHas('sorotAktif', 'semua');
    }

    public function test_an_archived_room_is_not_a_valid_focus_for_a_view(): void
    {
        $this->keluarga->update(['archived' => true]);
        $view = $this->tampilan(['sorot' => 'r'.$this->keluarga->id]);

        $this->aliran()
            ->call('terapkanTampilan', $view->id)
            ->assertViewHas('sorotAktif', 'semua');
    }

    public function test_a_view_saved_by_another_user_cannot_be_applied_or_deleted(): void
    {
        $other = $this->userWithAccounts();
        $theirs = $this->tampilan(['rentang' => '12'], 'Milik orang lain', $other);

        $this->aliran()
            ->call('terapkanTampilan', $theirs->id)
            ->assertSet('rentang', '1')
            ->call('hapusTampilan', $theirs->id);

        $this->assertDatabaseHas('saved_views', ['id' => $theirs->id]);
    }

    public function test_deleting_a_view_removes_it_from_the_list(): void
    {
        $view = $this->tampilan([], 'Sementara');

        $this->aliran()
            ->assertSee('Sementara')
            ->call('hapusTampilan', $view->id)
            ->assertDontSee('Sementara');

        $this->assertDatabaseMissing('saved_views', ['id' => $view->id]);
    }

    public function test_the_list_is_capped_at_twelve_views_per_user(): void
    {
        for ($i = 1; $i <= Aliran::MAX_TAMPILAN; $i++) {
            $this->tampilan([], 'Tampilan '.$i);
        }

        $this->aliran()
            ->set('namaTampilan', 'Tambahan')
            ->call('simpanTampilan', 'semua')
            ->assertHasErrors(['namaTampilan']);

        $this->assertSame(Aliran::MAX_TAMPILAN, SavedView::query()->where('user_id', $this->user->id)->count());
    }

    public function test_a_free_user_cannot_save_apply_or_delete_views(): void
    {
        $free = $this->userWithAccounts(pro: false);
        $view = SavedView::create(['user_id' => $free->id, 'page' => 'aliran', 'name' => 'Lama', 'filters' => ['rentang' => '12', 'bulan' => null, 'akun' => 'semua', 'ids' => [], 'sorot' => 'semua']]);

        $this->aliran([], $free)
            ->set('namaTampilan', 'Baru')
            ->call('simpanTampilan', 'semua')
            ->call('terapkanTampilan', $view->id)
            ->assertSet('rentang', '1')
            ->call('hapusTampilan', $view->id);

        $this->assertDatabaseCount('saved_views', 1);
        $this->assertDatabaseHas('saved_views', ['id' => $view->id]);
    }
}
