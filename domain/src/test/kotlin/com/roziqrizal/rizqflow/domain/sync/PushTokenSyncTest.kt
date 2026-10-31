package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.entitlement.Plan
import com.roziqrizal.rizqflow.domain.entitlement.PlanEntitlements
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Kapan token FCM dikirim dan dicabut (wiring FCM 2026-11-01), terhadap registrar palsu. */
class PushTokenSyncTest {

    private class FakeRegistrar(
        var registerResult: RemoteResult<Unit> = RemoteResult.Success(Unit),
        var revokeResult: RemoteResult<Unit> = RemoteResult.Success(Unit),
    ) : PushTokenRegistrar {
        val registered = mutableListOf<Pair<String, PushTokenRegistration>>()
        val revoked = mutableListOf<Pair<String, String>>()

        override suspend fun register(apiToken: String, registration: PushTokenRegistration): RemoteResult<Unit> {
            registered += apiToken to registration
            return registerResult
        }

        override suspend fun revoke(apiToken: String, deviceId: String): RemoteResult<Unit> {
            revoked += apiToken to deviceId
            return revokeResult
        }
    }

    private fun sync(registrar: PushTokenRegistrar, plans: Set<Plan> = setOf(Plan.SYNC), apiToken: String? = "api-1") =
        PushTokenSync(registrar, PlanEntitlements(plans), apiToken = { apiToken }, deviceId = "perangkat-1")

    @Test
    fun `dengan Sync dan sesi server token FCM dikirim bersama device id dan platform android`() = runBlocking {
        val registrar = FakeRegistrar()

        val outcome = sync(registrar).register("fcm-abc")

        assertEquals(PushRegistrationOutcome.Registered, outcome)
        assertEquals(listOf("api-1" to PushTokenRegistration("fcm-abc", "perangkat-1")), registrar.registered)
        assertEquals("android", registrar.registered.single().second.platform)
    }

    @Test
    fun `tanpa Sync token tidak dikirim`() = runBlocking {
        val registrar = FakeRegistrar()

        assertEquals(PushRegistrationOutcome.NoSync, sync(registrar, plans = setOf(Plan.PRO)).register("fcm-abc"))
        assertTrue(registrar.registered.isEmpty())
    }

    @Test
    fun `tanpa token API server token tidak dikirim`() = runBlocking {
        val registrar = FakeRegistrar()

        assertEquals(PushRegistrationOutcome.NoServerSession, sync(registrar, apiToken = null).register("fcm-abc"))
        assertTrue(registrar.registered.isEmpty())
    }

    @Test
    fun `tanpa token FCM (Firebase mati) tidak ada yang dikirim`() = runBlocking {
        val registrar = FakeRegistrar()

        assertEquals(PushRegistrationOutcome.NoFcmToken, sync(registrar).register(null))
        assertEquals(PushRegistrationOutcome.NoFcmToken, sync(registrar).register("  "))
        assertTrue(registrar.registered.isEmpty())
    }

    @Test
    fun `kegagalan server dilaporkan apa adanya`() = runBlocking {
        val registrar = FakeRegistrar(registerResult = RemoteResult.Failure(RemoteError.Unreachable))

        assertEquals(PushRegistrationOutcome.Failed(RemoteError.Unreachable), sync(registrar).register("fcm-abc"))
    }

    @Test
    fun `mencabut memakai token API yang diberikan dan device id perangkat ini, tanpa gerbang Sync`() = runBlocking {
        val registrar = FakeRegistrar()

        val result = sync(registrar, plans = emptySet(), apiToken = null).revoke("api-lama")

        assertEquals(RemoteResult.Success(Unit), result)
        assertEquals(listOf("api-lama" to "perangkat-1"), registrar.revoked)
        assertTrue(registrar.registered.isEmpty())
    }

    @Test
    fun `toString pendaftaran tidak memuat token FCM`() {
        assertFalse(PushTokenRegistration("fcm-rahasia", "perangkat-1").toString().contains("fcm-rahasia"))
    }
}
