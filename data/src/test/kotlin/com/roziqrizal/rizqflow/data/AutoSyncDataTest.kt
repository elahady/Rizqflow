package com.roziqrizal.rizqflow.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.data.sync.SharedPrefsAutoSyncSettings
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.PersonalSyncResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import com.roziqrizal.rizqflow.domain.sync.SyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Sisi `:data` dari sync otomatis (Tahap 6 sync-desain.md bagian H): pemicu "baris kotor", penjaga loop (pull tidak
 * memicu apa pun dan push sesudahnya mengirim 0 baris), delegasi pemicu ruang bersama ke orkestrator, dan pengaturan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoSyncDataTest {
    private lateinit var a: SyncFixture

    @Before
    fun setUp() {
        a = SyncFixture(autoSyncScope = CoroutineScope(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() = a.close()

    @Test
    fun `penyimpanan lokal yang menambah baris kotor memberi tahu pemicu`() = runBlocking<Unit> {
        val fired = AtomicInteger()
        a.local.changeNotifier.listener = { fired.incrementAndGet() }

        a.expense(15_000)

        assertTrue(fired.get() > 0, "recorder harus memanggil pemicu saat menandai kotor")
    }

    @Test
    fun `galat dari pemicu tidak menggagalkan penyimpanan lokal`() = runBlocking<Unit> {
        a.local.changeNotifier.listener = { error("pemicu rusak") }

        val id = a.expense(15_000)

        assertEquals(15_000L, a.local.transactions.find(id)?.amount?.minor, "transaksi tetap tersimpan")
    }

    @Test
    fun `pull lalu push menghasilkan nol unggahan dan pull tidak memicu apa pun`() = runBlocking<Unit> {
        val expenseId = a.expense(20_000)
        val remote = MemoryPersonalRemote()
        val pushedFromA = engineFor(a.local, remote, RecoveryStatus.Configured(Instant.now())).syncNow("device-a")
        assertTrue(assertIs<PersonalSyncResult.Success>(pushedFromA).pushed > 0)
        val serverRows = remote.stored.size

        val b = emptyLedger()
        val fired = AtomicInteger()
        b.changeNotifier.listener = { fired.incrementAndGet() }
        val engineB = engineFor(b, remote, RecoveryStatus.Configured(Instant.now()))

        val first = assertIs<PersonalSyncResult.Success>(engineB.syncNow("device-b"))
        val second = assertIs<PersonalSyncResult.Success>(engineB.syncNow("device-b"))

        assertTrue(first.applied > 0, "pull menerapkan data A")
        assertEquals(20_000L, b.transactions.find(expenseId)?.amount?.minor)
        assertEquals(0, first.pushed, "hasil pull tidak boleh terkirim balik")
        assertEquals(0, second.pushed)
        assertEquals(serverRows, remote.stored.size, "server tidak menerima satu baris pun dari B")
        assertEquals(0, fired.get(), "penerapan pull tidak menandai kotor, jadi tidak memicu sync otomatis")
        b.close()
    }

    @Test
    fun `setelah A menarik hasil B dan B menarik hasil A tidak ada baris yang berputar`() = runBlocking<Unit> {
        a.expense(11_000)
        val remote = MemoryPersonalRemote()
        val configured = RecoveryStatus.Configured(Instant.now())
        val b = emptyLedger()
        val engineA = engineFor(a.local, remote, configured)
        val engineB = engineFor(b, remote, configured)

        engineA.syncNow("device-a")
        engineB.syncNow("device-b")
        // Putaran penuh sampai tenang: tidak satu pun perangkat boleh punya sesuatu untuk dikirim.
        val roundA = assertIs<PersonalSyncResult.Success>(engineA.syncNow("device-a"))
        val roundB = assertIs<PersonalSyncResult.Success>(engineB.syncNow("device-b"))
        val stable = remote.stored.size
        engineA.syncNow("device-a")
        engineB.syncNow("device-b")

        assertEquals(0, roundA.pushed)
        assertEquals(0, roundB.pushed)
        assertEquals(stable, remote.stored.size, "server tidak tumbuh lagi setelah tenang")
        assertTrue(a.rows().none { it.dirty })
        b.close()
    }

    @Test
    fun `mode delegasi meneruskan penyimpanan ruang bersama ke orkestrator tanpa menyinkronkan sendiri`() = runBlocking<Unit> {
        val syncCalls = AtomicInteger()
        val delegated = AtomicInteger()
        a.local.autoSync.attach(sync = { syncCalls.incrementAndGet(); SyncResult.NotSignedIn }, log = {})
        a.local.autoSync.delegateTriggersTo { delegated.incrementAndGet() }

        a.local.autoSync.onExpenseSaved(RoomId("ruang-apa-saja"))
        a.local.autoSync.onWorkspaceOpened()

        assertEquals(1, delegated.get())
        assertEquals(0, syncCalls.get(), "kelas ini tidak lagi menyinkronkan ruang bersama sendiri, supaya tidak ganda dengan orkestrator")
    }

    @Test
    fun `kunci ruang tetap dipakai bersama setelah delegasi`() = runBlocking<Unit> {
        a.local.autoSync.delegateTriggersTo {}
        val order = mutableListOf<String>()

        a.local.autoSync.withRoomLock(RoomId("r1")) { order += "satu" }
        a.local.autoSync.withRoomLock(RoomId("r1")) { order += "dua" }

        assertEquals(listOf("satu", "dua"), order)
    }

    @Test
    fun `saklar hanya Wi-Fi bawaannya aktif dan tersimpan`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SharedPrefsAutoSyncSettings(context)
        assertTrue(settings.wifiOnly, "bawaan aman: hanya Wi-Fi")

        settings.wifiOnly = false

        assertFalse(SharedPrefsAutoSyncSettings(context).wifiOnly, "tersimpan lintas objek")
    }
}
