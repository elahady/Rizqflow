package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import java.security.SecureRandom
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Penanda NON-RAHASIA "kode pemulihan sudah diatur" per akun di perangkat ini. Tidak pernah memuat kode,
 * kata, atau kunci; hanya status, waktu, dan `updated_at` dari server. Implementasi sungguhan
 * (`SharedPrefsRecoveryStatusStore`) ada di `:data`.
 *
 * `serverUpdatedAt` = `updated_at` salinan di server persis seperti di tanda terima unggahan (ISO-8601 UTC). Dipakai
 * membandingkan: bila server kelak punya `updated_at` lain, salinan itu sudah diganti dari perangkat lain dan kode
 * di kertas mungkin tidak berlaku lagi ([RecoveryKeyService.compareWithServer]). Null = tidak diketahui (penanda
 * lama, atau hasil unggahan yang tidak bisa dipastikan).
 */
sealed interface RecoveryStatus {
    /** Belum pernah diatur dari perangkat ini (atau salinan di server sudah dihapus). */
    data object NotConfigured : RecoveryStatus

    /**
     * Pengguna belum membuktikan mencatat kodenya (layar ditutup, aplikasi mati, verifikasi belum lulus), ATAU
     * hasil unggahan tidak bisa dipastikan (jaringan putus di tengah, balasan hilang). Kode lama (bila ada) mungkin
     * sudah tidak berlaku di server dan kode baru tidak lagi bisa ditampilkan, jadi satu-satunya jalan keluar adalah
     * membuat kode baru. Penanda ini DICATAT SEBELUM unggahan dikirim, supaya unggahan yang terkomit tetapi
     * balasannya hilang tidak meninggalkan penanda yang berbohong.
     */
    data class PendingConfirmation(val serverUpdatedAt: String? = null) : RecoveryStatus

    /** Pengguna sudah mengonfirmasi mencatat kode ([at], jam ponsel), atau kunci dipulihkan di perangkat ini. */
    data class Configured(val at: Instant, val serverUpdatedAt: String? = null) : RecoveryStatus
}

interface RecoveryStatusStore {
    fun read(): RecoveryStatus
    fun markPending(serverUpdatedAt: String? = null)
    fun markConfigured(at: Instant, serverUpdatedAt: String? = null)
    fun clear()

    /** Menulis ulang [status] persis seperti semula (mengembalikan penanda yang dicatat sebelum unggahan yang pasti gagal). */
    fun restore(status: RecoveryStatus) = when (status) {
        RecoveryStatus.NotConfigured -> clear()
        is RecoveryStatus.PendingConfirmation -> markPending(status.serverUpdatedAt)
        is RecoveryStatus.Configured -> markConfigured(status.at, status.serverUpdatedAt)
    }
}

/** Hasil [RecoveryKeyService.enableRecovery]. Tidak ada varian selain [Ready] yang membawa kata. */
sealed interface EnableRecoveryResult {
    /**
     * Unggahan ke server SUKSES dan salinan yang dibaca kembali SAMA persis; [display] boleh ditampilkan sekali.
     * Kelas biasa (bukan data class) supaya [toString] tidak mencetak isi [display] walau yang sama lupa
     * menyembunyikan kata.
     */
    class Ready(val display: RecoveryCodeDisplay) : EnableRecoveryResult {
        override fun toString(): String = "Ready(disembunyikan)"
    }

    /** Tidak ada token server (belum masuk atau sesi berakhir): tidak ada panggilan jaringan, tidak ada kode. */
    data object NotSignedIn : EnableRecoveryResult

    /** 403: menyimpan salinan di server butuh langganan Sync aktif. Bukan kegagalan; pesan lembut. */
    data object SyncRequired : EnableRecoveryResult

    /**
     * Perangkat ini belum pernah mengatur kode pemulihan, tetapi server SUDAH punya salinan (dari perangkat lain).
     * Mengunggah kunci perangkat ini akan menimpanya dengan kunci yang berbeda, jadi tidak dilakukan diam-diam:
     * pemanggil menawarkan Pulihkan, atau mengulang dengan `replaceExistingCopy = true` setelah pengguna setuju.
     */
    data object ExistingCopyOnServer : EnableRecoveryResult

    /** Kunci akun di perangkat tidak bisa dibaca atau dibungkus (Keystore bermasalah). Tidak ada yang diunggah. */
    data object KeyUnavailable : EnableRecoveryResult

