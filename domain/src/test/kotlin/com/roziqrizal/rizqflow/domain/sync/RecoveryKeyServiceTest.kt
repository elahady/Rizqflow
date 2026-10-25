package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.auth.runSuspend
import java.security.SecureRandom
import java.time.Instant
import java.util.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Penyedia kunci palsu: "kunci" adalah bytes tetap, supaya tes memeriksa kunci mana yang terpasang setelah pulihkan. */
private class FakeAccountKeys(var key: ByteArray) : AccountKeyProvider {
    var exportError: Exception? = null
    var importError: Exception? = null
    var imports = 0

    override fun getOrCreateAccountKey(): SyncAead = error("tidak dipakai di tes ini")

    override fun exportAccountKeyForRecovery(): ByteArray {
        exportError?.let { throw it }
        return key.copyOf()
    }

    override fun importAccountKeyFromRecovery(exportedAccountKey: ByteArray) {
        imports++
        importError?.let { throw it }
        key = exportedAccountKey.copyOf()
    }
}

/** Server palsu: menyimpan satu salinan dan mencatat SEMUA yang diterimanya. */
private class FakeRecoveryRemote : RecoveryKeyRemote {
    var stored: RecoveryKeyLookup.Found? = null
    val putBodies = mutableListOf<ByteArray>()
    val putTokens = mutableListOf<String>()
    var getCalls = 0
    var putFailure: RemoteError? = null
    var getFailure: RemoteError? = null

    /** Penyimpanan terjadi, tetapi balasan ke klien hilang (jaringan putus setelah komit). */
    var putCommitsThenFails: RemoteError? = null

    /** Server menyimpan bytes yang rusak (mis. galat charset MySQL) tetapi menjawab sukses. */
    var corruptStoredCopy = false

    /** `updated_at` pada tanda terima (dan pada salinan tersimpan). */
    var updatedAt = "2026-10-25T00:00:00Z"

    /** Bila diisi, unggahan menunggu gerbang ini sebelum menyimpan (mensimulasikan permintaan yang lama). */
    var putGate: CompletableDeferred<Unit>? = null

    /** Dipanggil tepat saat unggahan tiba di server, sebelum apa pun disimpan. */
    var onPut: (() -> Unit)? = null

    /** Jawaban GET yang dipaksa, menggantikan isi `stored`. */
    var getOverride: RemoteResult<RecoveryKeyLookup>? = null

    override suspend fun put(apiToken: String, wrappedKey: ByteArray): RemoteResult<RecoveryKeyReceipt> {
        putTokens += apiToken
        putBodies += wrappedKey.copyOf()
        onPut?.invoke()
        putGate?.await()
        putFailure?.let { return RemoteResult.Failure(it) }
        val bytes = wrappedKey.copyOf().also { if (corruptStoredCopy) it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        stored = RecoveryKeyLookup.Found(bytes, RECOVERY_KEY_VERSION, updatedAt)
        putCommitsThenFails?.let { return RemoteResult.Failure(it) }
        return RemoteResult.Success(RecoveryKeyReceipt(RECOVERY_KEY_VERSION, updatedAt))
    }

    override suspend fun get(apiToken: String): RemoteResult<RecoveryKeyLookup> {
        getCalls++
        getFailure?.let { return RemoteResult.Failure(it) }
        getOverride?.let { return it }
        return RemoteResult.Success(stored ?: RecoveryKeyLookup.NotFound)
    }

    override suspend fun delete(apiToken: String): RemoteResult<Unit> {
        stored = null
        return RemoteResult.Success(Unit)
    }
}

private class FakeTokens(var token: String? = "token-akun") : ApiTokenStore {
    override fun read() = token
    override fun expiresAt(): Instant? = null
    override fun save(token: String, expiresAt: Instant?): Boolean = true
    override fun clear() {
        token = null
    }
}

private class FakeStatusStore(var status: RecoveryStatus = RecoveryStatus.NotConfigured) : RecoveryStatusStore {
    override fun read() = status
    override fun markPending(serverUpdatedAt: String?) {
        status = RecoveryStatus.PendingConfirmation(serverUpdatedAt)
    }

    override fun markConfigured(at: Instant, serverUpdatedAt: String?) {
        status = RecoveryStatus.Configured(at, serverUpdatedAt)
    }

    override fun clear() {
        status = RecoveryStatus.NotConfigured
    }
}

class RecoveryKeyServiceTest {
    private val now = Instant.parse("2026-10-25T10:00:00Z")
    private val keyA = "KUNCI-AKUN-A-RAHASIA-123456789012345678".toByteArray()
    private val keyB = "KUNCI-AKUN-B-LAIN-98765432109876543210".toByteArray()

    private val remote = FakeRecoveryRemote()
    private val keysA = FakeAccountKeys(keyA)
    private val statusA = FakeStatusStore()
    private val serviceA = RecoveryKeyService(keysA, remote, FakeTokens(), statusA, clock = { now })

