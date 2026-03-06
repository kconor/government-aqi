package com.example.aqi.data

import com.example.aqi.BuildConfig
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AqiApi private constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) {
    fun getAllData(): MasterDataPayload = getJson("api/aqi", MasterDataPayload::class.java)

    fun getForecastData(): ForecastPayload = getJson("api/forecast", ForecastPayload::class.java)

    private fun <T : Any> getJson(path: String, clazz: Class<T>): T {
        val request = Request.Builder()
            .url(BuildConfig.AQI_BASE_URL + path)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code()} from $path: ${response.message()}")
            }

            val body = response.body()?.string()
                ?: throw IllegalStateException("Empty body from $path")

            return gson.fromJson(body, clazz)
                ?: throw IllegalStateException("Failed to parse response from $path")
        }
    }

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

            AqiApi(client, Gson())
        }
    }
}
