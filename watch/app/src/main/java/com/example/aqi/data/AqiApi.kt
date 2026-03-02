package com.example.aqi.data

import com.example.aqi.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.util.concurrent.TimeUnit

interface AqiApi {
    @GET("api/aqi")
    suspend fun getAllData(): MasterDataPayload

    companion object {
        val instance: AqiApi by lazy {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
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
