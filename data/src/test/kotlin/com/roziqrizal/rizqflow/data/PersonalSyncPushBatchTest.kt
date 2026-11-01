package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.domain.sync.PersonalSyncResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.SyncLogEntryUpload
import com.roziqrizal.rizqflow.domain.sync.SyncPullResult
import com.roziqrizal.rizqflow.domain.sync.SyncPushLimits
import com.roziqrizal.rizqflow.domain.sync.SyncPushResult
import com.roziqrizal.rizqflow.domain.sync.SyncRemote
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Server palsu yang mencatat ukuran tiap batch push dan bisa gagal pada panggilan push ke-N. */
private class BatchRecordingRemote(private val failOnPushCall: Int? = null) : SyncRemote {
    val inner = MemoryPersonalRemote()
    val batchSizes = mutableListOf<Int>()
    private var pushCalls = 0

    override suspend fun push(apiToken: String, entries: List<SyncLogEntryUpload>): RemoteResult<SyncPushResult> {
        pushCalls++
        if (pushCalls == failOnPushCall) return RemoteResult.Failure(RemoteError.Unreachable)
        batchSizes += entries.size
        return inner.push(apiToken, entries)
    }

    override suspend fun pull(apiToken: String, deviceId: String, after: Long, limit: Int): RemoteResult<SyncPullResult> =
        inner.pull(apiToken, deviceId, after, limit)
}

/**
 * Pemotongan push cakupan pribadi per batas request (sync-desain.md bagian C "Batas": 200 baris dan
 * 800 KB). Batas kecil disuntikkan lewat [SyncPushLimits] supaya beberapa batch muncul dari data kecil.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalSyncPushBatchTest {
    private lateinit var a: SyncFixture
    private val configured = RecoveryStatus.Configured(Instant.now())

    @Before
    fun setUp() {
        a = SyncFixture()
    }

    @After
    fun tearDown() = a.close()

    @Test
    fun `rekaman kotor tepat sebanyak batas baris terkirim dalam satu batch`() = runBlocking {
        val dirty = a.local.syncStore.dirtyCount(SyncScope.Personal)
        val remote = BatchRecordingRemote()

        val result = engineFor(a.local, remote, configured, SyncPushLimits(maxRows = dirty)).syncNow("device-a")

        assertEquals(dirty, assertIs<PersonalSyncResult.Success>(result).pushed)
        assertEquals(listOf(dirty), remote.batchSizes)
        assertEquals(0, a.local.syncStore.dirtyCount(SyncScope.Personal))
    }

    @Test
    fun `rekaman kotor lebih dari batas baris terkirim dalam beberapa batch dan semuanya bersih`() = runBlocking {
        repeat(3) { a.expense(1_000L + it) }
        val dirty = a.local.syncStore.dirtyCount(SyncScope.Personal)
        val remote = BatchRecordingRemote()

        val result = engineFor(a.local, remote, configured, SyncPushLimits(maxRows = 3)).syncNow("device-a")

        assertEquals(dirty, assertIs<PersonalSyncResult.Success>(result).pushed)
        assertTrue(remote.batchSizes.size > 1, "harus lebih dari satu batch: ${remote.batchSizes}")
        assertTrue(remote.batchSizes.all { it <= 3 }, "tidak ada batch melebihi batas baris: ${remote.batchSizes}")
        assertEquals(dirty, remote.batchSizes.sum())
        assertEquals(dirty, remote.inner.stored.map { it.recordId }.toSet().size, "tidak ada rekaman terkirim dua kali")
        assertEquals(0, a.local.syncStore.dirtyCount(SyncScope.Personal))
    }

    @Test
    fun `batas byte memotong batch walau batas baris longgar`() = runBlocking {
        repeat(2) { a.expense(2_000L + it) }
        val dirty = a.local.syncStore.dirtyCount(SyncScope.Personal)
        val remote = BatchRecordingRemote()

        // Tiap baris amplop berpengisi kelipatan 256 byte (base64 > 340 byte), jadi 1500 byte cukup untuk beberapa baris saja.
        val result = engineFor(a.local, remote, configured, SyncPushLimits(maxRows = 1000, maxBytes = 1500)).syncNow("device-a")

        assertEquals(dirty, assertIs<PersonalSyncResult.Success>(result).pushed)
        assertTrue(remote.batchSizes.size > 1, "batas byte harus memotong: ${remote.batchSizes}")
        assertEquals(0, a.local.syncStore.dirtyCount(SyncScope.Personal))
    }

    @Test
    fun `lebih dari 200 rekaman satu tipe terkirim seluruhnya lewat beberapa putaran pengumpulan`() = runBlocking {
        repeat(205) { a.expense(100L + it) }
        val dirty = a.local.syncStore.dirtyCount(SyncScope.Personal)
        val remote = BatchRecordingRemote()

        val result = engineFor(a.local, remote, configured).syncNow("device-a")

        assertEquals(dirty, assertIs<PersonalSyncResult.Success>(result).pushed)
        assertTrue(remote.batchSizes.all { it <= 200 }, "batas bawaan 200 baris per request: ${remote.batchSizes}")
        assertEquals(0, a.local.syncStore.dirtyCount(SyncScope.Personal))
    }

    @Test
    fun `kegagalan di batch tengah tidak menandai terkirim rekaman yang belum terkirim`() = runBlocking {
        repeat(3) { a.expense(3_000L + it) }
        val dirty = a.local.syncStore.dirtyCount(SyncScope.Personal)
        val remote = BatchRecordingRemote(failOnPushCall = 2)

        val result = engineFor(a.local, remote, configured, SyncPushLimits(maxRows = 3)).syncNow("device-a")

        assertIs<PersonalSyncResult.Failed>(result)
        assertEquals(listOf(3), remote.batchSizes, "hanya batch pertama yang sampai ke server")
        assertEquals(dirty - 3, a.local.syncStore.dirtyCount(SyncScope.Personal), "batch pertama bersih, sisanya tetap kotor")
        assertEquals(3, remote.inner.stored.size)

        // Sync berikutnya (server pulih: hanya panggilan ke-2 yang gagal) mengirim sisanya tanpa mengulang batch pertama.
        val second = engineFor(a.local, remote, configured, SyncPushLimits(maxRows = 3)).syncNow("device-a")

        assertEquals(dirty - 3, assertIs<PersonalSyncResult.Success>(second).pushed)
        assertEquals(dirty, remote.inner.stored.map { it.recordId }.toSet().size, "setiap rekaman tepat sekali di server")
        assertEquals(dirty, remote.inner.stored.size)
        assertEquals(0, a.local.syncStore.dirtyCount(SyncScope.Personal))
    }
}
