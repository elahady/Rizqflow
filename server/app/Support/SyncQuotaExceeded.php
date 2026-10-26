<?php

namespace App\Support;

use RuntimeException;

/** Push ditolak karena kuota sinkron akun akan terlewati (lihat App\Models\SyncQuota::tambahPemakaian). */
class SyncQuotaExceeded extends RuntimeException {}
