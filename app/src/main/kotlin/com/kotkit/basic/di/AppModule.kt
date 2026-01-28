package com.kotkit.basic.di

import android.content.Context
import androidx.work.WorkManager
import com.kotkit.basic.data.local.keystore.KeystoreManager
import com.kotkit.basic.data.local.preferences.EncryptedPreferences
import com.kotkit.basic.executor.screenshot.ThumbnailGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideKeystoreManager(
        @ApplicationContext context: Context
    ): KeystoreManager {
        return KeystoreManager(context)
    }

    @Provides
    @Singleton
    fun provideEncryptedPreferences(
        @ApplicationContext context: Context
    ): EncryptedPreferences {
        return EncryptedPreferences(context)
    }

    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context
    ): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideThumbnailGenerator(
        @ApplicationContext context: Context
    ): ThumbnailGenerator {
        return ThumbnailGenerator(context)
    }
}
