package com.roziqrizal.rizqflow.ui.paywall

import com.roziqrizal.rizqflow.domain.sync.RemoteError

/**
 * Penjaga Sync di klien (Tahap 9, "Penguncian Sync di klien"). Layar Ruang Keluarga, pemindai undangan QR, dan
 * sinkron manual dibuka hanya bila [com.roziqrizal.rizqflow.domain.entitlement.Entitlements] mengizinkan fiturnya
 * (keduanya butuh Plan.SYNC); tanpa itu paywall varian Sync yang dibuka, bukan pesan gagal.
 *
 * Jawaban 403 "Butuh langganan Sync" dari server dipetakan ke ajakan yang sama. Penjaga klien mencegah request
 * sebelum terkirim; pemetaan ini menangani kasus ketika request tetap terkirim dan ditolak (paket turun di tengah sesi).
 */
fun RemoteError.opensSyncPaywall(): Boolean = this == RemoteError.SyncRequired
