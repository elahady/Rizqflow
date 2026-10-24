package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.entitlement.Plan
import com.roziqrizal.rizqflow.domain.sync.ClaimStatus
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaim
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaimResponse
import com.roziqrizal.rizqflow.domain.sync.PurchaseClaimResult
import com.roziqrizal.rizqflow.domain.sync.PurchaseRemote
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import kotlinx.serialization.SerializationException

/**
 * Implementasi [PurchaseRemote] sungguhan lewat [RizqflowApiService] (Tahap 9). Jawaban 503 (server
 * belum dikonfigurasi memverifikasi Play) dan galat lain jadi [RemoteResult.Failure] lewat
 * [mapHttpError] (503 menjadi [RemoteError.Unexpected]); pemanggil memperlakukan semuanya sama, yaitu
 * mencoba lagi nanti tanpa menyentuh paket lokal.
 */
class RetrofitPurchaseRemote(private val service: RizqflowApiService) : PurchaseRemote {
    override suspend fun claim(apiToken: String, claims: List<PurchaseClaim>): RemoteResult<PurchaseClaimResponse> =
        safeCall {
            val request = jsonBody(
                PurchaseClaimRequestDto.serializer(),
                PurchaseClaimRequestDto(claims.map { PurchaseClaimItemDto(it.productId, it.purchaseToken) }),
            )
            val response = service.claimPurchases(bearer(apiToken), request)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                // Badan 200 yang bukan JSON sesuai kontrak (mis. halaman galat dari proxy) bukan alasan
                // untuk crash: dilaporkan sebagai kegagalan biasa yang dicoba lagi nanti.
                try {
                    RemoteResult.Success(body.decodeJson(PurchaseClaimResponseDto.serializer()).toDomain())
                } catch (_: SerializationException) {
                    RemoteResult.Failure(RemoteError.Unexpected(response.code()))
                }
            } else {
                response.toFailure()
            }
        }
}

private fun PurchaseClaimResponseDto.toDomain() = PurchaseClaimResponse(
    plans = plans.mapNotNull(::planOfWire).toSet(),
    results = results.map { PurchaseClaimResult(it.productId, claimStatusOfWire(it.status)) },
)

/** Nama paket di kontrak server; nama yang belum dikenal klien ini dibuang, bukan membuat jawaban gagal. */
private fun planOfWire(wire: String): Plan? = when (wire) {
    "pro" -> Plan.PRO
    "sync" -> Plan.SYNC
    else -> null
}

/** Status per item di kontrak server; nilai yang belum dikenal menjadi [ClaimStatus.UNKNOWN]. */
private fun claimStatusOfWire(wire: String): ClaimStatus = when (wire) {
    "active" -> ClaimStatus.ACTIVE
    "pending" -> ClaimStatus.PENDING
    "inactive" -> ClaimStatus.INACTIVE
    "claimed_by_other" -> ClaimStatus.CLAIMED_BY_OTHER
    "unknown_product" -> ClaimStatus.UNKNOWN_PRODUCT
    "unverifiable" -> ClaimStatus.UNVERIFIABLE
    else -> ClaimStatus.UNKNOWN
}
