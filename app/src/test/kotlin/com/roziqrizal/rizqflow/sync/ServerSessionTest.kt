package com.roziqrizal.rizqflow.sync

import com.roziqrizal.rizqflow.domain.sync.AccountRemote
import com.roziqrizal.rizqflow.domain.sync.ApiAccount
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaim
import com.roziqrizal.rizqflow.domain.sync.RefreshedToken
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ServerSession]: satu tempat keadaan sesi server dan penanganan 401 (Tahap 12). */
class ServerSessionTest {
    private val expiry = Instant.parse("2027-01-22T10:00:00Z")

    // --- Keadaan awal ---

    @Test
    fun `tanpa token tersimpan perlu sambungkan ulang`() {
        assertTrue(ServerSession(FakeTokenStore(token = null)).needsReconnect.value)
    }

    @Test
    fun `dengan token tersimpan tidak perlu sambungkan ulang`() {
        assertFalse(ServerSession(FakeTokenStore(token = "t")).needsReconnect.value)
    }

    @Test
    fun `token lama tanpa masa berlaku terbaca dengan masa berlaku belum diketahui`() {
        val session = ServerSession(FakeTokenStore(token = "t-lama", expiry = null))
        assertEquals("t-lama", session.token())
        assertNull(session.expiresAt())
    }

    // --- 401 membersihkan token akun yang tepat ---

    @Test
    fun `401 membersihkan token dan menaikkan perlu sambungkan ulang`() {
        val store = FakeTokenStore(token = "t", expiry = expiry)
        val session = ServerSession(store)

        assertTrue(session.onUnauthorized("t"))

        assertNull(store.token)
        assertNull(session.expiresAt())
        assertTrue(session.needsReconnect.value)
    }

    @Test
    fun `401 untuk token yang sudah diganti tidak membuang token baru yang sah`() {
        val store = FakeTokenStore(token = "t-baru", expiry = expiry)
        val session = ServerSession(store)

        // Permintaan lama yang terbang saat token diputar menerima 401 untuk token lama.
        assertFalse(session.onUnauthorized("t-lama"))

        assertEquals("t-baru", store.token)
        assertFalse(session.needsReconnect.value)
    }

    @Test
    fun `401 pada satu akun tidak menyentuh token akun lain`() {
        val storeA = FakeTokenStore(token = "token-a")
        val storeB = FakeTokenStore(token = "token-b")
        val sessionA = ServerSession(storeA)
        val sessionB = ServerSession(storeB)

        sessionA.onUnauthorized("token-a")

        assertNull(storeA.token)
        assertEquals("token-b", storeB.token)
        assertTrue(sessionA.needsReconnect.value)
        assertFalse(sessionB.needsReconnect.value)
    }

    // --- Pembungkus: 401 dari endpoint terlindungi mana pun dipusatkan ---

    @Test
    fun `401 dari push sync mengakhiri sesi lewat pembungkus`() = runBlocking {
        val store = FakeTokenStore(token = "t")
        val session = ServerSession(store)

        val result = session.guard(FailingSyncRemote(RemoteError.Unauthorized)).push("t", emptyList())

        assertEquals(RemoteResult.Failure(RemoteError.Unauthorized), result) // pemanggil tetap melihat jawaban aslinya
        assertNull(store.token)
        assertTrue(session.needsReconnect.value)
    }

    @Test
    fun `401 dari pull sync, ruang keluarga, dan klaim pembelian masing-masing mengakhiri sesi`() = runBlocking {
        fun fresh() = FakeTokenStore(token = "t").let { it to ServerSession(it) }

        fresh().let { (store, session) ->
            session.guard(FailingSyncRemote(RemoteError.Unauthorized)).pull("t")
            assertNull(store.token, "pull")
            assertTrue(session.needsReconnect.value)
        }
        fresh().let { (store, session) ->
            session.guard(FailingRoomMembershipRemote(RemoteError.Unauthorized)).join("t", "ruang-1")
            assertNull(store.token, "join")
            assertTrue(session.needsReconnect.value)
        }
        fresh().let { (store, session) ->
            session.guard(FailingRoomMembershipRemote(RemoteError.Unauthorized)).members("t", "ruang-1")
            assertNull(store.token, "members")
            assertTrue(session.needsReconnect.value)
        }
        fresh().let { (store, session) ->
            session.guard(FailingPurchaseRemote(RemoteError.Unauthorized)).claim("t", listOf(PurchaseClaim("rizqflow_pro", "pt")))
            assertNull(store.token, "klaim pembelian")
            assertTrue(session.needsReconnect.value)
        }
    }

