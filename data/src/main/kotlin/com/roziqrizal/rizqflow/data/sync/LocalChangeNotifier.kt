package com.roziqrizal.rizqflow.data.sync

/**
 * Penghubung "penyimpanan lokal menambah baris kotor" ke pemicu sync otomatis (Tahap 6 sync-desain.md bagian H, pemicu
 * 2). [SyncRecorder] memanggil [fire] setiap menandai satu rekaman kotor atau tombstone. Pemakainya (`AccountWorkspace`)
 * memasang [listener] SESUDAH `LocalLedger` jadi, karena orkestrator sync butuh objek yang dibuat setelah repositori;
 * sebelum itu (dan di tes yang tidak memasangnya) [fire] tidak melakukan apa pun.
 *
 * Aturan: [listener] harus murah dan tidak menyentuh database atau jaringan, karena [fire] dipanggil DI DALAM
 * transaksi Room penulisan data (lihat `AutoSyncOrchestrator.onLocalChange`: hanya menandai dan mengatur timer
 * debounce). Galat dari [listener] tidak boleh menggagalkan penyimpanan, jadi ditelan di sini.
 *
 * Penerapan hasil pull TIDAK memanggil [fire]: `SyncRecorder` berhenti lebih dulu di konteks `applying`, sehingga pull
 * tidak memicu push balik (penjaga loop sync).
 */
class LocalChangeNotifier {
    @Volatile
    var listener: (() -> Unit)? = null

    fun fire() {
        try {
            listener?.invoke()
        } catch (_: Exception) {
            // Pemicu sync tidak pernah boleh menggagalkan penyimpanan lokal.
        }
    }
}
