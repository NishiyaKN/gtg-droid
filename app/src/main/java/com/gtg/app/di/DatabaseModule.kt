package com.gtg.app.di

import android.content.Context
import androidx.room.Room
import com.gtg.app.data.local.GtgDatabase
import com.gtg.app.data.local.dao.ActivityWindowDao
import com.gtg.app.data.local.dao.ExerciseDao
import com.gtg.app.data.local.dao.ExerciseLogDao
import com.gtg.app.data.local.dao.InactivityBlockDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    // SEM fallbackToDestructiveMigration: com allowBackup=false, um wipe de
    // schema destruiria todo o histórico do usuário sem recuperação. Toda
    // mudança de schema DEVE vir com AutoMigration (ou Migration manual) no
    // GtgDatabase — uma migração faltante agora falha rápido em
    // IllegalStateException no primeiro open, visível em debug, em vez de
    // apagar dados silenciosamente em produção.
    fun provideDatabase(@ApplicationContext context: Context): GtgDatabase =
        Room.databaseBuilder(context, GtgDatabase::class.java, "gtg_database")
            .build()

    @Provides fun provideExerciseDao(db: GtgDatabase): ExerciseDao = db.exerciseDao()
    @Provides fun provideExerciseLogDao(db: GtgDatabase): ExerciseLogDao = db.exerciseLogDao()
    @Provides fun provideActivityWindowDao(db: GtgDatabase): ActivityWindowDao = db.activityWindowDao()
    @Provides fun provideInactivityBlockDao(db: GtgDatabase): InactivityBlockDao = db.inactivityBlockDao()
}
