package com.gtg.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.gtg.app.data.local.entity.ExerciseLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseLogDao {

    @Insert
    suspend fun insert(log: ExerciseLogEntity): Long

    @Query("SELECT * FROM exercise_logs WHERE exercise_id = :exerciseId ORDER BY timestamp DESC")
    fun observeByExercise(exerciseId: Long): Flow<List<ExerciseLogEntity>>

    @Query("SELECT * FROM exercise_logs ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ExerciseLogEntity>>

    /**
     * Sinal de mudança barato para observers que só precisam saber QUE a
     * tabela mudou (Home/Statistics recarregam via queries agregadas próprias).
     * observeAll() deserializava a tabela inteira a cada insert só para
     * descartar o payload.
     */
    @Query("SELECT COUNT(*) FROM exercise_logs")
    fun observeCount(): Flow<Int>

    /** Logs de um dia específico (timestamps em epoch millis UTC). */
    @Query("SELECT * FROM exercise_logs WHERE timestamp >= :dayStartMillis AND timestamp < :dayEndMillis ORDER BY timestamp ASC")
    suspend fun getLogsForDay(dayStartMillis: Long, dayEndMillis: Long): List<ExerciseLogEntity>

    /** Logs em um intervalo arbitrário (timestamps em epoch millis UTC). */
    @Query("SELECT * FROM exercise_logs WHERE timestamp >= :startMillis AND timestamp < :endMillis ORDER BY timestamp ASC")
    suspend fun getLogsBetween(startMillis: Long, endMillis: Long): List<ExerciseLogEntity>

    /** Total de reps entre duas datas. */
    @Query("SELECT COALESCE(SUM(reps_completed), 0) FROM exercise_logs WHERE timestamp >= :startMillis AND timestamp < :endMillis")
    suspend fun totalRepsBetween(startMillis: Long, endMillis: Long): Int

    /** Contagem de séries entre duas datas. */
    @Query("SELECT COUNT(*) FROM exercise_logs WHERE timestamp >= :startMillis AND timestamp < :endMillis")
    suspend fun totalSetsBetween(startMillis: Long, endMillis: Long): Int

    /** Último log registrado (para retomar estado após reboot). */
    @Query("SELECT * FROM exercise_logs ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastLog(): ExerciseLogEntity?
}
