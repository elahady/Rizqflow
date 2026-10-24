<?php

namespace Database\Factories;

use App\Models\Category;
use App\Models\Room;
use Illuminate\Database\Eloquent\Factories\Factory;

/**
 * @extends Factory<Category>
 */
class CategoryFactory extends Factory
{
    public function definition(): array
    {
        return [
            'room_id' => Room::factory(),
            'name' => 'Belanja',
            'weight' => null,
            'is_system' => false,
            'archived' => false,
            'sort_order' => 1,
        ];
    }

    /** Pos sistem Tak terlacak. */
    public function untracked(): static
    {
        return $this->state(['name' => Category::UNTRACKED, 'is_system' => true]);
    }
}
