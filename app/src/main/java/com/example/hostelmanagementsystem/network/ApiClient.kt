package com.example.hostelmanagementsystem.network

import android.content.Context
import com.example.hostelmanagementsystem.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
object ApiClient {

    @Volatile
    private var applicationContext: Context? = null


    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    private val authInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val token = applicationContext?.let { SessionManager(it).getToken() }.orEmpty()
        val requestBuilder = originalRequest.newBuilder()

        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        chain.proceed(requestBuilder.build())
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .build()
    }

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
