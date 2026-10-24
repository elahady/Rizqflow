package com.roziqrizal.rizqflow.data.remote

import android.content.Context
import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import com.roziqrizal.rizqflow.domain.sync.ApiTokenStore
import java.time.Instant

/**
 * Token API Sanctum di SharedPreferences biasa, mengikuti pola PurchaseStore/ThemePreference di
 * `:app` — bukan Keystore, karena ini token bisa-dicabut (bukan kunci enkripsi jangka panjang
 * seperti Tahap 13), dan pertahanan sebenarnya ada di server (cabut token), bukan sembunyikan di
 * perangkat (lihat catatan "Konsekuensi yang diterima" di [ApiTokenStore]).
 *
 * Satu berkas per akun ([accountId]), bukan satu berkas untuk seluruh aplikasi: token adalah milik
 * akun server yang masuk, jadi akun Google lain di perangkat yang sama tidak pernah membaca token
 * akun sebelumnya (yang tidak dibersihkan saat Keluar). Tanpa ini, klaim pembelian, sync, dan Hapus
 * akun milik akun baru bisa jalan atas nama akun lama. Nama berkas memakai hash pengenal akun, sama
 * dengan nama database akun (lihat [AccountStorage]).
 *
 * Masa berlaku (Tahap 12) disimpan di berkas yang sama sebagai milidetik epoch di kunci terpisah.
 * Berkas lama yang hanya punya token (tanpa kunci itu) tetap terbaca persis seperti dulu; masa
 * berlakunya null, artinya belum diketahui. Token dan tanggalnya ditulis dalam satu `edit()` supaya
 * tidak pernah ada token baru dengan tanggal token lama.
 */
class SharedPrefsApiTokenStore(context: Context, accountId: String) : ApiTokenStore {
    private val prefs = context.getSharedPreferences(
        PREF_FILE_PREFIX + AccountStorage.scopeKey(accountId),
        Context.MODE_PRIVATE,
    )

    init {
        // Berkas lama satu-untuk-semua-akun (versi sebelum token dipisah per akun): pemiliknya tidak
        // diketahui, jadi tidak dipakai dan dibuang. Akibatnya akun yang sudah masuk lebih dulu
        // memperoleh token server baru pada masuk dengan Google berikutnya.
        context.getSharedPreferences(LEGACY_PREF_FILE_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    override fun read(): String? = prefs.getString(KEY_TOKEN, null)

    override fun expiresAt(): Instant? {
        if (read() == null || !prefs.contains(KEY_EXPIRES_AT_MS)) return null
        return Instant.ofEpochMilli(prefs.getLong(KEY_EXPIRES_AT_MS, 0L))
    }

    // commit() (sinkron, mengembalikan hasil) dan bukan apply(): token hasil putar menggantikan token yang
    // sudah dicabut server, jadi pemanggil perlu tahu bila penulisan ke disk gagal. Berkasnya kecil.
    //
    // Pemanggil yang menyimpan token hasil putar atau masuk (TokenRefresher, ServerSession.connect) menjalankannya
    // di Dispatchers.IO supaya penulisan sinkron ini tidak menahan thread utama. Keluar akun ([clear]) sengaja
    // tetap sinkron di thread pemanggil: lihat catatan di sana.
    override fun save(token: String, expiresAt: Instant?): Boolean = save(token, expiresAt, savedAt = null)

    override fun save(token: String, expiresAt: Instant?, savedAt: Instant?): Boolean =
        prefs.edit().apply {
            putString(KEY_TOKEN, token)
            if (expiresAt != null) putLong(KEY_EXPIRES_AT_MS, expiresAt.toEpochMilli()) else remove(KEY_EXPIRES_AT_MS)
            if (savedAt != null) putLong(KEY_SAVED_AT_MS, savedAt.toEpochMilli()) else remove(KEY_SAVED_AT_MS)
            // Satu edit dengan token: begitu ada token tersimpan, catatan "pernah punya" ikut tertulis.
            putBoolean(KEY_EVER_HELD, true)
        }.commit()

    override fun lastSavedAt(): Instant? {
        if (read() == null || !prefs.contains(KEY_SAVED_AT_MS)) return null
        return Instant.ofEpochMilli(prefs.getLong(KEY_SAVED_AT_MS, 0L))
    }

    // Token dari berkas versi sebelum catatan ini ada tetap dihitung: ada token berarti pernah punya.
    override fun hasEverHeldToken(): Boolean = read() != null || prefs.getBoolean(KEY_EVER_HELD, false)

    override fun clear() {
        // commit() juga di sini: keluar akun TIDAK menunggu jaringan, tapi token lokal harus sudah hilang dari disk
        // sebelum proses bisa mati (apply() menulis di latar dan bisa kalah dari proses yang dimatikan). Sengaja
        // TIDAK dipindah ke thread latar: di onClick Keluar pembersihan harus pasti, tidak boleh terlewat. Satu
        // berkas kecil; biaya commit() di thread utama jauh lebih murah daripada token yang tertinggal di disk.
        val hadToken = read() != null
        prefs.edit().apply {
            remove(KEY_TOKEN)
            remove(KEY_EXPIRES_AT_MS)
            remove(KEY_SAVED_AT_MS)
            // Akun server-nya masih ada walau tokennya dibersihkan: ingat bahwa perangkat ini pernah memegangnya.
            if (hadToken) putBoolean(KEY_EVER_HELD, true)
        }.commit()
    }

    override fun forgetAccount() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREF_FILE_PREFIX = "rizqflow_api_token_"
        private const val LEGACY_PREF_FILE_NAME = "rizqflow_api_token"
        private const val KEY_TOKEN = "token"
        private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
        private const val KEY_SAVED_AT_MS = "saved_at_ms"
        private const val KEY_EVER_HELD = "ever_held"
    }
}
