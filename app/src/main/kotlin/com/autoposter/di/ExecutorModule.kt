package com.autoposter.di

import android.content.Context
import com.autoposter.data.local.keystore.KeystoreManager
import com.autoposter.executor.accessibility.portal.UITreeParser
import com.autoposter.executor.screenshot.ImageCompressor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ExecutorModule {

    @Provides
    @Singleton
    fun provideUITreeParser(): UITreeParser {
        return UITreeParser()
    }

    @Provides
    @Singleton
    fun provideImageCompressor(): ImageCompressor {
        return ImageCompressor()
    }
}
