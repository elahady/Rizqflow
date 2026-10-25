package com.roziqrizal.rizqflow.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPrefsRecoveryStatusStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun store(accountId: String = "akun-a") = SharedPrefsRecoveryStatusStore(context, accountId)

    @Test
    fun `belum pernah diatur adalah NotConfigured`() {
        assertEquals(RecoveryStatus.NotConfigured, store().read())
    }

    @Test
    fun `menunggu konfirmasi tersimpan lintas instans`() {
        store().markPending()

        assertEquals(RecoveryStatus.PendingConfirmation(null), store().read())
    }

    @Test
    fun `terkonfigurasi menyimpan waktu dan tahan dimuat ulang`() {
        val at = Instant.parse("2026-10-25T10:00:00Z")
        store().markConfigured(at)

        assertEquals(RecoveryStatus.Configured(at), store().read())
    }

    @Test
    fun `menunggu konfirmasi menimpa terkonfigurasi (kode lama tidak lagi berlaku)`() {
        store().markConfigured(Instant.parse("2026-10-25T10:00:00Z"))

        store().markPending()

        assertEquals(RecoveryStatus.PendingConfirmation(null), store().read())
    }

    @Test
    fun `clear mengembalikan ke NotConfigured`() {
        store().markConfigured(Instant.parse("2026-10-25T10:00:00Z"))

        store().clear()

        assertEquals(RecoveryStatus.NotConfigured, store().read())
    }

    @Test
    fun `penanda terpisah per akun`() {
        store("akun-a").markConfigured(Instant.parse("2026-10-25T10:00:00Z"))

        assertEquals(RecoveryStatus.NotConfigured, store("akun-b").read())
    }

    @Test
    fun `berkas penanda hanya memuat status, waktu, dan updated_at server, tanpa kata atau kunci`() {
        store().markConfigured(Instant.parse("2026-10-25T10:00:00Z"), "2026-10-25T09:59:00Z")

        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val contents = prefsDir.listFiles().orEmpty()
            .filter { it.name.startsWith("rizqflow_recovery_status_") }
            .joinToString("\n") { it.readText() }

        assertEquals(setOf("state", "configured_at_ms", "server_updated_at"), Regex("name=\"([a-z_]+)\"").findAll(contents).map { it.groupValues[1] }.toSet())
    }

    @Test
    fun `updated_at server tersimpan di penanda terkonfigurasi dan menunggu konfirmasi`() {
        val at = Instant.parse("2026-10-25T10:00:00Z")

        store().markPending("2026-10-25T09:59:00Z")
        assertEquals(RecoveryStatus.PendingConfirmation("2026-10-25T09:59:00Z"), store().read())

        store().markConfigured(at, "2026-10-25T09:59:00Z")
        assertEquals(RecoveryStatus.Configured(at, "2026-10-25T09:59:00Z"), store().read())
    }

    @Test
    fun `menandai tanpa updated_at menghapus updated_at lama`() {
        store().markConfigured(Instant.parse("2026-10-25T10:00:00Z"), "lama")

        store().markPending(null)

        assertEquals(RecoveryStatus.PendingConfirmation(null), store().read())
    }

    @Test
    fun `restore menulis ulang setiap bentuk status persis seperti semula`() {
        val at = Instant.parse("2026-10-25T10:00:00Z")
        for (status in listOf(
            RecoveryStatus.NotConfigured,
            RecoveryStatus.PendingConfirmation(null),
            RecoveryStatus.PendingConfirmation("x"),
            RecoveryStatus.Configured(at, null),
            RecoveryStatus.Configured(at, "x"),
        )) {
            store().markConfigured(Instant.parse("2030-01-01T00:00:00Z"), "sisa") // keadaan lain yang harus tertimpa

            store().restore(status)

            assertEquals(status, store().read(), "untuk $status")
        }
    }
}
