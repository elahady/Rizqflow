<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

class LaporanTest extends TestCase
{
    use RefreshDatabase;

    public function test_hub_lists_the_trend_chart_card_marked_pro_for_a_free_user(): void
    {
        $user = User::factory()->create();

        $this->actingAs($user)
            ->get(route('laporan'))
            ->assertOk()
            ->assertSeeInOrder(['Grafik tren', '(Pro)']);
    }

    public function test_hub_does_not_mark_the_card_pro_for_a_pro_user(): void
    {
        $user = User::factory()->create(['plans' => ['pro']]);

        $this->actingAs($user)
            ->get(route('laporan'))
            ->assertOk()
            ->assertSee('Grafik tren')
            ->assertDontSee('(Pro)');
    }
}
