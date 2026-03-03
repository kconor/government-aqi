package com.example.aqi.data

import com.example.aqi.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface AqiApi {
    @GET("api/aqi")
    suspend fun getAllData(): MasterDataPayload

    companion object {
        private fun hmacHex(secret: String, message: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        val instance: AqiApi by lazy {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val minute = System.currentTimeMillis() / 60000
                    val token = hmacHex(BuildConfig.API_SECRET, minute.toString())
                    chain.proceed(
                        chain.request().newBuilder()
                            .addHeader("X-Auth", token)
                            .build()
                    )
                }
                .build()

            Retrofit.Builder()
                .baseUrl(BuildConfig.AQI_BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(AqiApi::class.java)
        }
    }
}
