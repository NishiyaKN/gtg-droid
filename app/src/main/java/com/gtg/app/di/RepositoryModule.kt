package com.gtg.app.di

import com.gtg.app.data.repository.ActivityWindowRepositoryImpl
import com.gtg.app.data.repository.CalendarEventRepositoryImpl
import com.gtg.app.data.repository.ExerciseLogRepositoryImpl
import com.gtg.app.data.repository.ExerciseRepositoryImpl
import com.gtg.app.data.repository.InactivityBlockRepositoryImpl
import com.gtg.app.domain.repository.ActivityWindowRepository
import com.gtg.app.domain.repository.CalendarEventRepository
import com.gtg.app.domain.repository.ExerciseLogRepository
import com.gtg.app.domain.repository.ExerciseRepository
import com.gtg.app.domain.repository.InactivityBlockRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindExerciseRepository(impl: ExerciseRepositoryImpl): ExerciseRepository

    @Binds @Singleton
    abstract fun bindExerciseLogRepository(impl: ExerciseLogRepositoryImpl): ExerciseLogRepository

    @Binds @Singleton
    abstract fun bindActivityWindowRepository(impl: ActivityWindowRepositoryImpl): ActivityWindowRepository

    @Binds @Singleton
    abstract fun bindInactivityBlockRepository(impl: InactivityBlockRepositoryImpl): InactivityBlockRepository

    @Binds @Singleton
    abstract fun bindCalendarEventRepository(impl: CalendarEventRepositoryImpl): CalendarEventRepository
}
