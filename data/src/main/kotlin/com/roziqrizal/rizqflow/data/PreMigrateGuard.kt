package com.roziqrizal.rizqflow.data

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Pagar migrasi (Tahap 1 sync-desain.md bagian E, USULAN Claude): sebelum membuka database yang
 * `user_version`-nya di bawah [targetVersion], salin berkas `.db` (dan `-wal`/`-shm` bila ada) ke
 * `<nama>.db.premigrate`, `.premigrate-wal`, `.premigrate-shm`. Hanya SATU salinan: salinan baru
 * mengganti yang lama. Bila migrasi merusak data, salinan itu keadaan terakhir sebelum migrasi.
 *
 * - **Masa simpan**: salinan dihapus hanya bila SUDAH [keepForOpens] pembukaan sukses sesudah pembukaan yang
 *   memigrasi DAN umurnya sudah [minAgeMillis] (7 hari). Syarat umur dipilih karena paling sederhana: pembukaan
 *   database juga dilakukan worker pengingat, widget, dan tindakan notifikasi (`AccountWorkspace.open`), jadi hitungan
 *   pembukaan saja bisa tercapai dalam beberapa menit tanpa pengguna pernah melihat hasil migrasinya. Umur tidak bisa
 *   dipercepat oleh pembukaan mana pun. Status ada di `<nama>.db.premigrate.opens` (baris 1 = jumlah pembukaan sukses,
 *   baris 2 = waktu salinan dibuat).
 * - **Kegagalan menyalin tidak pernah menggagalkan pembukaan aplikasi**: semua pengecualian ditangkap
 *   dan dicatat lewat [log] hanya dengan nama kelas galat, tanpa isi data atau path.
 * - Salinan ditulis dulu ke berkas `.tmp`, baru dipindahkan. Bila pemindahan salah satu berkas gagal, ketiga berkas
 *   salinan (utama, `-wal`, `-shm`) dibersihkan supaya tidak tersisa salinan campuran; salinan lama yang baik memang
 *   ikut hilang pada kasus itu (lebih baik tanpa salinan daripada salinan yang tidak konsisten). Ruang kosong dicek
 *   lebih dulu; kurang ruang berarti dilewati dan dicatat.
 * - Bila versi database tidak terbaca dan SUDAH ada salinan utuh, salinan itu dipertahankan (tidak ditimpa, hitungan
 *   tidak direset): database yang tidak terbaca bisa saja sudah rusak, dan menimpa salinan baik dengannya akan
 *   menghilangkan satu-satunya cadangan. Bila belum ada salinan sama sekali, tetap disalin.
 * - Versi dibaca read-only dengan [DatabaseErrorHandler] kosong: penanganan bawaan Android menghapus berkas database yang
 *   dianggap korup, dan pagar ini tidak boleh pernah menghapus berkas pengguna.
 * - Dua pembukaan bersamaan (UI, worker, widget) di satu proses dilindungi `synchronized` per path berkas; antar-proses
 *   tidak dilindungi (aplikasi ini satu proses).
 * - [openHelperFactory] memanggil [beforeOpen] pada akses database pertama (thread yang pertama menyentuh Room, biasanya
 *   eksekutor kueri/IO), bukan saat `LocalLedger.open` dipanggil dari komposisi di thread utama.
 * - Salinan memuat data keuangan mentah di penyimpanan privat aplikasi yang sama dengan database aslinya, jadi tidak
 *   menambah paparan; penghapusan database lewat `deleteDatabase` (ruang demo) tidak menghapusnya.
 */
