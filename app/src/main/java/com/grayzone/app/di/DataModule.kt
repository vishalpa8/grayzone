package com.grayzone.app.di

import android.content.Context
import android.content.SharedPreferences
import com.grayzone.app.PrefsKeys
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
    }
}
