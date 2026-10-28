package com.procutstudio.editor.di

import android.content.Context
import androidx.room.Room
import com.procutstudio.editor.core.di.DefaultDispatcher
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.core.di.MainDispatcher
import com.procutstudio.editor.data.local.db.ProCutDatabase
import com.procutstudio.editor.data.local.db.ProjectDao
import com.procutstudio.editor.data.repository.ProjectRepository
import com.procutstudio.editor.data.repository.ProjectRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @IoDispatcher
    fun providesIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    fun providesMainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providesDatabase(@ApplicationContext context: Context): ProCutDatabase =
        Room.databaseBuilder(context, ProCutDatabase::class.java, ProCutDatabase.NAME)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides
    fun providesProjectDao(database: ProCutDatabase): ProjectDao = database.projectDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindsProjectRepository(impl: ProjectRepositoryImpl): ProjectRepository
}
