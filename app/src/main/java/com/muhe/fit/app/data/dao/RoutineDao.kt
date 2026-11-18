package com.muhe.fit.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.muhe.fit.app.data.entity.RoutineEntity

@Dao
abstract class RoutineDao : BaseDao<RoutineEntity>() {

    @Query("SELECT * FROM routines")
    abstract suspend fun getAll(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE _ts > :since")
    abstract suspend fun getSince(since: Long): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE id = :id")
    abstract suspend fun getById(id: String): RoutineEntity?

    // 删除行 = 先记文档级 deleted.routines 删除戳（同步层负责）再调本方法
    @Query("DELETE FROM routines WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM routines")
    abstract suspend fun clearAll()
}
