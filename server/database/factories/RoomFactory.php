<?php

namespace Database\Factories;

use App\Models\Room;
use App\Models\User;
use Illuminate\Database\Eloquent\Factories\Factory;

/**
 * Ruang Keluarga (Mencukupi) tanpa aturan alokasi; atur `allocation_percent` per tes. Jatah dan
 * terpakai tidak disimpan di ruang: catat pemasukan lewat App\Support\Ledger untuk membentuknya.
 *
 * @extends Factory<Room>
 */
class RoomFactory extends Factory
{
    public function definition(): array
    {
        return [
            'user_id' => User::factory(),
            'name' => 'Keluarga',
            'icon' => 'keluarga',
            'type' => Room::TYPE_MENCUKUPI,
            'slot' => 3,
            'sort_order' => 1,
        ];
    }

    public function type(string $type): static
    {
        return $this->state(['type' => $type]);
    }

    public function percent(int $percent): static
    {
        return $this->state(['allocation_percent' => $percent]);
    }
}
