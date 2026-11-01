package com.roziqrizal.rizqflow.domain.sync

/**
 * Batas satu request push (sync-desain.md bagian C "Batas"): paling banyak [maxRows] baris dan
 * [maxBytes] byte perkiraan badan JSON. Server sendiri menerima sampai 500 baris dan menolak badan
 * berlebih dengan 413; batas klien sengaja lebih ketat supaya tidak bergantung pada penolakan itu
 * (dan pada `client_max_body_size` Nginx 1 MB yang belum diperiksa, Tahap 12).
 */
data class SyncPushLimits(val maxRows: Int = 200, val maxBytes: Int = 800 * 1024) {
    init {
        require(maxRows >= 1) { "maxRows minimal 1" }
        require(maxBytes >= 1) { "maxBytes minimal 1" }
    }
}

/**
 * Memotong daftar baris unggahan menjadi beberapa batch yang masing-masing memenuhi [SyncPushLimits].
 * Urutan masukan dipertahankan (batch pertama berisi baris paling awal). Murni, tanpa jaringan.
 */
object SyncPushBatcher {
    /** Perkiraan byte pembungkus `{"entries":[` ... `]}` satu request. */
    const val REQUEST_OVERHEAD_BYTES = 16

    /**
     * Perkiraan byte satu baris di badan JSON (`SyncEntryUploadDto`): nama kunci, tanda kutip, koma, dan
     * nilai. Dibulatkan ke atas (konservatif): lebih baik batch sedikit lebih kecil daripada ditolak 413.
     */
    fun estimateBytes(entry: SyncLogEntryUpload): Int {
        // {"room_id":null,"record_type":"","record_id":"","device_id":"","payload":"","deleted":false,
        //  "changed_at":"","changed_at_ms":0000000000000}, — kerangka tetap sekitar 150 byte.
        val fixed = 160
        return fixed +
            utf8Length(entry.roomId) +
            utf8Length(entry.recordType) +
            utf8Length(entry.recordId) +
            utf8Length(entry.deviceId) +
            utf8Length(entry.payload) +
            utf8Length(entry.changedAt) +
            20 // angka changed_at_ms dan cadangan
    }

    /**
     * Memotong [items] menjadi batch: tiap batch paling banyak [SyncPushLimits.maxRows] baris dan jumlah
     * [sizeOf] ditambah [REQUEST_OVERHEAD_BYTES] tidak melebihi [SyncPushLimits.maxBytes]. Satu baris yang
     * sendirian sudah melebihi batas byte tetap dikirim sebagai batch tunggal (tidak dibuang diam-diam;
     * server yang memutuskan, dan payload per baris sendiri dibatasi 64 KB oleh amplop).
     */
    fun <T> split(items: List<T>, limits: SyncPushLimits = SyncPushLimits(), sizeOf: (T) -> Int): List<List<T>> {
        val batches = mutableListOf<List<T>>()
        var current = mutableListOf<T>()
        var currentBytes = REQUEST_OVERHEAD_BYTES
        for (item in items) {
            val size = sizeOf(item)
            val fullByRows = current.size >= limits.maxRows
            val fullByBytes = current.isNotEmpty() && currentBytes + size > limits.maxBytes
            if (fullByRows || fullByBytes) {
                batches += current
                current = mutableListOf()
                currentBytes = REQUEST_OVERHEAD_BYTES
            }
            current += item
            currentBytes += size
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    private fun utf8Length(value: String?): Int = value?.toByteArray(Charsets.UTF_8)?.size ?: 4
}
