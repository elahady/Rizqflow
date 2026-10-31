package com.roziqrizal.rizqflow.sync

import com.roziqrizal.rizqflow.domain.entitlement.PlanEntitlements
import com.roziqrizal.rizqflow.domain.sync.PushTokenRegistrar
import com.roziqrizal.rizqflow.domain.sync.PushTokenRegistration
import com.roziqrizal.rizqflow.domain.sync.PushTokenSync
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keluar akun mencabut token FCM perangkat ini di server sebelum token API dicabut (wiring FCM 2026-11-01). */
class ServerSignOutPushTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    /** Registrar palsu yang mencatat panggilannya ke log bersama dengan remote akun, supaya urutannya terlihat. */
    private class RecordingRegistrar(
        private val log: MutableList<String>,
        private val result: RemoteResult<Unit> = RemoteResult.Success(Unit),
        private val hang: Boolean = false,
    ) : PushTokenRegistrar {
        override suspend fun register(apiToken: String, registration: PushTokenRegistration): RemoteResult<Unit> = error("tidak dipakai")

        override suspend fun revoke(apiToken: String, deviceId: String): RemoteResult<Unit> {
            synchronized(log) { log += "push-revoke:$apiToken:$deviceId" }
            if (hang) awaitCancellation()
            return result
        }
    }

    private fun pushTokens(registrar: PushTokenRegistrar) =
        PushTokenSync(registrar, PlanEntitlements(emptySet()), apiToken = { null }, deviceId = "perangkat-1")

    @Test
    fun `token FCM dicabut dengan token API lama lalu token API dicabut`() = runBlocking {
        val remote = FakeAccountRemote(valid = setOf("t-1"))
        val store = FakeTokenStore("t-1")
        val session = ServerSession(store)
        val registrar = RecordingRegistrar(remote.calls)

        ServerSignOut(scope).signOut(session, session.guard(remote), pushTokens(registrar))!!.join()

        assertNull(store.token)
        assertEquals(listOf("push-revoke:t-1:perangkat-1", "logout:t-1"), remote.calls)
    }

    @Test
    fun `gagal mencabut token FCM tidak menghalangi pencabutan token API`() = runBlocking {
        val remote = FakeAccountRemote(valid = setOf("t-1"))
        val session = ServerSession(FakeTokenStore("t-1"))
        val registrar = RecordingRegistrar(remote.calls, result = RemoteResult.Failure(RemoteError.Unreachable))

        ServerSignOut(scope).signOut(session, session.guard(remote), pushTokens(registrar))!!.join()

        assertEquals(listOf("push-revoke:t-1:perangkat-1", "logout:t-1"), remote.calls)
    }

    @Test
    fun `pencabutan token FCM yang menggantung dibatasi waktu lalu token API tetap dicabut`() = runBlocking {
        val remote = FakeAccountRemote(valid = setOf("t-1"))
        val session = ServerSession(FakeTokenStore("t-1"))
        val registrar = RecordingRegistrar(remote.calls, hang = true)

        withTimeout(5_000) { ServerSignOut(scope, timeoutMillis = 100).signOut(session, session.guard(remote), pushTokens(registrar))!!.join() }

        assertEquals(listOf("push-revoke:t-1:perangkat-1", "logout:t-1"), remote.calls)
    }

    @Test
    fun `tanpa jalur FCM tidak ada pencabutan token FCM`() = runBlocking {
        val remote = FakeAccountRemote(valid = setOf("t-1"))
        val session = ServerSession(FakeTokenStore("t-1"))

        ServerSignOut(scope).signOut(session, session.guard(remote), pushTokens = null)!!.join()

        assertEquals(listOf("logout:t-1"), remote.calls)
        assertTrue(remote.calls.none { it.startsWith("push-revoke") })
    }
}
