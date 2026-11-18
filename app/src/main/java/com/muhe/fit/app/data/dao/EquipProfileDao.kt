package com.muhe.fit.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.muhe.fit.app.data.entity.EquipProfileEntity

@Dao
abstract class EquipProfileDao : BaseDao<EquipProfileEntity>() {

    @Query("SELECT * FROM equipProfiles")
    abstract suspend fun getAll(): List<EquipProfileEntity>

    @Query("SELECT * FROM equipProfiles WHERE _ts > :since")
    abstract suspend fun getSince(since: Long): List<EquipProfileEntity>

    @Query("SELECT * FROM equipProfiles WHERE id = :id")
    abstract suspend fun getById(id: String): EquipProfileEntity?

    // 删除行 = 先记文档级 deleted.equipProfiles 删除戳（同步层负责）再调本方法
    @Query("DELETE FROM equipProfiles WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM equipProfiles")
    abstract suspend fun clearAll()
}