    @Test
    fun `401 dari hapus akun, putar token, dan keluar mengakhiri sesi`() = runBlocking {
        val calls = listOf<suspend (AccountRemote) -> Unit>(
            { it.deleteAccount("t") },
            { it.refresh("t") },
            { it.logout("t") },
        )
        for (call in calls) {
            val store = FakeTokenStore(token = "t")
            val session = ServerSession(store)
            call(session.guard(FakeAccountRemote(valid = emptySet()))) // server tidak mengenal token itu: 401
            assertNull(store.token)
            assertTrue(session.needsReconnect.value)
        }
    }

    @Test
    fun `401 dari masuk dengan Google bukan sesi berakhir, token tersimpan tidak disentuh`() = runBlocking {
        val store = FakeTokenStore(token = "t")
        val session = ServerSession(store)
        val remote = FakeAccountRemote().apply { loginResult = RemoteResult.Failure(RemoteError.Unauthorized) }

        session.guard(remote).loginWithGoogle("id-token-ditolak", "perangkat-1")

        assertEquals("t", store.token)
        assertFalse(session.needsReconnect.value)
    }

    @Test
    fun `jaringan mati dan galat lain tidak pernah mencabut apa pun`() = runBlocking {
        val errors = listOf(RemoteError.Unreachable, RemoteError.SyncRequired, RemoteError.Rejected("x"), RemoteError.Unexpected(503))
        for (error in errors) {
            val store = FakeTokenStore(token = "t", expiry = expiry)
            val session = ServerSession(store)

            session.guard(FailingSyncRemote(error)).push("t", emptyList())
            session.guard(FailingRoomMembershipRemote(error)).members("t", "r")
            session.guard(FailingPurchaseRemote(error)).claim("t", emptyList())
            session.guard(FakeAccountRemote().apply { refreshFailure = error }).refresh("t")

            assertEquals("t", store.token, "token harus tetap untuk $error")
            assertEquals(expiry, session.expiresAt())
            assertFalse(session.needsReconnect.value)
        }
    }

    // --- Masuk / sambungkan ulang ---

    @Test
    fun `masuk berhasil menyimpan token beserta masa berlakunya dan menurunkan perlu sambungkan ulang`() = runBlocking {
        val store = FakeTokenStore(token = null)
        val session = ServerSession(store)
        assertTrue(session.needsReconnect.value)

        val result = session.connect(FakeAccountRemote(), "id-token", "perangkat-1")

        assertIs<RemoteResult.Success<*>>(result)
        assertEquals("login-token", store.token)
        assertEquals(Instant.parse("2027-03-01T00:00:00Z"), session.expiresAt())
        assertFalse(session.needsReconnect.value)
        assertFalse(session.connecting.value)
    }

    @Test
    fun `masuk gagal tidak mengubah apa pun dan tidak macet di keadaan menyambung`() = runBlocking {
        val store = FakeTokenStore(token = null)
        val session = ServerSession(store)
        val remote = FakeAccountRemote().apply { loginResult = RemoteResult.Failure(RemoteError.Unreachable) }

        val result = session.connect(remote, "id-token", "perangkat-1")

        assertIs<RemoteResult.Failure>(result)
        assertNull(store.token)
        assertTrue(session.needsReconnect.value, "barisnya tetap ada supaya bisa dicoba lagi")
        assertFalse(session.connecting.value)
    }

