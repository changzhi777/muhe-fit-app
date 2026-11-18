package com.muhe.fit.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.muhe.fit.app.data.entity.CustomExEntity

@Dao
abstract class CustomExDao : BaseDao<CustomExEntity>() {

    @Query("SELECT * FROM customEx")
    abstract suspend fun getAll(): List<CustomExEntity>

    @Query("SELECT * FROM customEx WHERE _ts > :since")
    abstract suspend fun getSince(since: Long): List<CustomExEntity>

    @Query("SELECT * FROM customEx WHERE id = :id")
    abstract suspend fun getById(id: String): CustomExEntity?

    // 删除行 = 先记文档级 deleted.customEx 删除戳（同步层负责）再调本方法
    @Query("DELETE FROM customEx WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM customEx")
    abstract suspend fun clearAll()
}