    /**
     * Penolakan PASTI sebelum ada yang tersimpan (401, 422, atau galat klien 4xx lain), atau pemeriksaan salinan
     * sebelum unggah gagal. Tidak ada kode yang ditampilkan dan penanda kembali persis seperti semula.
     */
    data class Failed(val error: RemoteError) : EnableRecoveryResult

    /**
     * Hasil TIDAK BISA DIPASTIKAN: unggahan sudah dikirim tetapi balasannya hilang (jaringan putus, galat 5xx,
     * balasan rusak), atau unggahan diterima tetapi salinan di server tidak bisa dibaca kembali untuk diperiksa.
     * Salinan di server mungkin sudah berganti, jadi kode lama (bila ada) tidak boleh dianggap berlaku. Kode TIDAK
     * ditampilkan dan penanda menjadi [RecoveryStatus.PendingConfirmation]. Jalan keluarnya: buat kode baru.
     */
    data object Unverified : EnableRecoveryResult

    /**
     * Unggahan diterima tetapi salinan yang dibaca kembali TIDAK SAMA dengan yang diunggah (galat penyimpanan
     * di server, atau diganti perangkat lain di antaranya). Kode tidak ditampilkan; penanda menunggu konfirmasi.
     */
    data object ServerCopyMismatch : EnableRecoveryResult
}

/** Hasil [RecoveryKeyService.recoverKey]. Kunci lokal hanya berubah pada [Recovered]. */
sealed interface RecoverKeyResult {
    data object Recovered : RecoverKeyResult

    /** Ketikan tidak sah (jumlah, kata tak dikenal, checksum): spesifik boleh ditampilkan, belum ada panggilan jaringan. */
    data class InvalidCode(val kind: InvalidRecoveryCodeKind) : RecoverKeyResult

    data object NotSignedIn : RecoverKeyResult

    /** Server belum punya salinan untuk akun ini (404). */
    data object NoCopyOnServer : RecoverKeyResult

    /** Kode tidak cocok dengan salinan di server. Sengaja tidak membedakan "salah" dari "salinan rusak". */
    data object WrongCode : RecoverKeyResult

    /** Salinan memakai versi format yang belum dikenal aplikasi ini (server lebih baru): jangan ditebak. */
    data object UnsupportedVersion : RecoverKeyResult

    /** Kode benar tetapi isi salinan tidak bisa dipasang sebagai kunci (rusak). Kunci lokal tidak disentuh. */
    data object BackupUnreadable : RecoverKeyResult

    /** Kunci pulihan tidak bisa ditulis ke penyimpanan perangkat. Kunci lokal yang ada tetap utuh. */
    data object KeyUnavailable : RecoverKeyResult

    data class Failed(val error: RemoteError) : RecoverKeyResult
}

/** Hasil [RecoveryKeyService.checkRemoteCopy]. */
enum class RemoteCopyCheck { EXISTS, NONE, UNKNOWN }

/**
 * Hasil [RecoveryKeyService.compareWithServer]: apakah salinan di server masih yang dicatat perangkat ini.
 * Hanya [REPLACED] dan [MISSING] berarti kode di kertas mungkin tidak berlaku lagi.
 */
enum class RecoveryCopyState {
    /** Tidak bisa dipastikan (belum diatur, tidak ada `updated_at` tercatat, belum masuk, atau jaringan gagal). Tidak ada kesimpulan. */
    UNKNOWN,

    /** `updated_at` di server sama dengan yang dicatat perangkat ini. */
    IN_SYNC,

    /** Server punya salinan dengan `updated_at` lain: diganti dari perangkat lain. */
    REPLACED,

    /** Server tidak punya salinan lagi (dihapus). */
    MISSING,
}

