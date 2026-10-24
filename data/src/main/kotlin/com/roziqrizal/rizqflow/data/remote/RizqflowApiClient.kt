package com.roziqrizal.rizqflow.data.remote

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/**
 * Satu Retrofit untuk seluruh API Rizqflow (akun server dan sync, Tahap 9). [baseUrl] datang dari
 * pemanggil (`BuildConfig.API_BASE_URL` di `:app`), tidak ditanam di sini — supaya pindah VPS tidak
 * butuh mengubah `:data` (lihat CLAUDE.md "alamat API bisa dikonfigurasi"). Tanpa converter factory
 * karena [RizqflowApiService] cuma memakai RequestBody/ResponseBody mentah, didukung Retrofit bawaan.
 */
object RizqflowApiClient {
    /**
     * Batas waktu SELURUH satu panggilan (sambung + kirim + tunggu + terima). `readTimeout` hanya menghitung jeda
     * antar-byte, jadi server yang menetes jawaban sedikit demi sedikit atau jaringan yang menggantung bisa
     * menahan satu panggilan jauh melewati 15 detik; batas ini yang menjamin pemanggil (putar token, klaim
     * pembelian) tidak menunggu tanpa ujung. Lewat batas menjadi `IOException` ([RemoteError.Unreachable]).
     */
    internal const val CALL_TIMEOUT_SECONDS = 30L

    internal fun httpClient(callTimeoutMillis: Long = TimeUnit.SECONDS.toMillis(CALL_TIMEOUT_SECONDS)): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()

    fun create(baseUrl: String): RizqflowApiService = create(baseUrl, httpClient())

    internal fun create(baseUrl: String, client: OkHttpClient): RizqflowApiService =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .build()
            .create(RizqflowApiService::class.java)
}
