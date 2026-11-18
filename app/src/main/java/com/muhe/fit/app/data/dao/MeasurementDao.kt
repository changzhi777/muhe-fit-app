package com.muhe.fit.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.muhe.fit.app.data.entity.MeasurementEntity

// 主键是日期 d（一天一次 check-in，lib/measurements.js emptyMeasurement 同构）
@Dao
abstract class MeasurementDao : BaseDao<MeasurementEntity>() {

    @Query("SELECT * FROM measurements")
    abstract suspend fun getAll(): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE _ts > :since")
    abstract suspend fun getSince(since: Long): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE d = :d")
    abstract suspend fun getDay(d: String): MeasurementEntity?

    // 删除行 = 先记文档级 deleted.measurements 删除戳（同步层负责）再调本方法
    @Query("DELETE FROM measurements WHERE d = :d")
    abstract suspend fun deleteDay(d: String)

    @Query("DELETE FROM measurements")
    abstract suspend fun clearAll()
}
