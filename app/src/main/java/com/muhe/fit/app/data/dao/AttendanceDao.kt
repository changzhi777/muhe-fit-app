package com.muhe.fit.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.muhe.fit.app.data.entity.AttendanceEntity

@Dao
abstract class AttendanceDao : BaseDao<AttendanceEntity>() {

    @Query("SELECT * FROM attendance")
    abstract suspend fun getAll(): List<AttendanceEntity>

    @Query("SELECT * FROM attendance WHERE _ts > :since")
    abstract suspend fun getSince(since: Long): List<AttendanceEntity>

    // 打卡历史/月度统计主查询（M2 打卡屏消费；列 ts 建有索引）
    @Query("SELECT * FROM attendance WHERE ts BETWEEN :from AND :to ORDER BY ts DESC")
    abstract suspend fun getRange(from: Long, to: Long): List<AttendanceEntity>

    @Query("SELECT * FROM attendance WHERE id = :id")
    abstract suspend fun getById(id: String): AttendanceEntity?

    // 误打卡撤销 = 先记文档级 deleted.attendance 删除戳（同步层负责）再调本方法
    @Query("DELETE FROM attendance WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM attendance")
    abstract suspend fun clearAll()
}
