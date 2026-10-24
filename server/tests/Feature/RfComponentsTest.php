<?php

namespace Tests\Feature;

use App\Models\Room;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Blade;
use Tests\TestCase;

/** Komponen Blade anonim x-rf.* (resources/views/components/rf), dipakai semua halaman web. */
class RfComponentsTest extends TestCase
{
    use RefreshDatabase;

    public function test_icon_renders_known_names_and_nothing_for_unknown_names(): void
    {
        $this->assertStringContainsString('<svg', Blade::render('<x-rf.icon name="check" />'));
        $this->assertStringContainsString('aria-hidden="true"', Blade::render('<x-rf.icon name="check" />'));
        $this->assertSame('', trim(Blade::render('<x-rf.icon name="tidak-ada" />')));
        $this->assertSame('', trim(Blade::render('<x-rf.icon />')));

        $labelled = Blade::render('<x-rf.icon name="search" aria-label="Cari" class="extra" />');
        $this->assertStringContainsString('role="img"', $labelled);
        $this->assertStringContainsString('aria-label="Cari"', $labelled);
        $this->assertStringNotContainsString('aria-hidden', $labelled);
        $this->assertStringContainsString('class="ic extra"', $labelled);
    }

    public function test_every_documented_icon_name_renders(): void
    {
        $names = ['home', 'list', 'calendar', 'swap', 'card', 'grid', 'heart', 'people', 'user', 'chart', 'download', 'upload',
            'lock', 'key', 'search', 'plus', 'check', 'chevron-left', 'chevron-right', 'chevron-down', 'warn', 'info', 'eye',
            'eye-off', 'sun', 'moon', 'x', 'pencil', 'trash', 'arrow-right', 'logout', 'refresh'];

        foreach ($names as $name) {
            $this->assertMatchesRegularExpression('#<svg[^>]*><(?:path|rect|circle)#', Blade::render('<x-rf.icon name="'.$name.'" />'), "Ikon {$name} kosong");
        }
    }

    public function test_pill_has_default_icons_per_tone(): void
    {
        $good = Blade::render('<x-rf.pill tone="good">Terpenuhi</x-rf.pill>');
        $this->assertStringContainsString('rf-pill good', $good);
        $this->assertStringContainsString('<svg', $good);
        $this->assertStringContainsString('Terpenuhi', $good);

        $this->assertStringContainsString('<svg', Blade::render('<x-rf.pill tone="warn">Perlu perhatian</x-rf.pill>'));
        $this->assertStringContainsString('<svg', Blade::render('<x-rf.pill tone="info">Info</x-rf.pill>'));
        $this->assertStringNotContainsString('<svg', Blade::render('<x-rf.pill>Mendatang</x-rf.pill>'));
        $this->assertStringNotContainsString('<svg', Blade::render('<x-rf.pill tone="info" icon="">Rp 25.000</x-rf.pill>'));
        $this->assertStringContainsString('rf-pill neutral', Blade::render('<x-rf.pill tone="merah">X</x-rf.pill>'));
    }

    public function test_room_icon_maps_room_icon_key_and_slot(): void
    {
        $html = Blade::render('<x-rf.room-icon :slot="2" icon="diri" />');
        $this->assertStringContainsString('rf-roomic c2', $html);
        $this->assertStringContainsString('M4 21c0-4', $html); // ikon user

        $this->assertStringContainsString('rf-roomic cn', Blade::render('<x-rf.room-icon :slot="9" icon="memberi" />'));
        $this->assertStringContainsString('rf-roomic cn', Blade::render('<x-rf.room-icon />'));

        $room = new Room(['name' => 'Keluarga', 'icon' => 'keluarga']);
        $room->slot = 3;
        $fromModel = Blade::render('<x-rf.room-icon :room="$room" />', ['room' => $room]);
        $this->assertStringContainsString('rf-roomic c3', $fromModel);
        $this->assertStringContainsString('cx="17.5"', $fromModel); // ikon people
    }

    public function test_room_name_shows_room_and_pos(): void
    {
        $html = Blade::render('<x-rf.room-name :slot="3" room="Keluarga" pos="Dapur dan belanja" />');
        $this->assertStringContainsString('rf-dot c3', $html);
        $this->assertStringContainsString('Keluarga', $html);
        $this->assertStringContainsString('Dapur dan belanja', $html);
        $this->assertStringContainsString('›', $html);

        $only = Blade::render('<x-rf.room-name :slot="1" room="Memberi" />');
        $this->assertStringNotContainsString('›', $only);
    }

    public function test_ring_clamps_the_fill_to_100_percent_but_keeps_the_label(): void
    {
        $half = Blade::render('<x-rf.ring :percent="50" />');
        $this->assertStringContainsString('stroke-dasharray="81.7 81.7"', $half);
        $this->assertStringContainsString('aria-hidden="true"', $half);

        $over = Blade::render('<x-rf.ring :percent="140" warn :size="104" :slot="3" text="140%" label="Keluarga 140 persen" />');
        $this->assertStringContainsString('stroke-dasharray="163.4 0"', $over);
        $this->assertStringContainsString('rf-ring-fg warn', $over);
        $this->assertStringContainsString('width="104"', $over);
        $this->assertStringContainsString('class="c3"', $over);
        $this->assertStringContainsString('>140%</text>', $over);
        $this->assertStringContainsString('aria-label="Keluarga 140 persen"', $over);
    }

