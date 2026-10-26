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
 * ruang bersama tersimpan ([onExpenseSaved], dipanggil repositori SETELAH transaksi komit). Tombol manual
 * "Sinkronkan sekarang" tetap jadi fallback.
 *
 * Ruang "bersama" = punya baris `shared_room` berstatus ACTIVE. Sinkron yang sebenarnya dijalankan lewat [attach]
 * (di produksi: `SyncEngine.syncNow`); pemisahan ini membuat pemicu bisa diuji tanpa SyncEngine, yang `final`.
 *
 * Gagal senyap: galat dan [SyncResult.Failed] hanya dicatat lewat `log`, tidak pernah dilempar ke penyimpanan
 * lokal. Putaran sinkron berjalan satu per satu ([Mutex]), dan permintaan untuk ruang yang sudah menunggu giliran
 * tidak ditumpuk (putaran itu akan membaca perubahan terbaru).
 */
class SharedRoomAutoSync(
    private val syncStore: SyncStore,
    private val scope: CoroutineScope,
) {
    @Volatile private var runSync: (suspend (RoomId) -> SyncResult)? = null
    @Volatile private var log: (String) -> Unit = {}

    private val mutex = Mutex()
    private val waiting: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Dipasang sekali setelah `SyncEngine` dirakit. Sebelum itu pemicu tidak melakukan apa pun. */
    fun attach(sync: suspend (RoomId) -> SyncResult, log: (String) -> Unit) {
        this.log = log
        runSync = sync
    }

    /** Pengeluaran di [roomId] sudah tersimpan. Hanya ruang bersama yang disinkronkan; tidak pernah melempar. */
    suspend fun onExpenseSaved(roomId: RoomId) {
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
            mutex.withLock {
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
                SyncResult.NotSignedIn, is SyncResult.Success -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("sinkron otomatis gagal: ${e.javaClass.simpleName}")
        }
    }
}