/**
 * Kode pemulihan 24 kata (Tahap 13, server-sync.md "Model kunci"): membungkus kunci akun dengan kode lalu
 * menyimpan salinannya di server ([enableRecovery]), dan memulihkannya di perangkat baru ([recoverKey]).
 * Seluruh keputusan ada di sini supaya bisa diuji dengan penyimpanan dan server palsu, mengikuti pola
 * `PurchaseController`.
 *
 * ATURAN YANG TIDAK BOLEH DILANGGAR:
 * - Kode 24 kata dan kunci terbaca TIDAK PERNAH disimpan di perangkat (hanya [RecoveryStatusStore], yang tidak
 *   memuat rahasia), TIDAK PERNAH dikirim ke server (hanya hasil [AccountKeyRecoveryCrypto.wrap]), dan tidak
 *   ada di log: semua tipe hasil yang bisa memuat kata menyembunyikannya di `toString`.
 * - [enableRecovery] hanya mengembalikan kata bila unggahan SUKSES dan salinan yang dibaca kembali dari server
 *   SAMA byte demi byte. Hasil lain (gagal pasti, tidak bisa dipastikan, tidak sama) tidak menampilkan apa pun.
 * - Penanda "terkonfigurasi" HANYA lewat [confirm] (pengguna lulus verifikasi) atau [recoverKey] (berhasil).
 * - Kegagalan di tahap mana pun pada [recoverKey] tidak menyentuh kunci lokal; impor adalah langkah terakhir.
 *
 * Yang DIJAMIN kode ini adalah fondasinya saja: kunci akun terbungkus kode dan tersimpan di server. Belum ada
 * data akun yang dienkripsi dengan kunci akun (sinkron data akun belum dibangun). Sejak 2026-10-27 (format
 * `version` 2, [RecoveryBundle]) kunci semua ruang keluarga AKTIF yang diikuti perangkat ini saat
 * [enableRecovery] dipanggil ikut dibungkus dan dipulihkan [recoverKey] — ruang yang diikuti SETELAH kode
 * dibuat (atau yang keanggotaannya diputus lalu digabung lagi) tidak otomatis tercakup oleh kode LAMA, perlu
 * [enableRecovery] baru atau tetap diundang ulang lewat QR. Versi 1 lama (sebelum 2026-10-27, belum pernah
 * dipakai pengguna sungguhan) tidak membawa kunci ruang sama sekali; [recoverKey] tetap bisa membukanya
 * (hanya kunci akun) supaya tidak mogok bila suatu saat ditemukan.
 *
 * Kebijakan menimpa: [recoverKey] MENIMPA kunci akun lokal DAN kunci ruang-ruang yang ikut terbungkus. Saat ini
 * aman karena belum ada data lokal yang dienkripsi dengan kunci akun, dan kunci ruang yang ditimpa hanya berarti
 * ciphertext ruang itu kembali terbaca seperti pemilik aslinya (tidak ada data yang hilang, karena kunci ruang
 * sebelum dipulihkan memang baru dibuat acak sendiri oleh perangkat baru dan belum pernah dipakai mengenkripsi
 * apa pun). Begitu sync entitas akun ada, pulihkan wajib menjaga data lama; itu kewajiban butir sync berikutnya.
 *
 * Token API dibaca dari [tokens] tiap pemanggilan; 401 ditangani `ServerSession` lewat remote yang sudah
 * dibungkus, bukan di sini. Pemanggil di UI yang memutar token dan mengulang sekali bila 401 karena token diputar.
 */