    private fun serviceB(keys: FakeAccountKeys, status: FakeStatusStore = FakeStatusStore(), tokens: FakeTokens = FakeTokens()) =
        RecoveryKeyService(keys, remote, tokens, status, clock = { now })

    private fun enableOk(service: RecoveryKeyService = serviceA, replace: Boolean = false): RecoveryCodeDisplay {
        val result = runSuspend { service.enableRecovery(replace) }
        return assertIs<EnableRecoveryResult.Ready>(result).display
    }

    // ---- enableRecovery ----

    @Test
    fun `enable sukses mengunggah bungkusan, menurunkan penanda ke menunggu konfirmasi, dan mengembalikan 24 kata`() {
        val display = enableOk()

        assertEquals(24, display.words.size)
        assertEquals(1, remote.putBodies.size)
        assertEquals(listOf("token-akun"), remote.putTokens)
        // `updated_at` dari tanda terima ikut tercatat di penanda.
        assertEquals(RecoveryStatus.PendingConfirmation("2026-10-25T00:00:00Z"), statusA.status)
    }

    @Test
    fun `yang dikirim ke server hanya bungkusan, tanpa kode 24 kata dan tanpa kunci terbaca`() {
        val display = enableOk()
        val sent = String(remote.putBodies.single(), Charsets.ISO_8859_1)

        assertFalse(display.words.joinToString(" ") in sent, "frasa kode terkirim ke server")
        assertFalse(String(keyA, Charsets.ISO_8859_1) in sent, "kunci terbaca terkirim ke server")
        assertFalse(sent.contains("KUNCI-AKUN"), "serpihan kunci terbaca terkirim ke server")
    }

    @Test
    fun `bungkusan yang diunggah terbuka dengan kata yang ditampilkan dan berisi kunci akun`() {
        val display = enableOk()

        val unwrapped = AccountKeyRecoveryCrypto.unwrap(remote.putBodies.single(), RecoveryCode.parse(display.words))

        assertContentEquals(keyA, unwrapped)
    }

    @Test
    fun `unggah yang hasilnya tidak bisa dipastikan karena jaringan tidak menampilkan kode dan penanda menunggu konfirmasi`() {
        remote.putFailure = RemoteError.Unreachable

        val result = runSuspend { serviceA.enableRecovery() }

        assertEquals(EnableRecoveryResult.Unverified, result)
        assertEquals(RecoveryStatus.PendingConfirmation(null), statusA.status)
    }

    @Test
    fun `403 tanpa Sync dipetakan jelas sebagai SyncRequired dan penanda kembali seperti semula`() {
        remote.putFailure = RemoteError.SyncRequired

        val result = runSuspend { serviceA.enableRecovery() }

        assertEquals(EnableRecoveryResult.SyncRequired, result)
        assertEquals(RecoveryStatus.NotConfigured, statusA.status)
    }

    @Test
    fun `401 422 dan galat klien 4xx adalah gagal pasti, tidak menampilkan kode, dan mengembalikan penanda`() {
        for (error in listOf(RemoteError.Unauthorized, RemoteError.Rejected("Tidak sah."), RemoteError.Unexpected(404), RemoteError.Unexpected(429))) {
            remote.putFailure = error
            val result = runSuspend { serviceA.enableRecovery() }
            assertEquals(EnableRecoveryResult.Failed(error), result, "untuk $error")
            assertEquals(RecoveryStatus.NotConfigured, statusA.status, "untuk $error")
        }
    }

    @Test
    fun `gagal pasti mengembalikan penanda terkonfigurasi persis seperti semula, lengkap dengan updated_at lamanya`() {
        val before = RecoveryStatus.Configured(now, "2026-10-01T00:00:00Z")
        statusA.status = before
        remote.putFailure = RemoteError.Rejected("Tidak sah.")

        runSuspend { serviceA.enableRecovery() }

        assertEquals(before, statusA.status)
    }

    @Test
    fun `gagal pasti mengembalikan penanda menunggu konfirmasi lama persis seperti semula`() {
        val before = RecoveryStatus.PendingConfirmation("2026-10-01T00:00:00Z")
        statusA.status = before
        remote.putFailure = RemoteError.Unauthorized

        runSuspend { serviceA.enableRecovery() }

        assertEquals(before, statusA.status)
    }

    @Test
    fun `galat server 5xx dan balasan 200 rusak tidak membuktikan apa pun, jadi hasilnya tidak bisa dipastikan`() {
        for (error in listOf(RemoteError.Unexpected(500), RemoteError.Unexpected(503), RemoteError.Unexpected(200))) {
            statusA.status = RecoveryStatus.NotConfigured
            remote.putFailure = error

            val result = runSuspend { serviceA.enableRecovery() }

            assertEquals(EnableRecoveryResult.Unverified, result, "untuk $error")
            assertEquals(RecoveryStatus.PendingConfirmation(null), statusA.status, "untuk $error")
        }
    }

