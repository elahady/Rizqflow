<?php

namespace App\Rules;

use Closure;
use Illuminate\Contracts\Validation\ValidationRule;

/**
 * Aturan kata sandi web: minimal 8 karakter dan bukan salah satu kata sandi paling umum.
 * Daftar di storage tidak dipakai - sengaja ditanam di kode supaya tidak butuh panggilan jaringan
 * (beda dari Password::uncompromised()) dan tesnya deterministik.
 */
class StrongPassword implements ValidationRule
{
    public const MIN_LENGTH = 8;

    private const COMMON = [
        'password', 'password1', 'password12', 'password123', 'password1234', 'passw0rd', 'p@ssw0rd', 'p@ssword',
        '12345678', '123456789', '1234567890', '12345678910', '11111111', '111111111', '00000000', '000000000',
        '88888888', '99999999', '12341234', '87654321', '987654321', '123123123', '1q2w3e4r', '1q2w3e4r5t',
        'qwertyui', 'qwerty123', 'qwertyuiop', 'asdfghjk', 'asdfghjkl', 'zxcvbnm1', 'zxcvbnm123', 'abcd1234',
        'abc12345', 'abcdefgh', 'abcdefg1', 'iloveyou', 'iloveyou1', 'sunshine', 'princess', 'football',
        'baseball', 'superman', 'trustno1', 'letmein1', 'welcome1', 'welcome123', 'monkey123', 'dragon123',
        'master123', 'shadow123', 'michael1', 'jennifer', 'charlie1', 'admin123', 'administrator', 'adminadmin',
        'changeme', 'default123', 'test1234', 'testtest', 'testing123', 'login123', 'secret123', 'rahasia123',
        'indonesia', 'indonesia1', 'indonesia123', 'indonesia45', 'sayangku', 'sayang123', 'cintaku1', 'bismillah',
        'bismillah1', 'alhamdulillah', 'allahuakbar', 'muhammad1', 'rahasia1', 'kamukamu', 'jakarta123',
        'bandung123', 'surabaya1', 'merdeka45', 'garudapancasila', 'rizqflow', 'rizqflow1', 'rizqflow123',
        'alkaukaba', 'roziqrizal', 'passwordku', 'katasandi', 'katasandi1', 'kata sandi', 'sandi1234',
        'hahahaha', 'asdasdasd', 'qazwsxedc', 'qweasdzxc', 'pass1234', 'pass12345', 'passpass', 'pa55word',
        'football1', 'liverpool', 'manchester', 'barcelona', 'chelsea1', 'starwars', 'pokemon1', 'whatever',
        'computer', 'internet', 'mustang1', 'access14', 'google123', 'facebook', 'facebook1', 'instagram',
    ];

    public function validate(string $attribute, mixed $value, Closure $fail): void
    {
        if (! is_string($value)) {
            return;
        }

        if (mb_strlen($value) < self::MIN_LENGTH) {
            $fail('Kata sandi minimal '.self::MIN_LENGTH.' karakter.');

            return;
        }

        if (in_array(mb_strtolower($value), self::COMMON, true) || $this->isRepeated($value)) {
            $fail('Kata sandi terlalu umum. Pilih yang lebih sulit ditebak.');
        }
    }

    /** "aaaaaaaa", "abababab": satu pola pendek yang diulang sampai habis. */
    private function isRepeated(string $value): bool
    {
        return (bool) preg_match('/^(.{1,3})\1+$/su', $value);
    }
}
