package com.roziqrizal.rizqflow.data.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus
import com.roziqrizal.rizqflow.domain.sync.SyncResult
import com.roziqrizal.rizqflow.domain.sync.SyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Sinkron otomatis ruang bersama (Tahap 9, roadmap "Sync berjalan otomatis"). Tanpa penjadwal latar belakang:
 * hanya dua momen yang memicu, yaitu saat ruang kerja dibuka ([onWorkspaceOpened]) dan setelah pengeluaran di
 * ruang bersama tersimpan, diubah, atau dihapus ([onExpenseSaved], dipanggil repositori SETELAH transaksi komit).
 * Tombol manual "Sinkronkan sekarang" tetap jadi fallback dan berjalan lewat [withRoomLock] yang sama.
 *
 * Ruang "bersama" = punya baris `shared_room` berstatus ACTIVE. Sinkron yang sebenarnya dijalankan lewat [attach]
 * (di produksi: `SyncCoordinator.syncNow`); pemisahan ini membuat pemicu bisa diuji tanpa SyncCoordinator, yang `final`.
 *
 * Gagal senyap: galat dan [SyncResult.Failed] hanya dicatat lewat `log`, tidak pernah dilempar ke penyimpanan
 * lokal. Putaran sinkron untuk SATU ruang berjalan satu per satu (satu [Mutex] per ruang, dipakai bersama oleh pemicu
 * otomatis dan tombol manual lewat [withRoomLock]); ruang berbeda boleh berjalan bersamaan. Permintaan otomatis untuk
 * ruang yang sudah menunggu giliran tidak ditumpuk (putaran itu akan membaca perubahan terbaru).
 */
class SharedRoomAutoSync(
    private val syncStore: SyncStore,
    private val scope: CoroutineScope,
) {
    @Volatile private var runSync: (suspend (RoomId) -> SyncResult)? = null
    @Volatile private var log: (String) -> Unit = {}

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val waiting: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun lockFor(roomId: RoomId): Mutex = locks.computeIfAbsent(roomId.value) { Mutex() }

    /**
     * Menjalankan [block] (sinkron manual "Sinkronkan sekarang") di bawah kunci ruang yang sama dengan pemicu
     * otomatis, supaya dua putaran untuk ruang yang sama tidak pernah berjalan bersamaan (dua push dari baris
     * pending yang sama, atau kursor yang saling menimpa). Bila putaran otomatis sedang berjalan, [block] menunggu
     * sampai selesai lalu berjalan sendiri (tidak digabung), jadi hasilnya selalu milik tombol ini.
     */
    suspend fun <T> withRoomLock(roomId: RoomId, block: suspend () -> T): T = lockFor(roomId).withLock { block() }

    /** Dipasang sekali setelah `SyncEngine` dirakit. Sebelum itu pemicu tidak melakukan apa pun. */
    fun attach(sync: suspend (RoomId) -> SyncResult, log: (String) -> Unit) {
        this.log = log
        runSync = sync
    }

    @Volatile private var delegate: (() -> Unit)? = null

    /**
     * Mode delegasi (Tahap 6): pemicu otomatis kelas ini diserahkan ke `AutoSyncOrchestrator` supaya ruang bersama TIDAK
     * disinkronkan dua kali (sekali oleh sini, sekali oleh run orkestrator). [onExpenseSaved] hanya meneruskan "ada
     * perubahan" ke [onChange] (debounce ada di orkestrator), [onWorkspaceOpened] tidak melakukan apa pun (pemicu
     * foreground orkestrator yang menyinkronkan pribadi lalu semua ruang bersama aktif). [withRoomLock] tetap dipakai
     * orkestrator dan tombol "Sinkronkan sekarang" ruang itu. Tanpa delegasi, perilaku lama persis seperti sebelumnya.
     */
    fun delegateTriggersTo(onChange: () -> Unit) {
        delegate = onChange
    }

    /**
     * Pengeluaran di [roomId] sudah tersimpan, diubah, atau dihapus (sudah komit). Hanya ruang bersama yang
     * disinkronkan; tidak pernah melempar.
     */
    suspend fun onExpenseSaved(roomId: RoomId) {
        delegate?.let { onChange ->
            onChange()
            return
        }
        val shared = try {
            syncStore.sharedRoom(roomId)?.status == SharedRoomStatus.ACTIVE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("cek ruang bersama gagal: ${e.javaClass.simpleName}")
            false
        }
        if (shared) schedule(roomId)
    }

    /** Ruang kerja baru dibuka: sinkronkan semua ruang bersama yang aktif. Tidak menunggu hasilnya. */
    fun onWorkspaceOpened() {
        if (delegate != null) return
        scope.launch {
            val rooms = try {
                syncStore.sharedRooms().filter { it.status == SharedRoomStatus.ACTIVE }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("baca ruang bersama gagal: ${e.javaClass.simpleName}")
                return@launch
            }
            rooms.forEach { schedule(it.roomId) }
        }
    }

    private fun schedule(roomId: RoomId) {
        // Sudah menunggu giliran: putaran yang sedang antre akan membaca semua perubahan yang sudah tersimpan.
        if (!waiting.add(roomId.value)) return
        scope.launch {
            lockFor(roomId).withLock {
                // Dilepas SEBELUM sinkron berjalan: penyimpanan yang datang selama putaran ini butuh putaran sendiri.
                waiting.remove(roomId.value)
                runOnce(roomId)
            }
        }
    }

    private suspend fun runOnce(roomId: RoomId) {
        val sync = runSync ?: return
        try {
            when (val result = sync(roomId)) {
                is SyncResult.Failed -> log("sinkron otomatis gagal: ${result.error}")
                // Bukan galat: SyncCoordinator sudah menandai ruang REMOVED, jadi tidak ikut lagi di putaran berikutnya.
                SyncResult.RemovedFromRoom -> log("ruang ${roomId.value} sudah bukan milik akun ini, sinkron dihentikan")
                SyncResult.NotSignedIn, is SyncResult.Success -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("sinkron otomatis gagal: ${e.javaClass.simpleName}")
        }
    }
}
