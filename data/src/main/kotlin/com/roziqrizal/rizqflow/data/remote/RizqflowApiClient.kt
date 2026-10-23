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
    fun create(baseUrl: String): RizqflowApiService {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .build()
            .create(RizqflowApiService::class.java)
    }
}
