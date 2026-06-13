package com.gtg.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gtg.app.data.local.entity.InactivityBlockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InactivityBlockDao {

    @Query("SELECT * FROM inactivity_blocks ORDER BY start_hour ASC, start_minute ASC")
    fun observeAll(): Flow<List<InactivityBlockEntity>>

    @Query("SELECT * FROM inactivity_blocks")
    suspend fun getAll(): List<InactivityBlockEntity>

    /**
     * Candidatos a "ativo em :dateIso" (yyyy-MM-dd): blocos recorrentes
     * (sempre candidatos — o predicado fino de DAILY/WEEKLY/MONTHLY continua
     * em [com.gtg.app.domain.model.InactivityBlock.isActiveOn]) + blocos NONE
     * da data exata. Poda no SQL os NONE de datas passadas/futuras, que são a
     * única categoria que cresce sem limite com o histórico do usuário — sem
     * isso, cada chamada do scheduler (7x por rollover, dentro do budget
     * goAsync do AlarmReceiver) deserializava a tabela inteira.
     */
    @Query("SELECT * FROM inactivity_blocks WHERE recurrence != 'NONE' OR specific_date = :dateIso")
    suspend fun getCandidatesForDate(dateIso: String): List<InactivityBlockEntity>

    @Query("SELECT * FROM inactivity_blocks WHERE id = :id")
    suspend fun getById(id: Long): InactivityBlockEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(block: InactivityBlockEntity): Long

    @Update
    suspend fun update(block: InactivityBlockEntity)

    @Delete
    suspend fun delete(block: InactivityBlockEntity)
}
