package com.roziqrizal.rizqflow.billing

import com.roziqrizal.rizqflow.domain.sync.ApiTokenStore
import com.roziqrizal.rizqflow.domain.sync.ClaimStatus
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaim
import com.roziqrizal.rizqflow.domain.sync.PurchaseRemote
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Satu permintaan klaim memuat paling banyak ini (batas kontrak server `POST /api/purchases/claim`). */
internal const val MAX_CLAIMS_PER_REQUEST = 10

/**
 * Hasil satu putaran [PurchaseClaimCoordinator.claim]. Satu-satunya hal yang perlu diketahui pemanggil
 * adalah apakah pesan lembut "pembelian dipakai akun lain" perlu ditampilkan sekarang.
 */
data class ClaimReport(val showClaimedByOtherNotice: Boolean) {
    companion object {
        val None = ClaimReport(showClaimedByOtherNotice = false)
    }
}

/**
 * Ingatan seumur proses tentang token pembelian yang sudah dilaporkan ke server dan dijawab tegas,
 * supaya satu token tidak dikirim berulang tiap sync. Kuncinya pasangan (token API, token pembelian):
 * ganti akun Google di proses yang sama tidak boleh dianggap "sudah dilaporkan" untuk akun yang baru.
 * Sengaja tidak disimpan ke disk: aplikasi dibuka ulang berarti lapor ulang sekali, dan server idempoten.
 * Satu instans dibagi seluruh proses (lihat `RizqflowApplication`), bukan per ruang kerja, supaya pesan
 * "dipakai akun lain" benar-benar muncul sekali per token per proses walau ruang kerja dibuka ulang.
 */
class ClaimedTokenMemory {
    private data class Key(val apiToken: String, val purchaseToken: String)

    private val reported = mutableSetOf<Key>()

    @Synchronized
    fun isReported(apiToken: String, purchaseToken: String): Boolean = Key(apiToken, purchaseToken) in reported

    /** True bila token ini baru pertama kali diingat (atomik; dipakai untuk memastikan pesan hanya sekali). */
    @Synchronized
    fun remember(apiToken: String, purchaseToken: String): Boolean = reported.add(Key(apiToken, purchaseToken))
}

/**
 * Melaporkan token pembelian Pro dan langganan Sync ke server (Tahap 9, roadmap.md "Catat dan verifikasi
 * pembelian") supaya web dan Sync mengenali paket yang sama. Semua keputusan "kapan, apa, dan apa
 * kelanjutannya" ada di sini dan bisa diuji tanpa Android, mengikuti pola [PurchaseController].
 *
 * Aturan yang tidak boleh dilanggar:
 * - Ini hanya LAPORAN. Kelas ini tidak memegang [PurchaseStore], jadi secara struktur tidak mungkin
 *   membuka, mencabut, atau menahan paket lokal; hak akses tetap sepenuhnya dari Google Play lewat
 *   [PurchaseController]. Kegagalan, penolakan, atau status apa pun dari server tidak mengubah apa pun
 *   di ponsel dan tidak pernah menghalangi beli atau pulihkan (pemanggil menjalankan [claim] terpisah).
 * - Hanya dikirim bila [enabled] (bukan akun lokal dan bukan demo, lihat [purchaseClaimsAllowed]) dan ada
 *   token API server milik akun yang sedang masuk (sudah masuk dengan Google; token disimpan per akun,
 *   jadi token akun lain tidak pernah terbaca di sini). Selain itu tidak ada panggilan jaringan sama sekali
 *   dan toko pun tidak ditanyai.
 * - Hanya pembelian [PurchaseState.PURCHASED]; yang tertunda tidak dikirim. `productId` dari
 *   [BillingProduct], bukan tebakan.
 * - Galat diam: [claim] tidak pernah melempar (selain pembatalan coroutine) dan hanya melaporkan satu hal
 *   ke pemanggil, yaitu pesan lembut [ClaimStatus.CLAIMED_BY_OTHER] yang muncul sekali per token per proses.
 *
 * Apa yang diingat ([ClaimedTokenMemory]) dan tidak dikirim lagi: ACTIVE dan CLAIMED_BY_OTHER saja, dua
 * jawaban yang tegas dan tidak berubah dengan sendirinya. Yang dicoba lagi di sync berikutnya: INACTIVE
 * (server juga memakainya untuk token yang belum dikenal Play, misalnya sesaat setelah beli atau saat
 * konfigurasi server masih salah, jadi bisa berubah), UNVERIFIABLE (Play belum terjangkau dari server),
 * PENDING, UNKNOWN_PRODUCT, status yang belum dikenal, jawaban yang tidak sejajar, dan semua kegagalan
 * jaringan. Satu permintaan tiap pemicu (buka aplikasi, selesai masuk, beli, pulihkan, sync eksternal),
 * bukan putaran: tidak ada risiko membanjiri server.
 *
 * Jawaban `plans` dari server sengaja diabaikan di sini: paket server dipakai server sendiri (gerbang
 * fitur Sync di sana), bukan untuk menentukan paket di ponsel.
 */
