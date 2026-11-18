package com.muhe.fit.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.muhe.fit.app.data.entity.GymCardEntity

@Dao
abstract class GymCardDao : BaseDao<GymCardEntity>() {

    @Query("SELECT * FROM gymCards")
    abstract suspend fun getAll(): List<GymCardEntity>

    @Query("SELECT * FROM gymCards WHERE _ts > :since")
    abstract suspend fun getSince(since: Long): List<GymCardEntity>

    @Query("SELECT * FROM gymCards WHERE id = :id")
    abstract suspend fun getById(id: String): GymCardEntity?

    // 删卡是合法操作（CheckIn 语义）；删除行 = 先记文档级 deleted.gymCards 戳（同步层负责）再调本方法
    @Query("DELETE FROM gymCards WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM gymCards")
    abstract suspend fun clearAll()
}
