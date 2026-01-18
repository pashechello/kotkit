package com.autoposter.di

import com.autoposter.BuildConfig
import com.autoposter.data.local.preferences.EncryptedPreferences
import com.autoposter.data.remote.api.ApiService
import com.autoposter.data.remote.api.AuthInterceptor
import com.autoposter.data.remote.api.RetryInterceptor
import com.autoposter.data.remote.api.TokenAuthenticator
import com.autoposter.security.SSLPinning
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            .setLenient()
            .create()
    }

    @Provides
    @Singleton
    fun provideAuthInterceptor(
        encryptedPreferences: EncryptedPreferences
    ): AuthInterceptor {
        return AuthInterceptor(encryptedPreferences)
    }

    @Provides
    @Singleton
    fun provideTokenAuthenticator(
        encryptedPreferences: EncryptedPreferences,
        gson: Gson
    ): TokenAuthenticator {
        return TokenAuthenticator(encryptedPreferences, gson)
    }

    @Provides
    @Singleton
    fun provideRetryInterceptor(): RetryInterceptor {
        return RetryInterceptor(
            maxRetries = 3,
            initialDelayMs = 1000,
            maxDelayMs = 10000
        )
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
        retryInterceptor: RetryInterceptor,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(retryInterceptor)
            .addInterceptor(loggingInterceptor)
            .authenticator(tokenAuthenticator) // Auto-refresh tokens on 401
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // Longer for AI analysis
            .writeTimeout(60, TimeUnit.SECONDS) // Longer for screenshot upload

        // Add SSL pinning in release builds
        if (!BuildConfig.DEBUG) {
            builder.certificatePinner(SSLPinning.getCertificatePinner())
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        gson: Gson
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }
}
