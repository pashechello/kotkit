package com.kotkit.basic.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kotkit.basic.data.local.db.entities.SelectorsConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SelectorsConfigDao {

    @Query("SELECT * FROM selectors_config WHERE id = 1")
    fun getConfigFlow(): Flow<SelectorsConfigEntity?>

    @Query("SELECT * FROM selectors_config WHERE id = 1")
    suspend fun getConfig(): SelectorsConfigEntity?

    @Query("SELECT version FROM selectors_config WHERE id = 1")
    suspend fun getVersion(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: SelectorsConfigEntity)

    @Query("DELETE FROM selectors_config")
    suspend fun deleteAll()
}
