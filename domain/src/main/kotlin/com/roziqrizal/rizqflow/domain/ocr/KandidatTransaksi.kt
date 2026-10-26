package com.roziqrizal.rizqflow.domain.ocr

import com.roziqrizal.rizqflow.domain.money.Money
import java.time.LocalDate

/** Arah aliran uang yang terbaca dari teks; [TIDAK_DIKETAHUI] bila teks tidak memberi petunjuk yang jelas. */
enum class ArahKandidat { MASUK, KELUAR, TIDAK_DIKETAHUI }

/**
 * Kandidat transaksi hasil pembacaan teks (OCR gambar atau ekstraksi PDF). Belum pernah disimpan:
 * pengguna wajib meninjau setiap kandidat sebelum menjadi transaksi (aturan "Selesai bila" Tahap 11).
 *
 * @property nominal selalu bilangan bulat rupiah, tidak pernah floating point.
 * @property tanggal `null` bila tidak ada tanggal yang bisa diurai dari baris maupun dokumen.
 * @property deskripsi teks keterangan dari baris asal, sudah dirapikan; bisa kosong.
 * @property baris baris teks asal, disimpan supaya pengguna bisa melihat dari mana kandidat berasal.
 */
data class KandidatTransaksi(
    val nominal: Money,
    val arah: ArahKandidat,
    val tanggal: LocalDate?,
    val deskripsi: String,
    val baris: String,
)
