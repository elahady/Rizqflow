package com.roziqrizal.rizqflow.domain.entitlement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntitlementsTest {

    @Test
    fun `tanpa paket semua fitur terkunci`() {
        val gratis = PlanEntitlements()

        assertTrue(Feature.entries.none { gratis.isEnabled(it) })
    }

    @Test
    fun `paket Pro membuka fitur Pro tetapi bukan fitur Sync`() {
        val pro = PlanEntitlements(setOf(Plan.PRO))

        assertTrue(pro.isEnabled(Feature.ADVANCED_ALLOCATION_RULES))
        assertTrue(pro.isEnabled(Feature.NOTIFICATION_CAPTURE))
        assertFalse(pro.isEnabled(Feature.ENCRYPTED_SYNC))
        assertFalse(pro.isEnabled(Feature.SHARED_FAMILY_ROOM))
    }

    @Test
    fun `paket Sync membuka fitur Sync tetapi bukan fitur Pro`() {
        val sync = PlanEntitlements(setOf(Plan.SYNC))

        assertTrue(sync.isEnabled(Feature.ENCRYPTED_SYNC))
        assertFalse(sync.isEnabled(Feature.UNLIMITED_ROOMS))
    }

    @Test
    fun `pengguna gratis dibatasi lima ruang`() {
        val gratis = PlanEntitlements()

        assertEquals(5, gratis.roomLimit)
        assertTrue(gratis.canAddRoom(currentRoomCount = 4))
        assertFalse(gratis.canAddRoom(currentRoomCount = 5))
    }

    @Test
    fun `pengguna Pro tidak dibatasi jumlah ruang`() {
        val pro = PlanEntitlements(setOf(Plan.PRO))

        assertNull(pro.roomLimit)
        assertTrue(pro.canAddRoom(currentRoomCount = 500))
    }

    @Test
    fun `batas ruang gratis bisa diatur`() {
        val gratis = PlanEntitlements(freeRoomLimit = 3)

        assertFalse(gratis.canAddRoom(currentRoomCount = 3))
    }

    @Test
    fun `LivePlanEntitlements membaca ulang paket tiap dipanggil, bukan potret sekali dibuat`() {
        var owned = emptySet<Plan>()
        val live = LivePlanEntitlements { owned }

        assertEquals(5, live.roomLimit)
        assertFalse(live.isEnabled(Feature.UNLIMITED_ROOMS))

        owned = setOf(Plan.PRO)

        assertNull(live.roomLimit)
        assertTrue(live.isEnabled(Feature.UNLIMITED_ROOMS))
    }
}
