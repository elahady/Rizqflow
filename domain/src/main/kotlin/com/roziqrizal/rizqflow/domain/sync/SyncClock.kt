package com.roziqrizal.rizqflow.domain.sync

/**
 * Jam hibrida ringan untuk cap waktu sinkron (sync-desain.md bagian D, USULAN Claude, belum
 * dikonfirmasi pemilik): `stamp = max(jamPerangkat + offsetServer, changedAtRekamanLokal + 1)`.
 *
 * - **Offset server** dikoreksi dari `server_time_ms` (jawaban server, tahap berikutnya) supaya jam
 *   perangkat yang salah beberapa menit tidak membuat edit baru kalah dari versi lama.
 * - **Floor per rekaman** (`changedAtRekamanLokal + 1`) menjamin edit sesudah melihat versi V selalu
 *   bercap lebih besar dari V walau jam perangkat tertinggal.
 * - **Tidak pernah turun, PER INSTANCE**: cap yang keluar dari satu instance tidak pernah lebih kecil dari cap
 *   sebelumnya, juga bila jam perangkat mundur atau offset diturunkan. Jaminan ini hanya berlaku selama instance
 *   hidup: satu instance per `LocalLedger` (jadi per ruang kerja akun), disimpan di memori, dan HILANG saat proses
 *   mati; ia bukan jaminan global antar-perangkat atau antar-proses. Yang bertahan melewati proses mati adalah
 *   lantai per rekaman (`changed_at` lokal + 1), karena nilainya disimpan di `sync_record`.
 * - **Risiko jam jauh ke depan**: bila jam perangkat sempat melompat jauh ke depan (salah setel), cap yang keluar ikut
 *   besar dan mengalahkan edit perangkat lain yang bercap jam benar sampai waktu sungguhan menyusul; di dalam satu
 *   instance cap itu juga "mengunci" nilai tinggi. Cap per rekaman tetap naik +1 tiap edit, jadi urutan lokal
 *   tidak rusak. **Tahap 2**: [SyncCoordinator] memanggil [stamp] tepat sebelum push (bukan di jalur tulis lokal
 *   biasa) supaya [serverOffsetMillis] ikut mengoreksi cap yang dikirim begitu perangkat ini pernah sekali
 *   tersambung ke server. Kelas ini sendiri TIDAK menolak atau memangkas (clamp) cap yang jauh melewati waktu
 *   server — ia hanya menjumlahkan offset dan menjaga lantai per rekaman; sebelum koneksi pertama (offset masih
 *   0) cap yang keluar masih bisa sebesar jam perangkat yang salah itu, karena belum ada apa pun untuk
 *   dikoreksi terhadap (tidak terhindarkan tanpa kontak server sama sekali).
 *
 * Jam disuntikkan ([deviceNowMillis]) supaya tes tidak bergantung pada waktu sistem. Kelas ini
 * hanya menjaga status di memori; tidak ada I/O. Aman dipakai dari beberapa thread.
 */
class SyncClock(private val deviceNowMillis: () -> Long = System::currentTimeMillis) {
    private var offsetMillis: Long = 0L
    private var lastStamp: Long = Long.MIN_VALUE

    /** Selisih (waktu server - jam perangkat) yang sedang dipakai; 0 sebelum pernah dikoreksi. */
    @get:Synchronized
    val serverOffsetMillis: Long get() = offsetMillis

    /** Mengganti offset server. Boleh naik atau turun; cap yang sudah keluar tidak ikut berubah. */
    @Synchronized
    fun setServerOffset(offsetMillis: Long) {
        this.offsetMillis = offsetMillis
    }

    /**
     * Jam perangkat MENTAH, tanpa [serverOffsetMillis] atau lantai per rekaman — dasar pemanggil
     * ([SyncCoordinator]) menghitung ulang offset dari balasan server: `offset baru = server_time_ms
     * - rawDeviceNowMillis()`. Sengaja BUKAN `System.currentTimeMillis()` langsung di sisi pemanggil:
     * harus jam SUMBER YANG SAMA dengan yang dipakai [stamp] (relevan terutama saat [deviceNowMillis]
     * disuntik beda dari jam sistem, mis. di tes), supaya offset yang dihitung benar-benar mengoreksi
     * jam yang dipakai [stamp], bukan jam lain yang kebetulan berbeda.
     */
    fun rawDeviceNowMillis(): Long = deviceNowMillis()

    /**
     * Cap untuk perubahan lokal sekarang. [recordChangedAtMillis] adalah `changed_at` rekaman yang
     * sama saat ini di penyimpanan lokal (null bila rekaman baru).
     */
    @Synchronized
    fun stamp(recordChangedAtMillis: Long? = null): Long {
        var candidate = saturatedAdd(deviceNowMillis(), offsetMillis)
        if (recordChangedAtMillis != null) candidate = maxOf(candidate, saturatedAdd(recordChangedAtMillis, 1L))
        candidate = maxOf(candidate, lastStamp)
        lastStamp = candidate
        return candidate
    }

    private fun saturatedAdd(a: Long, b: Long): Long {
        val sum = a + b
        // Luapan bertanda: kedua operan bertanda sama tetapi hasilnya berbeda tanda.
        return if (((a xor sum) and (b xor sum)) < 0) (if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE) else sum
    }
}