    @Test
    fun `niat dicatat SEBELUM unggahan sampai ke server`() {
        statusA.status = RecoveryStatus.Configured(now, "lama")
        var statusSaatUnggah: RecoveryStatus? = null
        remote.onPut = { statusSaatUnggah = statusA.status }

        enableOk()

        assertEquals(RecoveryStatus.PendingConfirmation(null), statusSaatUnggah)
    }

    @Test
    fun `unggahan terkomit tetapi balasan hilang tidak meninggalkan penanda lama, dan percobaan berikutnya tidak memunculkan salinan perangkat lain`() {
        remote.putCommitsThenFails = RemoteError.Unreachable

        val first = runSuspend { serviceA.enableRecovery() }

        assertEquals(EnableRecoveryResult.Unverified, first)
        assertNotNull(remote.stored, "server sebenarnya sudah menyimpan")
        assertEquals(RecoveryStatus.PendingConfirmation(null), statusA.status)

        // Percobaan berikutnya tidak boleh menuduh "sudah ada salinan dari perangkat lain".
        remote.putCommitsThenFails = null
        val second = runSuspend { serviceA.enableRecovery() }

        assertIs<EnableRecoveryResult.Ready>(second)
    }

    // ---- verifikasi salinan di server setelah unggah ----

    @Test
    fun `salinan di server rusak (bytes berbeda) tidak menampilkan kode`() {
        remote.corruptStoredCopy = true

        val result = runSuspend { serviceA.enableRecovery() }

        assertEquals(EnableRecoveryResult.ServerCopyMismatch, result)
        assertEquals(RecoveryStatus.PendingConfirmation(null), statusA.status)
    }

    @Test
    fun `salinan yang hilang saat dibaca kembali setelah unggahan tidak sama`() {
        statusA.status = RecoveryStatus.PendingConfirmation(null) // lewati pemeriksaan salinan sebelum unggah
        remote.getOverride = RemoteResult.Success(RecoveryKeyLookup.NotFound)

        val result = runSuspend { serviceA.enableRecovery() }

        assertEquals(EnableRecoveryResult.ServerCopyMismatch, result)
    }

    @Test
    fun `salinan berversi lain saat dibaca kembali setelah unggahan tidak sama`() {
        statusA.status = RecoveryStatus.PendingConfirmation(null)
        remote.onPut = {
            remote.getOverride = RemoteResult.Success(
                RecoveryKeyLookup.Found(remote.putBodies.last(), RECOVERY_KEY_VERSION + 1, "2026-10-25T00:00:00Z"),
            )
        }

        val result = runSuspend { serviceA.enableRecovery() }

        assertEquals(EnableRecoveryResult.ServerCopyMismatch, result)
    }

    @Test
    fun `GET pemeriksa gagal karena jaringan setelah unggahan diterima adalah hasil tidak bisa dipastikan, penanda memuat updated_at`() {
        // Pemeriksaan salinan sebelum unggah tidak ada (penanda sudah Pending), jadi satu-satunya GET adalah pemeriksa.
        statusA.status = RecoveryStatus.PendingConfirmation(null)
        remote.getFailure = RemoteError.Unreachable

        val result = runSuspend { serviceA.enableRecovery() }

        assertEquals(EnableRecoveryResult.Unverified, result)
        assertEquals(RecoveryStatus.PendingConfirmation("2026-10-25T00:00:00Z"), statusA.status)
    }

    @Test
    fun `kode ditampilkan hanya bila salinan yang dibaca kembali sama byte demi byte`() {
        val display = enableOk()

        val serverCopy = remote.stored!!.wrappedKey
        assertContentEquals(remote.putBodies.single(), serverCopy)
        assertContentEquals(keyA, AccountKeyRecoveryCrypto.unwrap(serverCopy, RecoveryCode.parse(display.words)))
    }

    // ---- pembatalan dan serialisasi ----

    @Test
    fun `pembatalan coroutine di tengah unggahan tidak membuang pencatatan hasil`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        remote.putGate = gate
        val job = launch(start = CoroutineStart.UNDISPATCHED) { serviceA.enableRecovery() }
        try {
            assertEquals(1, remote.putBodies.size, "unggahan harus sudah terkirim dan menunggu")
            job.cancel()
        } finally {
            gate.complete(Unit) // jangan biarkan anak coroutine menggantung bila asersi gagal
        }

        job.join()

        assertNotNull(remote.stored, "server menerima unggahan walau layar sudah hilang")
        assertEquals(RecoveryStatus.PendingConfirmation("2026-10-25T00:00:00Z"), statusA.status)
    }

