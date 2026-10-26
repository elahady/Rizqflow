package com.roziqrizal.rizqflow.ui.devices

import com.roziqrizal.rizqflow.domain.sync.DeviceRemote
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import com.roziqrizal.rizqflow.domain.sync.ServerDevice
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DevicesViewModel] dengan remote palsu (mock jaringan): daftar perangkat, urutan tampilan, galat, dan aturan
 * keluar lokal (hanya setelah server mencabut semua token, atau 401).
 */
class DevicesViewModelTest {

    /** Remote palsu: jawaban tetap, dan semua panggilan dicatat (token yang dipakai). */
    private class FakeDeviceRemote(
        var listResult: RemoteResult<List<ServerDevice>> = RemoteResult.Success(emptyList()),
        var revokeResult: RemoteResult<Unit> = RemoteResult.Success(Unit),
    ) : DeviceRemote {
        val listedWith = mutableListOf<String>()
        val revokedWith = mutableListOf<String>()

        override suspend fun listDevices(apiToken: String): RemoteResult<List<ServerDevice>> {
            listedWith += apiToken
            return listResult
        }

        override suspend fun revokeAllDevices(apiToken: String): RemoteResult<Unit> {
            revokedWith += apiToken
            return revokeResult
        }
    }

    private fun device(
        id: Long,
        name: String? = null,
        current: Boolean = false,
        lastUsed: String? = null,
        expires: String? = "2027-01-24T10:00:00Z",
    ) = ServerDevice(
        id = id,
        label = "Android",
        deviceName = name,
        createdAt = Instant.parse("2026-10-20T10:00:00Z"),
        lastUsedAt = lastUsed?.let(Instant::parse),
        expiresAt = expires?.let(Instant::parse),
        current = current,
    )

    private fun viewModel(remote: DeviceRemote, token: String? = "token-hp-ini") =
        DevicesViewModel(remote, tokenProvider = { token })

    @Test
    fun `muat daftar memakai token akun dan menaruh perangkat ini di atas`() = runBlocking {
        val remote = FakeDeviceRemote(
            listResult = RemoteResult.Success(
                listOf(
                    device(1, name = "Xiaomi 13", lastUsed = "2026-10-25T10:00:00Z"),
                    device(2, name = "Pixel 8", current = true, lastUsed = "2026-10-01T10:00:00Z"),
                    device(3, name = "Samsung S24", lastUsed = "2026-10-26T09:00:00Z"),
                ),
            ),
        )
        val vm = viewModel(remote)

        vm.load()

        val state = vm.state.value
        assertEquals(listOf("token-hp-ini"), remote.listedWith)
        assertFalse(state.loading)
        assertNull(state.loadFailure)
        // Perangkat ini (current) selalu pertama, lalu yang paling baru dipakai.
        assertEquals(listOf(2L, 3L, 1L), state.devices.map { it.id })
    }

    @Test
    fun `tanpa token server daftar tidak dimuat dan galatnya sesi berakhir`() = runBlocking {
        val remote = FakeDeviceRemote()
        val vm = viewModel(remote, token = null)

        vm.load()

        assertTrue(remote.listedWith.isEmpty())
        assertEquals(DevicesFailure.SessionEnded, vm.state.value.loadFailure)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `galat jaringan saat memuat tampil sebagai offline dan daftar lama dikosongkan`() = runBlocking {
        val remote = FakeDeviceRemote(listResult = RemoteResult.Failure(RemoteError.Unreachable))
        val vm = viewModel(remote)

        vm.load()

        assertEquals(DevicesFailure.Offline, vm.state.value.loadFailure)
        assertTrue(vm.state.value.devices.isEmpty())
    }

    @Test
    fun `401 saat memuat dipetakan ke sesi berakhir dan 5xx ke galat lain`() = runBlocking {
        val unauthorized = viewModel(FakeDeviceRemote(listResult = RemoteResult.Failure(RemoteError.Unauthorized)))
        unauthorized.load()
        assertEquals(DevicesFailure.SessionEnded, unauthorized.state.value.loadFailure)

        val broken = viewModel(FakeDeviceRemote(listResult = RemoteResult.Failure(RemoteError.Unexpected(500))))
        broken.load()
        assertEquals(DevicesFailure.Other, broken.state.value.loadFailure)
    }

    @Test
    fun `keluar dari semua perangkat mencabut lewat remote lalu menandai keluar lokal`() = runBlocking {
        val remote = FakeDeviceRemote()
        val vm = viewModel(remote)

        vm.signOutEverywhere()

        assertEquals(listOf("token-hp-ini"), remote.revokedWith)
        assertTrue(vm.state.value.signedOutEverywhere)
        assertFalse(vm.state.value.revoking)
        assertNull(vm.state.value.revokeFailure)
    }

    @Test
    fun `401 saat mencabut tetap dianggap keluar karena token memang sudah tidak sah`() = runBlocking {
        val remote = FakeDeviceRemote(revokeResult = RemoteResult.Failure(RemoteError.Unauthorized))
        val vm = viewModel(remote)

        vm.signOutEverywhere()

        assertTrue(vm.state.value.signedOutEverywhere)
        assertNull(vm.state.value.revokeFailure)
    }

    @Test
    fun `galat jaringan saat mencabut TIDAK memaksa keluar lokal dan menampilkan galat`() = runBlocking {
        val remote = FakeDeviceRemote(revokeResult = RemoteResult.Failure(RemoteError.Unreachable))
        val vm = viewModel(remote)

        vm.signOutEverywhere()

        assertFalse(vm.state.value.signedOutEverywhere)
        assertEquals(DevicesFailure.Offline, vm.state.value.revokeFailure)
        assertFalse(vm.state.value.revoking)
    }

    @Test
    fun `galat lain saat mencabut tidak memaksa keluar lokal`() = runBlocking {
        val remote = FakeDeviceRemote(revokeResult = RemoteResult.Failure(RemoteError.Unexpected(503)))
        val vm = viewModel(remote)

        vm.signOutEverywhere()

        assertFalse(vm.state.value.signedOutEverywhere)
        assertEquals(DevicesFailure.Other, vm.state.value.revokeFailure)
    }

    @Test
    fun `tanpa token keluar dari semua perangkat tidak memanggil remote dan menampilkan sesi berakhir`() = runBlocking {
        val remote = FakeDeviceRemote()
        val vm = viewModel(remote, token = null)

        vm.signOutEverywhere()

        assertTrue(remote.revokedWith.isEmpty())
        assertEquals(DevicesFailure.SessionEnded, vm.state.value.revokeFailure)
        assertFalse(vm.state.value.signedOutEverywhere)
    }

    @Test
    fun `setelah keluar berhasil, pemanggilan berikutnya tidak mencabut lagi`() = runBlocking {
        val remote = FakeDeviceRemote()
        val vm = viewModel(remote)

        vm.signOutEverywhere()
        vm.signOutEverywhere()

        assertEquals(1, remote.revokedWith.size)
    }

    @Test
    fun `perangkat kedaluwarsa tetap terdaftar dan terdeteksi dari masa berlakunya`() {
        val expired = device(4, name = "HP lama", expires = "2026-10-20T10:00:00Z")
        val alive = device(5, name = "Pixel 8", expires = "2027-01-24T10:00:00Z")
        val now = Instant.parse("2026-10-26T10:00:00Z")

        assertTrue(expired.isExpiredAt(now))
        assertFalse(alive.isExpiredAt(now))
        assertFalse(device(6, expires = null).isExpiredAt(now))
    }
}
