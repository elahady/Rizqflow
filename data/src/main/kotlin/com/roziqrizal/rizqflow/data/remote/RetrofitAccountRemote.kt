package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.domain.sync.AccountRemote
import com.roziqrizal.rizqflow.domain.sync.ApiAccount
import com.roziqrizal.rizqflow.domain.sync.RemoteResult

/** Implementasi [AccountRemote] sungguhan lewat [RizqflowApiService] (Tahap 9). */
class RetrofitAccountRemote(private val service: RizqflowApiService) : AccountRemote {
    override suspend fun loginWithGoogle(idToken: String, deviceId: String): RemoteResult<ApiAccount> =
        safeCall {
            val request = jsonBody(LoginRequestDto.serializer(), LoginRequestDto(credential = idToken, deviceId = deviceId))
            val response = service.loginWithGoogle(request)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                val decoded = body.decodeJson(LoginResponseDto.serializer())
                RemoteResult.Success(
                    ApiAccount(
                        apiToken = decoded.token,
                        userId = decoded.user.id,
                        name = decoded.user.name,
                        email = decoded.user.email,
                    ),
                )
            } else {
                response.toFailure()
            }
        }

    override suspend fun deleteAccount(apiToken: String): RemoteResult<Unit> =
        safeCall {
            val response = service.deleteAccount(bearer(apiToken))
            if (response.isSuccessful) RemoteResult.Success(Unit) else response.toFailure()
        }
}