    @Test
    fun `sesudah sambung ulang token baru dipakai dan 401 token lama tidak mengakhiri sesi baru`() = runBlocking {
        val store = FakeTokenStore(token = "t-lama")
        val session = ServerSession(store)
        session.onUnauthorized("t-lama") // sesi berakhir
        session.connect(FakeAccountRemote(), "id-token", "perangkat-1")

        // Permintaan sisa yang masih membawa token lama menerima 401.
        session.guard(FailingSyncRemote(RemoteError.Unauthorized)).push("t-lama", emptyList())

        assertEquals("login-token", store.token)
        assertFalse(session.needsReconnect.value)
    }

    // --- Token hasil putar ---

    @Test
    fun `token hasil putar disimpan bila yang tersimpan masih token yang diputar`() {
        val store = FakeTokenStore(token = "t-lama", expiry = expiry)
        val session = ServerSession(store)

        val outcome = session.onRotated("t-lama", RefreshedToken("t-baru", expiry.plusSeconds(100)), at = expiry)

        assertEquals(RotationOutcome.SAVED, outcome)
        assertEquals("t-baru", store.token)
        assertEquals(expiry.plusSeconds(100), session.expiresAt())
    }

    @Test
    fun `token hasil putar dibuang bila pengguna sudah keluar selama putar`() {
        val store = FakeTokenStore(token = "t-lama")
        val session = ServerSession(store)
        session.clearLocal()

        val outcome = session.onRotated("t-lama", RefreshedToken("t-baru", expiry), at = expiry)

        assertEquals(RotationOutcome.SUPERSEDED, outcome)
        assertNull(store.token, "token pengganti tidak boleh menghidupkan lagi sesi yang sudah ditutup")
    }

    @Test
    fun `kegagalan menulis token hasil putar dilaporkan, tidak diam`() {
        val store = FakeTokenStore(token = "t-lama", persist = false)
        val session = ServerSession(store)

        val outcome = session.onRotated("t-lama", RefreshedToken("t-baru", expiry), at = expiry)

        assertEquals(RotationOutcome.NOT_PERSISTED, outcome)
        assertEquals("t-baru", store.token, "tetap dipakai proses ini")
        assertFalse(session.needsReconnect.value)
    }

    @Test
    fun `clearLocal membersihkan token dan menaikkan perlu sambungkan ulang`() {
        val store = FakeTokenStore(token = "t", expiry = expiry)
        val session = ServerSession(store)

        session.clearLocal()

        assertNull(store.token)
        assertTrue(session.needsReconnect.value)
    }

    // --- Baris "Sambungkan ulang ke server" ---

    @Test
    fun `baris sambungkan ulang hanya untuk akun Google di data asli yang sesinya berakhir`() {
        assertTrue(shouldOfferReconnect(google = true, demo = false, needsReconnect = true))
        assertFalse(shouldOfferReconnect(google = true, demo = false, needsReconnect = false), "sesi masih berlaku")
        assertFalse(shouldOfferReconnect(google = false, demo = false, needsReconnect = true), "akun lokal atau uji")
        assertFalse(shouldOfferReconnect(google = true, demo = true, needsReconnect = true), "mode demo")
    }

    // --- Masuk email+sandi (Tahap 14): token yang sudah diterima server disimpan ke ruang kerja akun ini ---

    @Test
    fun `adopt menyimpan token email dan menurunkan perlu sambungkan ulang`() = runBlocking {
        val store = FakeTokenStore(token = null)
        val session = ServerSession(store, clock = { savedAt })
        assertTrue(session.needsReconnect.value)

        session.adopt(ApiAccount(apiToken = "token-email", userId = 9, name = "Roziq", email = "roziq@example.com", expiresAt = expiry))

        assertEquals("token-email", store.token)
        assertEquals(expiry, store.expiry)
        assertFalse(session.needsReconnect.value)
    }

    private val savedAt = Instant.parse("2026-10-25T08:00:00Z")
}
