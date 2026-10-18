package com.example.c001apk.di

import com.example.c001apk.BuildConfig
import com.example.c001apk.logic.network.ApiService
import com.example.c001apk.util.AddCookiesInterceptor
import com.example.c001apk.util.HttpLog
import com.example.c001apk.util.RiskControlInterceptor
import com.example.c001apk.util.SslErrorInterceptor
import com.example.c001apk.util.SslVerify
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Api1Service

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Api1ServiceNoRedirect

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Api2Service

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val API_BASE_URL = "https://api.coolapk.com"
    private const val API2_BASE_URL = "https://api2.coolapk.com"

    @Api1Service
    @Singleton
    @Provides
    fun provideApi1Service(@Api1Service retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }

    @Api1ServiceNoRedirect
    @Singleton
    @Provides
    fun provideApi1ServiceNo(@Api1ServiceNoRedirect retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }

    @Api2Service
    @Singleton
    @Provides
    fun provideApi2Service(@Api2Service retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }

    @Api1Service
    @Singleton
    @Provides
    fun provideApi1ServiceRetrofit(@Api1Service okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()
    }

    @Api1ServiceNoRedirect
    @Singleton
    @Provides
    fun provideApi1ServiceNoRetrofit(@Api1ServiceNoRedirect okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()
    }

    @Api2Service
    @Singleton
    @Provides
    fun provideApi2ServiceRetrofit(@Api1Service okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(API2_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()
    }

    @Api1Service
    @Singleton
    @Provides
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(AddCookiesInterceptor)
            // HTTP 请求日志（设置 → 高级 开关 ✓ 关着时零开销 ✓）
            // 放在最前：能记到**完整**的请求头与响应体 ✓
            .addInterceptor(HttpLog.Interceptor())
            .addInterceptor(SslErrorInterceptor)
            .addInterceptor(RiskControlInterceptor)
            .addInterceptor(
                HttpLoggingInterceptor().setLevel(
                    if (BuildConfig.DEBUG || BuildConfig.HTTP_LOG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
                )
            )
            .followRedirects(true)
            .let { SslVerify.apply(it) }
            .build()
    }

    @Api1ServiceNoRedirect
    @Singleton
    @Provides
    fun provideNoOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(AddCookiesInterceptor)
            // HTTP 请求日志（设置 → 高级 开关 ✓ 关着时零开销 ✓）
            // 放在最前：能记到**完整**的请求头与响应体 ✓
            .addInterceptor(HttpLog.Interceptor())
            .addInterceptor(SslErrorInterceptor)
            .addInterceptor(RiskControlInterceptor)
            .addInterceptor(
                HttpLoggingInterceptor().setLevel(
                    if (BuildConfig.DEBUG || BuildConfig.HTTP_LOG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
                )
            )
            .followRedirects(false)
            .let { SslVerify.apply(it) }
            .build()
    }

}
