package com.muhe.fit.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.muhe.fit.app.data.entity.WorkoutEntity

@Dao
abstract class WorkoutDao : BaseDao<WorkoutEntity>() {

    // 全量拉取——上传整档合并前重建 workouts 数组用
    @Query("SELECT * FROM workouts")
    abstract suspend fun getAll(): List<WorkoutEntity>

    // 本地辅助查询：按盖章 _ts 增量读（UI 增量/对账）。协议增量判定在文档级
    // GET /api/data/rev 先比版本再拉全量，不走本方法下载
    @Query("SELECT * FROM workouts WHERE _ts > :since")
    abstract suspend fun getSince(since: Long): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE id = :id")
    abstract suspend fun getById(id: String): WorkoutEntity?

    // 删除行 = 先记文档级 deleted.workouts 删除戳（同步层负责）再调本方法，防合并复活
    @Query("DELETE FROM workouts WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM workouts")
    abstract suspend fun clearAll()
}