    public function test_stack_orders_by_slot_merges_neutral_and_describes_all_segments(): void
    {
        $html = Blade::render('<x-rf.stack label="Pembagian rezeki" legend :segments="$segments" />', ['segments' => [
            ['slot' => 3, 'bp' => 6000, 'label' => 'Keluarga'],
            ['slot' => 1, 'bp' => 1000, 'label' => 'Memberi'],
            ['slot' => 9, 'bp' => 500, 'label' => 'Lain A'],
            ['slot' => 2, 'bp' => 2250, 'label' => 'Diri'],
            ['slot' => null, 'bp' => 250, 'label' => 'Lain B'],
        ]]);

        $this->assertStringContainsString('aria-label="Pembagian rezeki: Memberi 10 persen, Diri 22,5 persen, Keluarga 60 persen, Ruang lain 7,5 persen"', $html);
        $this->assertLessThan(strpos($html, 'class="c2"'), strpos($html, 'class="c1"'));
        $this->assertLessThan(strpos($html, 'class="c3"'), strpos($html, 'class="c2"'));
        $this->assertLessThan(strpos($html, 'class="cn"'), strpos($html, 'class="c3"'));
        $this->assertStringContainsString('width: 22.50%', $html);
        $this->assertStringContainsString('rf-legend', $html);
        $this->assertStringContainsString('Ruang lain 7,5%', $html);
    }

    public function test_stack_skips_zero_segments_in_the_bar_but_keeps_them_in_the_label(): void
    {
        $html = Blade::render('<x-rf.stack :segments="$segments" />', ['segments' => [
            ['slot' => 1, 'bp' => 0, 'label' => 'Memberi'],
            ['slot' => 2, 'bp' => 10000, 'label' => 'Diri'],
        ]]);

        $this->assertStringContainsString('Memberi 0 persen', $html);
        $this->assertStringNotContainsString('class="c1"', $html);
        $this->assertStringContainsString('width: 100.00%', $html);
    }

    public function test_track_and_pace_clamp_their_widths(): void
    {
        $track = Blade::render('<x-rf.track :percent="112" :slot="3" />');
        $this->assertStringContainsString('width: 100%', $track);
        $this->assertStringContainsString('rf-track c3', $track);

        $pace = Blade::render('<x-rf.pace :used="87" :time="77.4" :slot="3" />');
        $this->assertStringContainsString('rf-pace c3', $pace);
        $this->assertStringContainsString('width: 87%', $pace);
        $this->assertStringContainsString('left: 77.4%', $pace);
        $this->assertStringContainsString('aria-label="Terpakai 87 persen; waktu berjalan 77 persen"', $pace);
        $this->assertStringContainsString('width: 100%', Blade::render('<x-rf.pace :used="130" :time="150" />'));
    }

    public function test_tabs_mark_the_active_tab_with_aria_current(): void
    {
        $html = Blade::render('<x-rf.tabs label="Analisa" :items="$items" />', ['items' => [
            ['label' => 'Aliran', 'href' => '/laporan', 'active' => true],
            ['label' => 'Tak terlacak', 'href' => '/t', 'count' => 3],
            ['label' => 'Semua', 'href' => '/s', 'count' => 47, 'muted' => true],
        ]]);

        $this->assertSame(1, substr_count($html, 'aria-current="page"'));
        $this->assertStringContainsString('class="rf-tab on"', $html);
        $this->assertStringContainsString('aria-label="Analisa"', $html);
        $this->assertStringContainsString('<span class="rf-count">3</span>', $html);
        $this->assertStringContainsString('<span class="rf-sub">47</span>', $html);
    }

    public function test_page_head_renders_title_sub_lead_and_actions(): void
    {
        $html = Blade::render('<x-rf.page-head sub="Oktober 2026" title="Transaksi" lead="Semua catatanmu."><x-slot:actions><a href="/x">Ekspor</a></x-slot:actions></x-rf.page-head>');

        $this->assertStringContainsString('<h1 class="rf-h1">Transaksi</h1>', $html);
        $this->assertStringContainsString('Oktober 2026', $html);
        $this->assertStringContainsString('Semua catatanmu.', $html);
        $this->assertStringContainsString('rf-page-actions', $html);
        $this->assertStringContainsString('Ekspor', $html);

        $plain = Blade::render('<x-rf.page-head title="Jadwal" />');
        $this->assertStringNotContainsString('rf-sub', $plain);
        $this->assertStringNotContainsString('rf-lead', $plain);
        $this->assertStringNotContainsString('rf-page-actions', $plain);
    }

    public function test_banner_defaults_icon_per_tone_and_forwards_attributes(): void
    {
        $html = Blade::render('<x-rf.banner tone="warn" role="status">Pos ini melewati jatah.</x-rf.banner>');
        $this->assertStringContainsString('rf-banner warn', $html);
        $this->assertStringContainsString('role="status"', $html);
        $this->assertStringContainsString('<svg', $html);
        $this->assertStringContainsString('Pos ini melewati jatah.', $html);

        $this->assertStringNotContainsString('<svg', Blade::render('<x-rf.banner icon="">Tanpa ikon</x-rf.banner>'));
    }
}