class PreMigrateGuard(
    private val dbFile: File,
    private val targetVersion: Int = DEFAULT_TARGET_VERSION,
    private val keepForOpens: Int = DEFAULT_KEEP_FOR_OPENS,
    private val minAgeMillis: Long = DEFAULT_MIN_AGE_MILLIS,
    private val readUserVersion: (File) -> Int? = ::readUserVersionFromSqlite,
    private val copyFile: (File, File) -> Unit = ::copyReplacing,
    private val moveFile: (File, File) -> Unit = ::moveReplacing,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val main get() = File(dbFile.path + SUFFIX)
    private val wal get() = File(dbFile.path + SUFFIX + "-wal")
    private val shm get() = File(dbFile.path + SUFFIX + "-shm")
    private val state get() = File(dbFile.path + SUFFIX + ".opens")
    private val lock: Any = locks.computeIfAbsent(dbFile.absolutePath) { Any() }

    private var copiedByThisInstance = false

    @Volatile
    private var beforeOpenDone = false

    /** Berkas salinan utama; ada hanya selama masa simpan. */
    val premigrateFile: File get() = main

    /** Membuat salinan bila perlu. Tidak pernah melempar. */
    fun beforeOpen() {
        synchronized(lock) {
            try {
                if (!dbFile.exists() || dbFile.length() == 0L) return
                val version = readUserVersion(dbFile)
                if (version != null && version >= targetVersion) return
                if (version == null && hasUsableCopy()) {
                    log("Versi database tidak terbaca; salinan pra-migrasi yang ada dipertahankan")
                    return
                }
                copyAll(version)
            } catch (t: Throwable) {
                log("Pagar migrasi gagal (${t.javaClass.simpleName}); pembukaan tetap dilanjutkan tanpa salinan baru")
            }
        }
    }

    /**
     * Dipanggil setelah database terbuka sukses; menghitung, dan menghapus salinan bila hitungan dan umurnya
     * sudah cukup. Tidak pernah melempar.
     */
    fun onOpenSucceeded() {
        synchronized(lock) {
            try {
                if (!main.exists()) return
                if (copiedByThisInstance) {
                    // Pembukaan yang melakukan migrasi tidak dihitung.
                    copiedByThisInstance = false
                    return
                }
                val (previousOpens, createdAt) = readState()
                val opens = previousOpens + 1
                if (opens >= keepForOpens && nowMillis() - createdAt >= minAgeMillis) {
                    deleteAll()
                    log("Salinan pra-migrasi dihapus setelah $opens pembukaan sukses dan umur cukup")
                } else {
                    writeState(opens, createdAt)
                }
            } catch (t: Throwable) {
                log("Pagar migrasi gagal menghitung pembukaan (${t.javaClass.simpleName})")
            }
        }
    }

    /** Callback Room yang meneruskan `onOpen` ke [onOpenSucceeded]. */
    fun callback(): RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            onOpenSucceeded()
        }
    }

    /**
     * Pabrik helper yang menjalankan [beforeOpen] sekali, tepat sebelum akses database pertama, lalu meneruskan
     * semuanya ke helper framework bawaan Room. Perilaku pembukaan database tidak berubah.
     */
    fun openHelperFactory(): SupportSQLiteOpenHelper.Factory = SupportSQLiteOpenHelper.Factory { configuration ->
        GuardedOpenHelper(FrameworkSQLiteOpenHelperFactory().create(configuration), ::beforeOpenOnce)
    }

    private fun beforeOpenOnce() {
        if (beforeOpenDone) return
        synchronized(lock) {
            if (beforeOpenDone) return
            beforeOpenDone = true
            beforeOpen()
        }
    }

    private fun hasUsableCopy(): Boolean = main.exists() && main.length() > 0L

    private fun copyAll(fromVersion: Int?) {
        val sources = listOf(
            dbFile to main,
            File(dbFile.path + "-wal") to wal,
            File(dbFile.path + "-shm") to shm,
        )
        val needed = sources.filter { it.first.exists() }.sumOf { it.first.length() } + SPACE_MARGIN_BYTES
        val dir = dbFile.absoluteFile.parentFile
        if (dir != null && dir.usableSpace < needed) {
            log("Ruang penyimpanan tidak cukup untuk salinan pra-migrasi; dilewati")
            return
        }
        // Tulis semua ke berkas sementara dulu; baru pindahkan bila semuanya berhasil.
        val staged = mutableListOf<Pair<File, File>>() // sementara -> tujuan
        try {
            for ((source, target) in sources) {
                if (!source.exists()) continue
                val tmp = File(target.path + ".tmp")
                copyFile(source, tmp)
                staged += tmp to target
            }
        } catch (t: Throwable) {
            cleanTemporaries(sources)
            throw t
        }
        try {
            for ((tmp, target) in staged) moveFile(tmp, target)
        } catch (t: Throwable) {
            // Pemindahan parsial = salinan campuran (utama baru + pendamping lama, dst.): bersihkan ketiganya.
            cleanTemporaries(sources)
            deleteAll()
            throw t
        }
        // Berkas pendamping yang tidak ada di sumber sekarang tidak boleh tersisa dari salinan lama.
        for ((source, target) in sources) if (!source.exists()) target.delete()
        writeState(0, nowMillis())
        copiedByThisInstance = true
        log("Salinan pra-migrasi dibuat (versi ${fromVersion ?: "tidak terbaca"} -> $targetVersion)")
    }

    private fun cleanTemporaries(sources: List<Pair<File, File>>) {
        for ((_, target) in sources) File(target.path + ".tmp").delete()
    }

    private fun readState(): Pair<Int, Long> {
        val lines = if (state.exists()) state.readLines() else emptyList()
        val opens = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
        val createdAt = lines.getOrNull(1)?.trim()?.toLongOrNull() ?: main.lastModified()
        return opens to createdAt
    }

    private fun writeState(opens: Int, createdAt: Long) {
        state.writeText("$opens\n$createdAt")
    }

    private fun deleteAll() {
        main.delete()
        wal.delete()
        shm.delete()
        state.delete()
    }

    companion object {
        /** Versi skema `RizqflowDatabase` saat ini; dijaga sama oleh tes `PreMigrateGuardTest`. */
        const val DEFAULT_TARGET_VERSION = 9
        const val DEFAULT_KEEP_FOR_OPENS = 3

        /** Umur minimal salinan sebelum boleh dihapus: 7 hari. */
        const val DEFAULT_MIN_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val SUFFIX = ".premigrate"
        private const val TAG = "PreMigrateGuard"
        private const val SPACE_MARGIN_BYTES = 1L * 1024 * 1024
        private val locks = ConcurrentHashMap<String, Any>()

        internal fun readUserVersionFromSqlite(file: File): Int? = try {
            // Penangan galat KOSONG: bawaan Android menghapus berkas yang dianggap korup; pagar tidak boleh menghapus apa pun.
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { it.version }
        } catch (e: Exception) {
            null
        }

        internal fun copyReplacing(source: File, target: File) {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        internal fun moveReplacing(source: File, target: File) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** Meneruskan semuanya ke [delegate], hanya menjalankan [before] tepat sebelum database diakses. */
private class GuardedOpenHelper(
    private val delegate: SupportSQLiteOpenHelper,
    private val before: () -> Unit,
) : SupportSQLiteOpenHelper {
    override val databaseName: String? get() = delegate.databaseName

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) = delegate.setWriteAheadLoggingEnabled(enabled)

    override val writableDatabase: SupportSQLiteDatabase
        get() {
            before()
            return delegate.writableDatabase
        }

    override val readableDatabase: SupportSQLiteDatabase
        get() {
            before()
            return delegate.readableDatabase
        }

    override fun close() = delegate.close()
}
