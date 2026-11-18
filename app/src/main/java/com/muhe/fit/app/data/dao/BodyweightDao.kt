package com.muhe.fit.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.muhe.fit.app.data.entity.BodyweightEntity

// 主键是日期 d（条目无 id 键，stamps.go dayKeyOfD 同源），单条读写按 getDay/deleteDay 命名
@Dao
abstract class BodyweightDao : BaseDao<BodyweightEntity>() {

    @Query("SELECT * FROM bodyweight")
    abstract suspend fun getAll(): List<BodyweightEntity>

    @Query("SELECT * FROM bodyweight WHERE _ts > :since")
    abstract suspend fun getSince(since: Long): List<BodyweightEntity>

    @Query("SELECT * FROM bodyweight WHERE d = :d")
    abstract suspend fun getDay(d: String): BodyweightEntity?

    // 删除行 = 先记文档级 deleted.bodyweight 删除戳（同步层负责）再调本方法
    @Query("DELETE FROM bodyweight WHERE d = :d")
    abstract suspend fun deleteDay(d: String)

    @Query("DELETE FROM bodyweight")
    abstract suspend fun clearAll()
}
