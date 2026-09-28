package com.roziqrizal.rizqflow.billing

import com.roziqrizal.rizqflow.domain.entitlement.Feature
import com.roziqrizal.rizqflow.domain.entitlement.LivePlanEntitlements
import com.roziqrizal.rizqflow.domain.entitlement.Plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PurchaseStoreTest {

    private class FakeStorage(var names: Set<String> = emptySet()) : PlanStorage {
        override fun read() = names
        override fun write(names: Set<String>) {
            this.names = names
        }
    }

    @Test
    fun `perangkat baru tidak memiliki paket apa pun`() {
        assertTrue(PurchaseStore(FakeStorage()).plans.value.isEmpty())
    }

    @Test
    fun `grant lalu revoke menyalakan dan mematikan paket, dan tersimpan`() {
        val storage = FakeStorage()
        val store = PurchaseStore(storage)

        store.grant(Plan.PRO)
        assertEquals(setOf(Plan.PRO), store.plans.value)
        assertEquals(setOf("PRO"), storage.names)

        store.revoke(Plan.PRO)
        assertTrue(store.plans.value.isEmpty())
        assertTrue(storage.names.isEmpty())
    }

    @Test
    fun `revoke satu paket tidak menyentuh paket lain`() {
        val store = PurchaseStore(FakeStorage(setOf("PRO", "SYNC")))

        store.revoke(Plan.PRO)

        assertEquals(setOf(Plan.SYNC), store.plans.value)
    }

    @Test
    fun `nama paket yang tidak dikenal diabaikan saat dibaca`() {
        val store = PurchaseStore(FakeStorage(setOf("PRO", "LAMA")))

        assertEquals(setOf(Plan.PRO), store.plans.value)
    }

    @Test
    fun `saklar Pro langsung mengubah batas ruang dan akun`() {
        val store = PurchaseStore(FakeStorage())
        val entitlements = LivePlanEntitlements { store.plans.value }

        assertEquals(5, entitlements.roomLimit)
        assertEquals(3, entitlements.accountLimit)
        assertFalse(entitlements.isEnabled(Feature.ROLE_SYSTEMS))

        store.grant(Plan.PRO)
        assertNull(entitlements.roomLimit)
        assertNull(entitlements.accountLimit)
        assertTrue(entitlements.isEnabled(Feature.ROLE_SYSTEMS))

        store.revoke(Plan.PRO)
        assertEquals(5, entitlements.roomLimit)
    }
}
