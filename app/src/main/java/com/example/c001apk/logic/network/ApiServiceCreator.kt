package com.example.c001apk.logic.network

import com.example.c001apk.BuildConfig
import com.example.c001apk.util.AddCookiesInterceptor
import com.example.c001apk.util.SslErrorInterceptor
import com.example.c001apk.util.SslVerify
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

enum class ServiceType {
    API_SERVICE,
    API2_SERVICE
}

object ApiServiceCreator {

    private const val API_BASE_URL = "https://api.coolapk.com"
    private const val API2_BASE_URL = "https://api2.coolapk.com"

    private fun getClient(serviceType: ServiceType, followRedirects: Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AddCookiesInterceptor)
            .addInterceptor(SslErrorInterceptor)
            .addInterceptor(
                HttpLoggingInterceptor().setLevel
                    (
                    if (BuildConfig.DEBUG || BuildConfig.HTTP_LOG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
                )
            )
            .followRedirects(followRedirects)
            .let { SslVerify.apply(it) }
            .build()


    private fun getRetrofit(serviceType: ServiceType, followRedirects: Boolean): Retrofit =
        Retrofit.Builder()
            .baseUrl(
                when (serviceType) {
                    ServiceType.API_SERVICE -> API_BASE_URL
                    ServiceType.API2_SERVICE -> API2_BASE_URL
                }
            )
            .addConverterFactory(GsonConverterFactory.create())
            .client(getClient(serviceType, followRedirects))
            .build()

    fun <T> create(serviceType: ServiceType, followRedirects: Boolean, serviceClass: Class<T>): T =
        getRetrofit(serviceType, followRedirects).create(serviceClass)

    inline fun <reified T> create(serviceType: ServiceType, followRedirects: Boolean = true): T =
        create(serviceType, followRedirects, T::class.java)

}
