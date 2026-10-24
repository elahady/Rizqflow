<?php

namespace App\Http\Controllers;

use Symfony\Component\HttpFoundation\Response;

/**
 * Unduhan rekap zakat tahunan (PDF dan CSV) untuk keperluan SPT, lihat butir "Desain web baru" di
 * docs/konsep.md. Stub fase 1 desain web baru: route sudah ada, isi menyusul di fase berikutnya.
 */
class ZakatRekapController extends Controller
{
    public function pdf(int $year): Response
    {
        abort(501, 'Rekap zakat PDF belum tersedia.');
    }

    public function csv(int $year): Response
    {
        abort(501, 'Rekap zakat CSV belum tersedia.');
    }
}
