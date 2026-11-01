package com.roziqrizal.rizqflow.push

import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import com.roziqrizal.rizqflow.domain.auth.AuthProviderType
import com.roziqrizal.rizqflow.domain.auth.AuthSession
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import com.roziqrizal.rizqflow.domain.entitlement.PlanEntitlements
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.SyncNotificationKind
import com.roziqrizal.rizqflow.domain.sync.SyncNotificationPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Penerima FCM (wiring 2026-11-01): notifikasi hanya dengan akun server + Sync + payload sah. */
class SyncPushReceiverTest {
    private val validData = mapOf("roomId" to "ruang-keluarga-1", "kind" to "NEW_RECORD")

    private class Harness(plans: Set<Plan>, serverAccount: Boolean = true) {
        val shown = mutableListOf<SyncNotificationPayload>()
        val receiver = SyncPushReceiver(
            entitlements = PlanEntitlements(plans),
            hasServerAccount = { serverAccount },
            show = { shown += it },
        )
    }

    @Test
    fun `dengan Sync payload sah ditampilkan`() {
        val h = Harness(setOf(Plan.SYNC))

        val decision = h.receiver.onData(validData)

        val expected = SyncNotificationPayload(RoomId("ruang-keluarga-1"), SyncNotificationKind.NEW_RECORD)
        assertEquals(SyncPushDecision.Shown(expected), decision)
        assertEquals(listOf(expected), h.shown)
    }

    @Test
    fun `tanpa Sync tidak ada notifikasi`() {
        val h = Harness(emptySet())

        assertEquals(SyncPushDecision.NoSync, h.receiver.onData(validData))
        assertTrue(h.shown.isEmpty())
    }

    @Test
    fun `Pro saja tanpa Sync tidak ada notifikasi`() {
        val h = Harness(setOf(Plan.PRO))

        assertEquals(SyncPushDecision.NoSync, h.receiver.onData(validData))
        assertTrue(h.shown.isEmpty())
    }

    @Test
    fun `tanpa akun server tidak ada notifikasi walau punya Sync`() {
        val h = Harness(setOf(Plan.SYNC), serverAccount = false)

        assertEquals(SyncPushDecision.NoServerAccount, h.receiver.onData(validData))
        assertTrue(h.shown.isEmpty())
    }

    @Test
    fun `data berisi nominal nama atau catatan ditolak tanpa notifikasi`() {
        val h = Harness(setOf(Plan.SYNC))

        for (extra in listOf("nominal" to "150000", "nama" to "Kopi pagi", "catatan" to "bayar listrik")) {
            assertEquals(SyncPushDecision.Rejected, h.receiver.onData(validData + extra))
        }
        assertTrue(h.shown.isEmpty())
    }

    @Test
    fun `data rusak ditolak tanpa notifikasi`() {
        val h = Harness(setOf(Plan.SYNC))

        assertEquals(SyncPushDecision.Rejected, h.receiver.onData(emptyMap()))
        assertEquals(SyncPushDecision.Rejected, h.receiver.onData(mapOf("roomId" to "r1", "kind" to "DEPOSIT")))
        assertTrue(h.shown.isEmpty())
    }

    @Test
    fun `hanya akun Google dan email di data asli yang dianggap akun server`() {
        assertTrue(isServerAccount(AuthSession("a@b.c", "a@b.c", null, AuthProviderType.GOOGLE)))
        assertTrue(isServerAccount(AuthSession("e@b.c", "e@b.c", null, AuthProviderType.EMAIL)))
        assertFalse(isServerAccount(null))
        assertFalse(isServerAccount(AuthSession("uuid-1", null, null, AuthProviderType.PASSWORD, username = "lokal")))
        assertFalse(isServerAccount(AuthSession("debug", "uji@rizqflow.local", null, AuthProviderType.DEBUG)))
        assertFalse(isServerAccount(AuthSession(AccountStorage.DEMO_ACCOUNT_ID, "a@b.c", null, AuthProviderType.GOOGLE)))
    }
}