class RecoveryKeyService(
    private val keys: AccountKeyProvider,
    private val remote: RecoveryKeyRemote,
    private val tokens: ApiTokenStore,
    private val statusStore: RecoveryStatusStore,
    /**
     * Kunci ruang keluarga (format `version` 2). Null berarti tidak ada kunci ruang yang ikut
     * dibungkus saat [enableRecovery] atau dipasang balik saat [recoverKey] — dipakai tes lama yang
     * tidak peduli ruang keluarga; produksi (`AccountWorkspace`) selalu mengisi ini.
     */
    private val roomKeys: RoomKeyProvider? = null,
    /**
     * Daftar [RoomId] ruang keluarga yang sedang diikuti perangkat ini (status ACTIVE di
     * `shared_room`), dibaca ulang setiap [enableRecovery] dipanggil supaya daftarnya selalu segar
     * (bukan dicache). Bawaan kosong untuk tes lama yang tidak mengisi [roomKeys].
     */
    private val activeSharedRoomIds: suspend () -> List<RoomId> = { emptyList() },
    private val clock: () -> Instant = { Instant.now() },
    private val random: SecureRandom = SecureRandom(),
) {
    /** Satu unggahan pada satu waktu per akun: dua klik cepat atau layar yang dibuat ulang tidak boleh saling menimpa. */
    private val enableLock = Mutex()

    fun status(): RecoveryStatus = statusStore.read()

    /**
     * Membuat kode baru, membungkus kunci akun dengannya, MENGUNGGAH, membaca kembali salinan di server dan
     * membandingkannya byte demi byte, dan baru bila sama mengembalikan [EnableRecoveryResult.Ready].
     *
     * Urutan penanda (supaya tidak pernah berbohong):
     * 1. Sebelum unggahan dikirim, penanda dicatat [RecoveryStatus.PendingConfirmation] (status sebelumnya disimpan).
     * 2. Gagal PASTI (401, 403, 422, galat klien 4xx): penanda dikembalikan ke status sebelumnya.
     * 3. Hasil ambigu (jaringan putus, 5xx, balasan rusak, atau unggahan diterima tetapi tidak bisa dibaca kembali):
     *    penanda tetap menunggu konfirmasi dan hasilnya [EnableRecoveryResult.Unverified]; kode tidak ditampilkan.
     * 4. Isi server tidak sama dengan yang diunggah: [EnableRecoveryResult.ServerCopyMismatch].
     * 5. Sukses: penanda menyimpan `updated_at` dari tanda terima, sampai [confirm] lulus.
     *
     * Bagian unggah, baca ulang, dan pencatatan berjalan [NonCancellable]: layar yang hilang (Activity dibuat ulang,
     * keluar) di tengah jalan tidak boleh membuang hasil yang sudah terkomit di server tanpa tercatat.
     *
     * Berat (turunan kunci PBKDF2, dua kali): jalankan di luar thread utama.
     */
    suspend fun enableRecovery(replaceExistingCopy: Boolean = false): EnableRecoveryResult =
        enableLock.withLock { enableLocked(replaceExistingCopy) }

    private suspend fun enableLocked(replaceExistingCopy: Boolean): EnableRecoveryResult {
        val token = tokens.read() ?: return EnableRecoveryResult.NotSignedIn

        if (!replaceExistingCopy && statusStore.read() == RecoveryStatus.NotConfigured) {
            when (val existing = remote.get(token)) {
                is RemoteResult.Failure -> return existing.error.toEnableResult()
                is RemoteResult.Success -> if (existing.value is RecoveryKeyLookup.Found) return EnableRecoveryResult.ExistingCopyOnServer
            }
        }

        val code = RecoveryCode.generate(random)
        val wrapped = try {
            val exported = keys.exportAccountKeyForRecovery()
            try {
                val roomEntries = collectActiveRoomKeyEntries()
                try {
                    val payload = RecoveryBundle(exported, roomEntries).encode()
                    AccountKeyRecoveryCrypto.wrap(payload, code, random).also { blob ->
                        // Pagar keselamatan: bungkusan yang akan diunggah HARUS terbuka dengan kode yang akan ditampilkan.
                        // Bila tidak, pengguna mencatat kode yang tak pernah bisa memulihkan apa pun.
                        check(AccountKeyRecoveryCrypto.unwrap(blob, code).contentEquals(payload)) { "Bungkusan kunci tidak terbuka dengan kodenya" }
                    }
                } finally {
                    roomEntries.forEach { it.exportedRoomKey.fill(0) }
                }
            } finally {
                exported.fill(0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return EnableRecoveryResult.KeyUnavailable
        }

        return withContext(NonCancellable) { uploadAndVerify(token, wrapped, code) }
    }

    /**
     * Kunci semua ruang keluarga AKTIF yang diikuti perangkat ini SEKARANG (dibaca ulang tiap panggilan,
     * bukan dicache) — dibungkus bersama kunci akun jadi satu [RecoveryBundle] (format `version` 2).
     * Kosong bila [roomKeys] null (tes lama) atau perangkat tidak mengikuti ruang apa pun.
     */
    private suspend fun collectActiveRoomKeyEntries(): List<RecoveryBundle.RoomKeyEntry> {
        val provider = roomKeys ?: return emptyList()
        return activeSharedRoomIds().map { roomId -> RecoveryBundle.RoomKeyEntry(roomId, provider.exportRoomKey(roomId)) }
    }

    private suspend fun uploadAndVerify(token: String, wrapped: ByteArray, code: RecoveryCode): EnableRecoveryResult {
        val previous = statusStore.read()
        // Niat dicatat SEBELUM unggahan dikirim: unggahan yang terkomit tetapi balasannya hilang tidak boleh
        // meninggalkan penanda lama yang tidak lagi benar.
        statusStore.markPending(null)

        val receipt = when (val put = remote.put(token, wrapped)) {
            is RemoteResult.Failure -> {
                if (put.error.provesNothingWasStored()) {
                    statusStore.restore(previous)
                    return put.error.toEnableResult()
                }
                return EnableRecoveryResult.Unverified
            }

            is RemoteResult.Success -> put.value
        }

        when (val readBack = remote.get(token)) {
            is RemoteResult.Failure -> {
                // Unggahan diterima, tetapi isinya tidak bisa diperiksa: jujur "tidak bisa memastikan".
                statusStore.markPending(receipt.updatedAt)
                return EnableRecoveryResult.Unverified
            }

            is RemoteResult.Success -> {
                val found = readBack.value as? RecoveryKeyLookup.Found
                if (found == null || found.version != RECOVERY_KEY_VERSION || !found.wrappedKey.contentEquals(wrapped)) {
                    return EnableRecoveryResult.ServerCopyMismatch
                }
            }
        }

        statusStore.markPending(receipt.updatedAt)
        return EnableRecoveryResult.Ready(RecoveryCodeDisplay(code))
    }

    /**
     * Pengguna mengetik ulang kata yang diundi di [challenge]. Hanya bila benar semua, penanda menjadi
     * [RecoveryStatus.Configured] (membawa `updated_at` yang dicatat saat unggahan) dan hasilnya true. Salah satu
     * kata keliru: tidak ada yang berubah. Menulis penanda (commit sinkron): panggil dari thread latar.
     */
    fun confirm(challenge: RecoveryChallenge, answers: Map<Int, String>): Boolean {
        if (!challenge.isCorrect(answers)) return false
        val serverUpdatedAt = (statusStore.read() as? RecoveryStatus.PendingConfirmation)?.serverUpdatedAt
        statusStore.markConfigured(clock(), serverUpdatedAt)
        return true
    }

    /** Pulihkan kunci akun di perangkat ini dari [words] (24 kata ketikan pengguna). Lihat kebijakan menimpa di kelas. */
    suspend fun recoverKey(words: List<String>): RecoverKeyResult {
        val code = try {
            RecoveryCode.parse(words.map(RecoveryWordsInput::normalizeWord))
        } catch (e: InvalidRecoveryCode) {
            return RecoverKeyResult.InvalidCode(e.kind)
        }
        val token = tokens.read() ?: return RecoverKeyResult.NotSignedIn

        val found = when (val lookup = remote.get(token)) {
            is RemoteResult.Failure -> return RecoverKeyResult.Failed(lookup.error)
            is RemoteResult.Success -> when (val value = lookup.value) {
                RecoveryKeyLookup.NotFound -> return RecoverKeyResult.NoCopyOnServer
                is RecoveryKeyLookup.Found -> value
            }
        }
        if (found.version != RECOVERY_KEY_VERSION_LEGACY_ACCOUNT_KEY_ONLY && found.version != RECOVERY_KEY_VERSION) {
            return RecoverKeyResult.UnsupportedVersion
        }

        val unwrapped = try {
            AccountKeyRecoveryCrypto.unwrap(found.wrappedKey, code)
        } catch (_: RecoveryCodeInvalid) {
            return RecoverKeyResult.WrongCode
        }

        // Versi 1 lama: plaintext ADALAH bytes kunci akun mentah, tanpa struktur, tanpa kunci ruang.
        // Versi 2 (sejak 2026-10-27): plaintext berstruktur ([RecoveryBundle]) — kunci akun plus kunci ruang.
        val bundle = if (found.version == RECOVERY_KEY_VERSION_LEGACY_ACCOUNT_KEY_ONLY) {
            RecoveryBundle(unwrapped, emptyList())
        } else {
            try {
                RecoveryBundle.decode(unwrapped)
            } catch (_: RecoveryBundleCorrupt) {
                return RecoverKeyResult.BackupUnreadable
            } finally {
                unwrapped.fill(0)
            }
        }

        try {
            keys.importAccountKeyFromRecovery(bundle.accountKey)
        } catch (_: InvalidAccountKeyExport) {
            return RecoverKeyResult.BackupUnreadable
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return RecoverKeyResult.KeyUnavailable
        } finally {
            bundle.accountKey.fill(0)
        }

        // Kunci ruang yang ikut terbungkus (version 2): dipasang MENIMPA, kebijakan sama dengan kunci akun (lihat
        // KDoc kelas). Satu ruang gagal dipasang tidak membatalkan kunci akun yang sudah berhasil atau ruang lain
        // — "tenang, bukan panik": lebih baik pulih sebagian daripada tidak sama sekali, dan versi 1 lama memang
        // tidak pernah memulihkan ruang apa pun juga.
        roomKeys?.let { provider ->
            for (entry in bundle.roomKeys) {
                try {
                    provider.importRoomKey(entry.roomId, entry.exportedRoomKey)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Diam-diam dilewati: ruang itu tetap harus diundang ulang lewat QR, sama seperti version 1.
                } finally {
                    entry.exportedRoomKey.fill(0)
                }
            }
        }

        statusStore.markConfigured(clock(), found.updatedAt)
        return RecoverKeyResult.Recovered
    }

    /**
     * Apakah server sudah punya salinan untuk akun ini. Tidak pernah memblokir; setiap kegagalan (termasuk belum
     * masuk) hanyalah [RemoteCopyCheck.UNKNOWN]. Saat ini TIDAK dipanggil produksi (tawaran aktif "Pulihkan" di
     * perangkat baru disembunyikan sampai ada data yang bergantung pada kunci akun).
     */
    suspend fun checkRemoteCopy(): RemoteCopyCheck {
        val token = tokens.read() ?: return RemoteCopyCheck.UNKNOWN
        return when (val lookup = remote.get(token)) {
            is RemoteResult.Failure -> RemoteCopyCheck.UNKNOWN
            is RemoteResult.Success -> if (lookup.value is RecoveryKeyLookup.Found) RemoteCopyCheck.EXISTS else RemoteCopyCheck.NONE
        }
    }

    /**
     * Membandingkan `updated_at` yang dicatat penanda [RecoveryStatus.Configured] dengan salinan di server. Dipanggil
     * saat layar Kode pemulihan dibuka. Hanya penanda yang sudah terkonfigurasi dan punya `updated_at` tercatat yang
     * bisa dibandingkan; selain itu, dan setiap kegagalan jaringan, hasilnya [RecoveryCopyState.UNKNOWN] (tidak ada
     * kesimpulan). Tidak mengubah penanda apa pun.
     */
    suspend fun compareWithServer(): RecoveryCopyState {
        val recorded = (statusStore.read() as? RecoveryStatus.Configured)?.serverUpdatedAt ?: return RecoveryCopyState.UNKNOWN
        val token = tokens.read() ?: return RecoveryCopyState.UNKNOWN
        return when (val lookup = remote.get(token)) {
            is RemoteResult.Failure -> RecoveryCopyState.UNKNOWN
            is RemoteResult.Success -> when (val value = lookup.value) {
                RecoveryKeyLookup.NotFound -> RecoveryCopyState.MISSING
                is RecoveryKeyLookup.Found -> if (value.updatedAt == recorded) RecoveryCopyState.IN_SYNC else RecoveryCopyState.REPLACED
            }
        }
    }

    /**
     * Dipanggil setelah akun server dihapus: salinan di server ikut hilang, jadi penanda lokal tidak boleh tetap
     * "terkonfigurasi". Menulis penanda (commit sinkron): panggil dari thread latar dan jangan sampai terlewat.
     */
    fun forgetStatus() = statusStore.clear()

    private fun RemoteError.toEnableResult(): EnableRecoveryResult =
        if (this == RemoteError.SyncRequired) EnableRecoveryResult.SyncRequired else EnableRecoveryResult.Failed(this)

    /**
     * Jawaban yang MEMBUKTIKAN server tidak menyimpan apa pun: 401, 403, 422, dan galat klien 4xx lain ditolak sebelum
     * menyentuh data. Jaringan putus, 5xx, dan balasan 200 yang rusak TIDAK membuktikan apa-apa: server bisa sudah
     * menyimpannya.
     */
    private fun RemoteError.provesNothingWasStored(): Boolean = when (this) {
        RemoteError.Unauthorized, RemoteError.SyncRequired, is RemoteError.Rejected -> true
        is RemoteError.Unexpected -> httpCode in 400..499
        RemoteError.Unreachable -> false
    }
}
