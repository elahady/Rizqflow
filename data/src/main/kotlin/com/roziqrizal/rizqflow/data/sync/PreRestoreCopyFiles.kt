package com.roziqrizal.rizqflow.data.sync

import android.util.Log
import com.roziqrizal.rizqflow.domain.sync.PreRestoreCopyStat
import com.roziqrizal.rizqflow.domain.sync.PreRestoreCopyStore
import com.roziqrizal.rizqflow.domain.sync.SqliteDatabaseCheck
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Akhiran berkas di samping database akun (`<nama>.db<akhiran>`) untuk salinan pemulihan lokal. */
object PreRestoreFiles {
    /** Salinan otomatis sebelum "Pulihkan" menimpa data lokal ([PreRestoreBackup]). */
    const val PRERESTORE = ".prerestore"

    /** `.prerestore` yang SUDAH dipakai untuk mengembalikan data (lepas dari menu; dibersihkan [PreRestoreCleaner] sesudah 30 hari). */
    const val USED = ".prerestore.used"

    /** Salinan darurat dari keadaan SEBELUM data dikembalikan, supaya pengembalian tidak melenyapkan hasil pemulihan. */
    const val PREREVERT = ".prerevert"

    /** Penanda "sedang dipakai" selama proses pengembalian berlangsung; dihormati [PreRestoreCleaner]. */
    const val LOCK = ".prerestore.lock"

    fun of(databaseFile: File, suffix: String): File = File(databaseFile.path + suffix)
}

/**
 * Kunci pakai berbasis berkas penanda `<db>.prerestore.lock`, supaya [PreRestoreCleaner] tidak menghapus salinan yang
 * sedang dipakai untuk pengembalian (dan dua pengembalian tidak berjalan bersamaan). Berbasis berkas, bukan memori, karena
 * pembersih dan pengembalian bisa berjalan di komponen berbeda dan prosesnya bisa mati di tengah.
 *
 * Penanda yang lebih tua dari [staleAfterMillis] (proses mati sebelum melepasnya) dianggap basi, dan dipandang basi pula bila
 * jam bergeser lebih jauh dari itu ke arah mana pun: kunci tidak pernah tertahan selamanya, dan salinan hanya dipertahankan
 * lebih lama (pembersih selalu memilih tidak menghapus), tidak pernah dihapus karena kunci hilang.
 */
class PreRestoreUseLock(
    private val databaseFile: () -> File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val staleAfterMillis: Long = DEFAULT_STALE_MILLIS,
) {
    private fun marker(): File = PreRestoreFiles.of(databaseFile(), PreRestoreFiles.LOCK)

    /** True bila penanda ada dan belum basi. */
    fun isHeld(): Boolean {
        val file = marker()
        if (!file.isFile) return false
        return abs(nowMillis() - file.lastModified()) < staleAfterMillis
    }

    /** False bila dipegang pihak lain (belum basi) atau penanda tak bisa dibuat. */
    fun tryAcquire(): Boolean {
        if (isHeld()) return false
        val file = marker()
        return try {
            file.delete() // penanda basi
            val created = file.createNewFile()
            if (created) file.setLastModified(nowMillis())
            created
        } catch (_: IOException) {
            false
        }
    }

    fun release() {
        marker().delete()
    }

    companion object {
        /** Satu jam: jauh di atas durasi pengembalian sebenarnya (detik), jauh di bawah umur salinan (30 hari). */
        const val DEFAULT_STALE_MILLIS = 60L * 60 * 1000
    }
}

/**
 * [PreRestoreCopyStore] di atas berkas `<db>.prerestore`. Tidak pernah melempar (gagal = null/false), hanya nama kelas galat
 * yang dicatat. Tidak menghapus apa pun: "terpakai" adalah ganti nama, bukan hapus.
 */
class FilePreRestoreCopyStore(
    private val databaseFile: () -> File,
    private val lock: PreRestoreUseLock,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : PreRestoreCopyStore {
    private var originalModified: Long? = null

    private fun copy(): File = PreRestoreFiles.of(databaseFile(), PreRestoreFiles.PRERESTORE)

    private fun used(): File = PreRestoreFiles.of(databaseFile(), PreRestoreFiles.USED)

    override suspend fun stat(): PreRestoreCopyStat? = withContext(Dispatchers.IO) {
        try {
            val file = copy()
            if (!file.isFile) return@withContext null
            val header = try {
                // Dibaca manual: InputStream.readNBytes baru ada di Android 13 (minSdk proyek ini 26).
                file.inputStream().use { input ->
                    val buffer = ByteArray(SqliteDatabaseCheck.HEADER_SIZE)
                    var read = 0
                    while (read < buffer.size) {
                        val n = input.read(buffer, read, buffer.size - read)
                        if (n < 0) break
                        read += n
                    }
                    buffer.copyOf(read)
                }
            } catch (e: IOException) {
                log("Kepala salinan tak terbaca (${e.javaClass.simpleName})")
                null
            }
            PreRestoreCopyStat(file.length(), file.lastModified(), header)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("Salinan tak bisa diperiksa (${t.javaClass.simpleName})")
            null
        }
    }

    override suspend fun readAll(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val file = copy()
            if (file.isFile) file.readBytes() else null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Termasuk OutOfMemoryError pada database besar: lebih baik berhenti bersih daripada jatuh.
            log("Salinan tak bisa dibaca (${t.javaClass.simpleName})")
            null
        }
    }

    override fun tryLock(): Boolean = lock.tryAcquire()

    override fun unlock() = lock.release()

    override suspend fun markUsed(): Boolean = withContext(Dispatchers.IO) {
        try {
            val source = copy()
            if (!source.isFile) return@withContext false
            val modified = source.lastModified()
            val target = used()
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            originalModified = modified
            // Umur dihitung dari saat dipakai: jendela 30 hari sebelum dibersihkan dimulai dari sekarang.
            target.setLastModified(nowMillis())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("Salinan gagal ditandai terpakai (${t.javaClass.simpleName})")
            false
        }
    }

    override suspend fun unmarkUsed() = withContext(Dispatchers.IO) {
        try {
            val source = used()
            val target = copy()
            if (source.isFile && !target.exists()) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                originalModified?.let { target.setLastModified(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("Salinan gagal dikembalikan dari tanda terpakai (${t.javaClass.simpleName})")
        }
        Unit
    }

    private companion object {
        const val TAG = "PreRestoreCopyFiles"
    }
}