    @Test
    fun `dua unggahan bersamaan diserialkan, bukan saling menimpa`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        remote.putGate = gate
        val first = launch(start = CoroutineStart.UNDISPATCHED) { serviceA.enableRecovery() }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { serviceA.enableRecovery() }
        try {
            assertEquals(1, remote.putBodies.size, "unggahan kedua harus menunggu yang pertama")
        } finally {
            gate.complete(Unit) // jangan biarkan anak coroutine menggantung bila asersi gagal
        }
        joinAll(first, second)

        assertEquals(2, remote.putBodies.size)
    }

    @Test
    fun `tanpa token server tidak ada panggilan jaringan dan tidak ada kode`() {
        val service = RecoveryKeyService(keysA, remote, FakeTokens(token = null), statusA, clock = { now })

        val result = runSuspend { service.enableRecovery() }

        assertEquals(EnableRecoveryResult.NotSignedIn, result)
        assertEquals(0, remote.getCalls)
        assertTrue(remote.putBodies.isEmpty())
    }

    @Test
    fun `unggah yang tidak bisa dipastikan untuk akun terkonfigurasi menurunkan penanda ke menunggu konfirmasi (kode lama belum tentu berlaku)`() {
        statusA.status = RecoveryStatus.Configured(now, "2026-10-01T00:00:00Z")
        remote.putFailure = RemoteError.Unreachable

        runSuspend { serviceA.enableRecovery() }

        assertEquals(RecoveryStatus.PendingConfirmation(null), statusA.status)
    }

    @Test
    fun `buat kode baru yang berhasil membuat kode lama tidak berlaku, jadi penanda turun ke menunggu konfirmasi`() {
        statusA.status = RecoveryStatus.Configured(now)

        enableOk()

        assertEquals(RecoveryStatus.PendingConfirmation("2026-10-25T00:00:00Z"), statusA.status)
    }

    @Test
    fun `perangkat yang belum mengatur kode tidak diam-diam menimpa salinan milik perangkat lain di server`() {
        enableOk(serviceA) // perangkat A mengunggah dengan kunci A
        val stored = remote.putBodies.single()
        val keysB = FakeAccountKeys(keyB)

        val result = runSuspend { serviceB(keysB).enableRecovery() }

        assertEquals(EnableRecoveryResult.ExistingCopyOnServer, result)
        assertEquals(1, remote.putBodies.size, "tidak boleh ada unggahan kedua")
        assertContentEquals(stored, remote.stored!!.wrappedKey)
    }

    @Test
    fun `setelah pengguna setuju menimpa, perangkat yang belum mengatur kode boleh mengunggah`() {
        enableOk(serviceA)
        val keysB = FakeAccountKeys(keyB)

        val display = enableOk(serviceB(keysB), replace = true)

        assertEquals(2, remote.putBodies.size)
        assertContentEquals(keyB, AccountKeyRecoveryCrypto.unwrap(remote.putBodies.last(), RecoveryCode.parse(display.words)))
    }

    @Test
    fun `perangkat yang sudah mengatur kode tidak perlu memeriksa salinan di server dulu`() {
        statusA.status = RecoveryStatus.Configured(now)

        enableOk()

        assertEquals(1, remote.getCalls, "hanya GET pemeriksa setelah unggah; pemeriksaan salinan sebelum unggah dilewati")
    }

    @Test
    fun `pemeriksaan salinan yang gagal karena jaringan membatalkan enable tanpa kode`() {
        remote.getFailure = RemoteError.Unreachable

        val result = runSuspend { serviceA.enableRecovery() }

        assertEquals(EnableRecoveryResult.Failed(RemoteError.Unreachable), result)
        assertTrue(remote.putBodies.isEmpty())
    }

    @Test
    fun `kunci yang tidak bisa dibaca membatalkan enable sebelum ada yang diunggah`() {
        keysA.exportError = IllegalStateException("Keystore bermasalah")

        val result = runSuspend { serviceA.enableRecovery() }

        assertEquals(EnableRecoveryResult.KeyUnavailable, result)
        assertTrue(remote.putBodies.isEmpty())
        assertEquals(RecoveryStatus.NotConfigured, statusA.status)
    }

    // ---- confirm dan verifikasi catatan ----

    @Test
    fun `confirm dengan jawaban benar menandai terkonfigurasi dengan waktu sekarang`() {
        val display = enableOk()
        val challenge = display.newChallenge(Random(1))
        val answers = challenge.positions.associateWith { display.words[it - 1] }

        assertTrue(serviceA.confirm(challenge, answers))

        assertEquals(RecoveryStatus.Configured(now, "2026-10-25T00:00:00Z"), statusA.status)
    }

    @Test
    fun `confirm menerima huruf besar dan spasi di sekitar kata`() {
        val display = enableOk()
        val challenge = display.newChallenge(Random(2))
        val answers = challenge.positions.associateWith { "  ${display.words[it - 1].uppercase()} " }

        assertTrue(serviceA.confirm(challenge, answers))
    }

    @Test
    fun `confirm dengan satu kata keliru gagal dan penanda tetap menunggu konfirmasi`() {
        val display = enableOk()
        val challenge = display.newChallenge(Random(3))
        val answers = challenge.positions.associateWith { display.words[it - 1] }.toMutableMap()
        val wrongPosition = challenge.positions.last()
        answers[wrongPosition] = if (display.words[wrongPosition - 1] == "abandon") "ability" else "abandon"

        assertFalse(serviceA.confirm(challenge, answers))

        assertEquals(RecoveryStatus.PendingConfirmation("2026-10-25T00:00:00Z"), statusA.status)
    }

    @Test
    fun `confirm tanpa menjawab semua nomor gagal`() {
        val display = enableOk()
        val challenge = display.newChallenge(Random(4))
        val partial = challenge.positions.drop(1).associateWith { display.words[it - 1] }

        assertFalse(serviceA.confirm(challenge, partial))
        assertFalse(serviceA.confirm(challenge, emptyMap()))
        assertEquals(RecoveryStatus.PendingConfirmation("2026-10-25T00:00:00Z"), statusA.status)
    }

    @Test
    fun `confirm tidak bisa dilewati dengan menjawab nomor lain yang tidak diundi`() {
        val display = enableOk()
        val challenge = display.newChallenge(Random(5))
        val others = (1..24).filter { it !in challenge.positions }.take(3).associateWith { display.words[it - 1] }

        assertFalse(serviceA.confirm(challenge, others))
    }

    @Test
    fun `tantangan memilih tiga nomor berbeda berurut naik dalam 1 sampai 24 dan berubah tiap undian`() {
        val display = enableOk()
        val seen = mutableSetOf<List<Int>>()
        repeat(40) { i ->
            val positions = display.newChallenge(Random(i.toLong())).positions
            assertEquals(3, positions.size)
            assertEquals(positions.sorted(), positions)
            assertEquals(3, positions.toSet().size)
            assertTrue(positions.all { it in 1..24 })
            seen += positions
        }
        assertTrue(seen.size > 10, "undian harus beragam, hanya ${seen.size} variasi")
    }

    // ---- kode tidak bocor lewat string ----
    // Asersi memakai string TETAP, bukan "kata tidak ada di teks": nama kelas bisa kebetulan memuat kata baku
    // ("Recovery" memuat "cover", "Ready" adalah kata baku), yang membuat tes acak itu flaky ~1%.

    @Test
    fun `toString objek hasil, tampilan, tantangan, dan RecoveryCode hanya string tetap tanpa kata`() {
        val ready = runSuspend { serviceA.enableRecovery() }
        val display = assertIs<EnableRecoveryResult.Ready>(ready).display
        val challenge = display.newChallenge(Random(6))

        assertEquals("Ready(disembunyikan)", ready.toString())
        assertEquals("RecoveryCodeDisplay(disembunyikan)", display.toString())
        assertEquals("RecoveryChallenge(posisi=${challenge.positions})", challenge.toString())
        assertEquals("RecoveryCode(disembunyikan)", RecoveryCode.parse(display.words).toString())
    }

    @Test
    fun `hasil pulihkan hanya membawa nama hasil, bukan kata`() {
        val display = enableOk()
        val keysB = FakeAccountKeys(keyB)

        val result = runSuspend { serviceB(keysB).recoverKey(display.words) }

        assertEquals(RecoverKeyResult.Recovered, result)
        assertEquals("Recovered", result.toString())
    }

    // ---- recoverKey ----

    @Test
    fun `jalur sukses ujung ke ujung memulihkan kunci A di perangkat B`() {
        val display = enableOk()
        val keysB = FakeAccountKeys(keyB)
        val statusB = FakeStatusStore()

        val result = runSuspend { serviceB(keysB, statusB).recoverKey(display.words) }

        assertEquals(RecoverKeyResult.Recovered, result)
        assertContentEquals(keyA, keysB.key)
        assertEquals(RecoveryStatus.Configured(now, "2026-10-25T00:00:00Z"), statusB.status)
    }

    @Test
    fun `pulihkan menerima ketikan dengan huruf besar dan spasi berlebih`() {
        val display = enableOk()
        val keysB = FakeAccountKeys(keyB)

        val result = runSuspend { serviceB(keysB).recoverKey(display.words.map { " ${it.uppercase()}  " }) }

        assertEquals(RecoverKeyResult.Recovered, result)
        assertContentEquals(keyA, keysB.key)
    }

    @Test
    fun `kata salah ketik ditolak sebelum menyentuh jaringan atau kunci`() {
        val display = enableOk()
        val keysB = FakeAccountKeys(keyB)
        val getsBefore = remote.getCalls
        val typo = display.words.toMutableList().also { it[4] = "abondon" }

        val result = runSuspend { serviceB(keysB).recoverKey(typo) }

        assertEquals(RecoverKeyResult.InvalidCode(InvalidRecoveryCodeKind.UNKNOWN_WORD), result)
        assertEquals(getsBefore, remote.getCalls)
        assertContentEquals(keyB, keysB.key)
    }

    @Test
    fun `jumlah kata salah ditolak dengan alasan yang spesifik`() {
        val display = enableOk()
        val keysB = FakeAccountKeys(keyB)

        val result = runSuspend { serviceB(keysB).recoverKey(display.words.dropLast(1)) }

        assertEquals(RecoverKeyResult.InvalidCode(InvalidRecoveryCodeKind.WRONG_COUNT), result)
        assertContentEquals(keyB, keysB.key)
    }

    @Test
    fun `dua kata tertukar (checksum salah) ditolak sebagai checksum`() {
        val display = enableOk()
        val keysB = FakeAccountKeys(keyB)
        val swapped = display.words.toMutableList()
        // Tukar dua kata yang berbeda; mengulang bila kebetulan sama supaya tes tidak menguji kasus kosong.
        var i = 0
        while (swapped[i] == swapped[i + 1]) i++
        swapped[i] = swapped[i + 1].also { swapped[i + 1] = swapped[i] }

        val result = runSuspend { serviceB(keysB).recoverKey(swapped) }

        // Tukar dua kata hampir pasti merusak checksum 8 bit; peluang 1/256 lolos checksum lalu jadi WrongCode.
        assertTrue(
            result == RecoverKeyResult.InvalidCode(InvalidRecoveryCodeKind.BAD_CHECKSUM) || result == RecoverKeyResult.WrongCode,
            "hasil: $result",
        )
        assertContentEquals(keyB, keysB.key)
    }

    @Test
    fun `404 saat pulihkan menjadi tidak ada salinan, bukan galat, dan kunci tidak berubah`() {
        val keysB = FakeAccountKeys(keyB)
        val code = RecoveryCode.generate()

        val result = runSuspend { serviceB(keysB).recoverKey(code.words) }

        assertEquals(RecoverKeyResult.NoCopyOnServer, result)
        assertContentEquals(keyB, keysB.key)
    }

    @Test
    fun `kode valid tetapi bukan milik salinan ini menjadi WrongCode dan kunci lokal utuh`() {
        enableOk()
        val keysB = FakeAccountKeys(keyB)
        val statusB = FakeStatusStore()

        val result = runSuspend { serviceB(keysB, statusB).recoverKey(RecoveryCode.generate().words) }

        assertEquals(RecoverKeyResult.WrongCode, result)
        assertContentEquals(keyB, keysB.key)
        assertEquals(0, keysB.imports)
        assertEquals(RecoveryStatus.NotConfigured, statusB.status)
    }

    @Test
    fun `salinan di server yang rusak diperlakukan sama dengan kode salah, tanpa membedakan lebih jauh`() {
        val display = enableOk()
        val good = remote.stored!!
        val corrupt = good.wrappedKey.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        remote.stored = RecoveryKeyLookup.Found(corrupt, good.version, good.updatedAt)
        val keysB = FakeAccountKeys(keyB)

        val result = runSuspend { serviceB(keysB).recoverKey(display.words) }

        assertEquals(RecoverKeyResult.WrongCode, result)
        assertContentEquals(keyB, keysB.key)
    }

    @Test
    fun `versi format yang belum dikenal ditolak tanpa menebak`() {
        val display = enableOk()
        val good = remote.stored!!
        remote.stored = RecoveryKeyLookup.Found(good.wrappedKey, RECOVERY_KEY_VERSION + 1, good.updatedAt)
        val keysB = FakeAccountKeys(keyB)

        val result = runSuspend { serviceB(keysB).recoverKey(display.words) }

        assertEquals(RecoverKeyResult.UnsupportedVersion, result)
        assertContentEquals(keyB, keysB.key)
    }

    @Test
    fun `isi bungkusan yang tak bisa dipasang sebagai kunci menjadi BackupUnreadable dan kunci lokal utuh`() {
        val display = enableOk()
        val keysB = FakeAccountKeys(keyB)
        keysB.importError = InvalidAccountKeyExport()
        val statusB = FakeStatusStore()

        val result = runSuspend { serviceB(keysB, statusB).recoverKey(display.words) }

        assertEquals(RecoverKeyResult.BackupUnreadable, result)
        assertContentEquals(keyB, keysB.key)
        assertEquals(RecoveryStatus.NotConfigured, statusB.status)
    }

    @Test
    fun `kegagalan menulis kunci pulihan menjadi KeyUnavailable dan tidak menandai terkonfigurasi`() {
        val display = enableOk()
        val keysB = FakeAccountKeys(keyB)
        keysB.importError = java.io.IOException("disk penuh")
        val statusB = FakeStatusStore()

        val result = runSuspend { serviceB(keysB, statusB).recoverKey(display.words) }

        assertEquals(RecoverKeyResult.KeyUnavailable, result)
        assertEquals(RecoveryStatus.NotConfigured, statusB.status)
    }

    @Test
    fun `401 403 dan jaringan saat pulihkan menjadi Failed dan kunci lokal utuh`() {
        val display = enableOk()
        for (error in listOf(RemoteError.Unauthorized, RemoteError.SyncRequired, RemoteError.Unreachable, RemoteError.Unexpected(502))) {
            remote.getFailure = error
            val keysB = FakeAccountKeys(keyB)
            val statusB = FakeStatusStore()

            val result = runSuspend { serviceB(keysB, statusB).recoverKey(display.words) }

            assertEquals(RecoverKeyResult.Failed(error), result, "untuk $error")
            assertContentEquals(keyB, keysB.key)
            assertEquals(RecoveryStatus.NotConfigured, statusB.status)
        }
    }

    @Test
    fun `pulihkan tanpa token server tidak memanggil jaringan`() {
        val display = enableOk()
        val getsBefore = remote.getCalls

        val result = runSuspend { serviceB(FakeAccountKeys(keyB), tokens = FakeTokens(token = null)).recoverKey(display.words) }

        assertEquals(RecoverKeyResult.NotSignedIn, result)
        assertEquals(getsBefore, remote.getCalls)
    }

    // ---- tawaran di perangkat baru dan lain-lain ----

    @Test
    fun `checkRemoteCopy membedakan ada, belum ada, dan tidak diketahui`() {
        val service = serviceB(FakeAccountKeys(keyB))
        assertEquals(RemoteCopyCheck.NONE, runSuspend { service.checkRemoteCopy() })

        enableOk()
        assertEquals(RemoteCopyCheck.EXISTS, runSuspend { service.checkRemoteCopy() })

        remote.getFailure = RemoteError.Unreachable
        assertEquals(RemoteCopyCheck.UNKNOWN, runSuspend { service.checkRemoteCopy() })

        remote.getFailure = null
        val noToken = serviceB(FakeAccountKeys(keyB), tokens = FakeTokens(token = null))
        assertEquals(RemoteCopyCheck.UNKNOWN, runSuspend { noToken.checkRemoteCopy() })
    }

    @Test
    fun `forgetStatus menghapus penanda setelah akun server dihapus`() {
        statusA.status = RecoveryStatus.Configured(now)

        serviceA.forgetStatus()

        assertEquals(RecoveryStatus.NotConfigured, statusA.status)
    }

    // ---- RecoveryWordsInput ----

    @Test
    fun `split memecah tempelan berbagai bentuk menjadi kata huruf kecil`() {
        val pasted = "1. Abandon, 2) ability\n3: ABLE\t about   above"

        assertEquals(listOf("abandon", "ability", "able", "about", "above"), RecoveryWordsInput.split(pasted))
    }

    @Test
    fun `split mengabaikan teks kosong dan hanya angka`() {
        assertEquals(emptyList(), RecoveryWordsInput.split("  \n 12 34 "))
    }

    @Test
    fun `unknownWords melaporkan nomor dan isi kata yang tidak ada di daftar`() {
        val words = listOf("abandon", "abondon", "ability", "zzzz")

        assertEquals(listOf(2 to "abondon", 4 to "zzzz"), RecoveryWordsInput.unknownWords(words))
    }

    @Test
    fun `isKnownWord mengenali kata baku dan menolak yang lain`() {
        assertTrue(RecoveryCode.isKnownWord("zoo"))
        assertFalse(RecoveryCode.isKnownWord("Zoo"))
        assertFalse(RecoveryCode.isKnownWord("zooo"))
    }

    @Test
    fun `dua undian dari acak sistem tidak selalu sama`() {
        val display = enableOk()
        val draws = (1..30).map { display.newChallenge(SecureRandom()).positions }.toSet()

        assertNotEquals(1, draws.size)
    }

    // ---- updated_at, pembandingan dengan server ----

    @Test
    fun `confirm membawa updated_at yang dicatat saat unggah ke penanda terkonfigurasi`() {
        val display = enableOk()
        val challenge = display.newChallenge(Random(7))

        assertTrue(serviceA.confirm(challenge, challenge.positions.associateWith { display.words[it - 1] }))

        assertEquals(RecoveryStatus.Configured(now, "2026-10-25T00:00:00Z"), statusA.status)
    }

    @Test
    fun `pulihkan mencatat updated_at salinan yang dipasang`() {
        val display = enableOk()
        val statusB = FakeStatusStore()

        runSuspend { serviceB(FakeAccountKeys(keyB), statusB).recoverKey(display.words) }

        assertEquals(RecoveryStatus.Configured(now, "2026-10-25T00:00:00Z"), statusB.status)
    }

    private fun configuredA(serverUpdatedAt: String?) {
        statusA.status = RecoveryStatus.Configured(now, serverUpdatedAt)
    }

    @Test
    fun `salinan di server tidak berubah sejak dicatat adalah IN_SYNC`() {
        enableOk()
        configuredA("2026-10-25T00:00:00Z")

        assertEquals(RecoveryCopyState.IN_SYNC, runSuspend { serviceA.compareWithServer() })
    }

    @Test
    fun `salinan diganti dari perangkat lain (updated_at berbeda) adalah REPLACED`() {
        enableOk()
        configuredA("2026-10-25T00:00:00Z")
        remote.stored = RecoveryKeyLookup.Found(remote.stored!!.wrappedKey, RECOVERY_KEY_VERSION, "2026-10-26T08:00:00Z")

        assertEquals(RecoveryCopyState.REPLACED, runSuspend { serviceA.compareWithServer() })
    }

    @Test
    fun `salinan yang sudah tidak ada di server adalah MISSING`() {
        configuredA("2026-10-25T00:00:00Z")

        assertEquals(RecoveryCopyState.MISSING, runSuspend { serviceA.compareWithServer() })
    }

    @Test
    fun `pembandingan tanpa dasar atau tanpa kabar dari server tidak menarik kesimpulan`() {
        enableOk()

        // Penanda masih menunggu konfirmasi, belum terkonfigurasi.
        assertEquals(RecoveryCopyState.UNKNOWN, runSuspend { serviceA.compareWithServer() })
        // Penanda lama tanpa updated_at.
        configuredA(null)
        assertEquals(RecoveryCopyState.UNKNOWN, runSuspend { serviceA.compareWithServer() })
        // Jaringan gagal.
        configuredA("2026-10-25T00:00:00Z")
        remote.getFailure = RemoteError.Unreachable
        assertEquals(RecoveryCopyState.UNKNOWN, runSuspend { serviceA.compareWithServer() })
        // Belum masuk.
        remote.getFailure = null
        val noToken = RecoveryKeyService(keysA, remote, FakeTokens(token = null), statusA, clock = { now })
        assertEquals(RecoveryCopyState.UNKNOWN, runSuspend { noToken.compareWithServer() })
        // Belum diatur sama sekali.
        statusA.status = RecoveryStatus.NotConfigured
        assertEquals(RecoveryCopyState.UNKNOWN, runSuspend { serviceA.compareWithServer() })
    }

    @Test
    fun `pembandingan tidak mengubah penanda`() {
        enableOk()
        configuredA("2026-10-25T00:00:00Z")
        remote.stored = RecoveryKeyLookup.Found(remote.stored!!.wrappedKey, RECOVERY_KEY_VERSION, "lain")

        runSuspend { serviceA.compareWithServer() }

        assertEquals(RecoveryStatus.Configured(now, "2026-10-25T00:00:00Z"), statusA.status)
    }

    // ---- peringatan kata tak dikenal saat mengetik ----

    @Test
    fun `token terakhir yang masih diketik tidak diperingatkan`() {
        // "aban" belum utuh dan belum diakhiri pemisah.
        assertEquals(emptyList(), RecoveryWordsInput.unknownWordsToWarn("abandon aban", pasted = false))
        assertEquals(emptyList(), RecoveryWordsInput.unknownWordsToWarn("aban", pasted = false))
        assertEquals(emptyList(), RecoveryWordsInput.unknownWordsToWarn("ABAN", pasted = false))
    }

    @Test
    fun `token terakhir diperingatkan setelah diakhiri spasi atau baris baru`() {
        assertEquals(listOf(2 to "aban"), RecoveryWordsInput.unknownWordsToWarn("abandon aban ", pasted = false))
        assertEquals(listOf(2 to "aban"), RecoveryWordsInput.unknownWordsToWarn("abandon aban\n", pasted = false))
        assertEquals(listOf(2 to "aban"), RecoveryWordsInput.unknownWordsToWarn("abandon aban,", pasted = false))
    }

    @Test
    fun `kata tak dikenal di tengah selalu diperingatkan walau token terakhir masih diketik`() {
        assertEquals(listOf(1 to "abondon"), RecoveryWordsInput.unknownWordsToWarn("abondon ability abo", pasted = false))
    }

    @Test
    fun `hasil tempel diperingatkan seluruhnya termasuk kata terakhir`() {
        assertEquals(listOf(2 to "zzzz"), RecoveryWordsInput.unknownWordsToWarn("abandon zzzz", pasted = true))
    }

    @Test
    fun `kata terakhir yang sah tidak pernah diperingatkan`() {
        assertEquals(emptyList(), RecoveryWordsInput.unknownWordsToWarn("abandon ability", pasted = false))
    }

    @Test
    fun `tempel dikenali dari tambahan lebih dari satu karakter sekaligus`() {
        assertFalse(RecoveryWordsInput.isBulkChange("abandon ab", "abandon abi"))
        assertFalse(RecoveryWordsInput.isBulkChange("abandon ab", "abandon a"))
        assertTrue(RecoveryWordsInput.isBulkChange("", "abandon ability"))
        assertTrue(RecoveryWordsInput.isBulkChange("abandon ", "abandon ability"))
    }
}