class PurchaseClaimCoordinator(
    private val gateway: BillingGateway,
    private val remote: PurchaseRemote,
    private val apiTokenStore: ApiTokenStore,
    private val memory: ClaimedTokenMemory = ClaimedTokenMemory(),
    /** Dibaca tiap [claim]: akun lokal dan demo mengembalikan false (lihat [purchaseClaimsAllowed]). */
    private val enabled: () -> Boolean = { true },
    /**
     * Dijalankan tiap [claim] SEBELUM token dibaca (Tahap 12): memutar token yang hampir kedaluwarsa
     * (`TokenRefresher.refreshIfDue`) supaya klaim memakai token yang masih berlaku. Keputusan dan
     * serialisasinya ada di pemutar, bukan di sini; 401 dari klaim ditangani `ServerSession` lewat
     * remote yang sudah dibungkus, bukan di kelas ini.
     */
    private val refreshToken: suspend () -> Unit = {},
) {
    // Beberapa pemicu bisa jatuh bersamaan (buka aplikasi, selesai masuk, selesai beli): satu putaran
    // pada satu waktu, sehingga putaran kedua melihat ingatan putaran pertama dan tidak mengirim ganda.
    private val mutex = Mutex()

    suspend fun claim(): ClaimReport {
        if (!enabled()) return ClaimReport.None
        return try {
            refreshToken()
            // Dibaca SETELAH putar: token lama mungkin baru saja diganti dan sudah dicabut server.
            val apiToken = apiTokenStore.read() ?: return ClaimReport.None
            mutex.withLock { claimLocked(apiToken) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Laporan ke server tidak boleh jadi sebab aplikasi crash; sync berikutnya mencoba lagi.
            ClaimReport.None
        }
    }

    private suspend fun claimLocked(apiToken: String): ClaimReport {
        val owned = gateway.queryOwned()
        val toSend = owned.purchases
            .filter { it.state == PurchaseState.PURCHASED }
            .mapNotNull { purchase ->
                BillingProduct.of(purchase.plan)?.let { PurchaseClaim(it.productId, purchase.token) }
            }
            .distinct()
            .filterNot { memory.isReported(apiToken, it.purchaseToken) }
        var showNotice = false
        for (batch in toSend.chunked(MAX_CLAIMS_PER_REQUEST)) {
            val response = when (val result = remote.claim(apiToken, batch)) {
                is RemoteResult.Success -> result.value
                // Jaringan mati, 401, 422, 503 (server belum dikonfigurasi): diam, coba lagi di sync berikutnya.
                is RemoteResult.Failure -> break
            }
            // results harus sejajar dengan masukan. Bila tidak (server lebih baru atau rusak), tidak ada
            // yang bisa dipercaya: jangan mengingat apa pun dan coba lagi nanti.
            val aligned = response.results.size == batch.size &&
                response.results.indices.all { response.results[it].productId == batch[it].productId }
            if (!aligned) continue
            for ((claim, result) in batch.zip(response.results)) {
                when (result.status) {
                    ClaimStatus.ACTIVE -> {
                        memory.remember(apiToken, claim.purchaseToken)
                    }

                    ClaimStatus.CLAIMED_BY_OTHER -> {
                        // remember() atomik: hanya pemanggil yang pertama mengingat token ini yang memunculkan pesan.
                        if (memory.remember(apiToken, claim.purchaseToken)) showNotice = true
                    }

                    // INACTIVE tidak diingat: bukan jawaban final (token yang belum dikenal Play pun dijawab begini).
                    ClaimStatus.INACTIVE,
                    ClaimStatus.PENDING,
                    ClaimStatus.UNKNOWN_PRODUCT,
                    ClaimStatus.UNVERIFIABLE,
                    ClaimStatus.UNKNOWN,
                    -> Unit
                }
            }
        }
        return ClaimReport(showNotice)
    }
}

/**
 * Hanya akun yang punya akun server yang boleh melaporkan pembelian: akun lokal (PASSWORD) dan mode demo
 * tidak pernah mengirim token pembelian ke server, apa pun yang tersimpan di perangkat.
 */
fun purchaseClaimsAllowed(demo: Boolean, localAccount: Boolean): Boolean = !demo && !localAccount

/** Pembelian lewat tombol Beli yang sudah membuka paket: token barunya perlu dilaporkan ke server. */
fun BuyOutcome.opensPlan(): Boolean = this == BuyOutcome.GRANTED || this == BuyOutcome.ALREADY_OWNED

/** Pulihkan yang membuka minimal satu paket: tokennya perlu dilaporkan ke server. */
fun RestoreReport.opensPlan(): Boolean = granted.isNotEmpty()
