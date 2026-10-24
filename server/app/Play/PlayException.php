<?php

namespace App\Play;

use RuntimeException;

/**
 * Dasar semua kegagalan verifikasi Play. Pesan tidak pernah memuat token pembelian, dan
 * pengecualian Google/Guzzle asli sengaja tidak disambung sebagai `previous`: pesan Guzzle memuat
 * URL permintaan, dan token pembelian ada di URL itu.
 */
abstract class PlayException extends RuntimeException {}
