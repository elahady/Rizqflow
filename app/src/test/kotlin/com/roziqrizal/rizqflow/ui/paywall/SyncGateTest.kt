package com.roziqrizal.rizqflow.ui.paywall

import com.roziqrizal.rizqflow.domain.entitlement.Feature
import com.roziqrizal.rizqflow.domain.entitlement.PlanEntitlements
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Penguncian Sync di klien (Tahap 9): fitur ruang keluarga dan sinkron dikunci tanpa Plan.SYNC, dan 403
 * "Butuh langganan Sync" dari server dipetakan ke paywall yang sama dengan penjaga klien.
 */
class SyncGateTest {

    @Test
    fun `tanpa langganan Sync fitur ruang keluarga dan sinkron terkunci`() {
        val free = PlanEntitlements(emptySet())
        assertFalse(free.isEnabled(Feature.SHARED_FAMILY_ROOM))
        assertFalse(free.isEnabled(Feature.ENCRYPTED_SYNC))
    }

    @Test
    fun `Pro saja tidak membuka fitur Sync`() {
        val pro = PlanEntitlements(setOf(Plan.PRO))
        assertTrue(pro.isEnabled(Feature.THEMES), "fitur Pro tetap terbuka untuk pemilik Pro")
        assertFalse(pro.isEnabled(Feature.SHARED_FAMILY_ROOM))
        assertFalse(pro.isEnabled(Feature.ENCRYPTED_SYNC))
    }

    @Test
    fun `langganan Sync membuka fitur ruang keluarga dan sinkron`() {
        val sync = PlanEntitlements(setOf(Plan.SYNC))
        assertTrue(sync.isEnabled(Feature.SHARED_FAMILY_ROOM))
        assertTrue(sync.isEnabled(Feature.ENCRYPTED_SYNC))
    }

    @Test
    fun `hanya 403 dari server yang dipetakan ke paywall Sync`() {
        assertTrue(RemoteError.SyncRequired.opensSyncPaywall())
        assertFalse(RemoteError.Unauthorized.opensSyncPaywall())
        assertFalse(RemoteError.Unreachable.opensSyncPaywall())
        assertFalse(RemoteError.Rejected("pesan").opensSyncPaywall())
        assertFalse(RemoteError.Unexpected(500).opensSyncPaywall())
    }

    @Test
    fun `fitur ruang keluarga memakai varian paywall Sync, bukan Pro`() {
        assertEquals(Plan.SYNC, paywallPlanFor(Feature.SHARED_FAMILY_ROOM))
        assertEquals(Plan.SYNC, paywallPlanFor(Feature.ENCRYPTED_SYNC))
    }
}
