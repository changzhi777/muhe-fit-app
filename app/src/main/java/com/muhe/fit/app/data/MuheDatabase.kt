package com.muhe.fit.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.muhe.fit.app.data.converter.Converters
import com.muhe.fit.app.data.dao.AttendanceDao
import com.muhe.fit.app.data.dao.BodyweightDao
import com.muhe.fit.app.data.dao.CustomExDao
import com.muhe.fit.app.data.dao.EquipProfileDao
import com.muhe.fit.app.data.dao.GymCardDao
import com.muhe.fit.app.data.dao.MeasurementDao
import com.muhe.fit.app.data.dao.RoutineDao
import com.muhe.fit.app.data.dao.WorkoutDao
import com.muhe.fit.app.data.entity.AttendanceEntity
import com.muhe.fit.app.data.entity.BodyweightEntity
import com.muhe.fit.app.data.entity.CustomExEntity
import com.muhe.fit.app.data.entity.EquipProfileEntity
import com.muhe.fit.app.data.entity.GymCardEntity
import com.muhe.fit.app.data.entity.MeasurementEntity
import com.muhe.fit.app.data.entity.RoutineEntity
import com.muhe.fit.app.data.entity.WorkoutEntity

// ── 沐禾健身本地库：八类 stamps 表一次建齐（裁定 #17 / android-app.md §2.2） ──
// 表结构 = 服务器盖章模型的客户端半。服务端语义真源：
// ck/backend-go/internal/data/stamps.go（七类 delLists/delTime/_ts/_wid/_wids 盖章、
// mergeDeletions/mergeEdits 只增不减合并）；attendance 为裁定新增第八类。
// 每表 = 业务查询列（投影）+ raw 整条目 JSON（透传重建源）+ stamp 盖章列（_ts/_wid/_wids）。
// 本表不含文档级元数据（_rev / deleted.* 删除戳 / edited.* 编辑戳 / unitSet 等 settings 键）——
// 那是 states 整档合并的输入，存储载体由 M1 网络层批次裁定（DataStore 或补表），届时与
// GET /api/data/rev 乐观并发、整档 PUT 合并一并落地。
@Database(
    entities = [
        WorkoutEntity::class,        // 1 workouts      只增不减
        RoutineEntity::class,        // 2 routines      可变（mergeEdits）
        CustomExEntity::class,       // 3 customEx      可变（mergeEdits）
        BodyweightEntity::class,     // 4 bodyweight    只增不减（键 d）
        MeasurementEntity::class,    // 5 measurements  只增不减（键 d）
        GymCardEntity::class,        // 6 gymCards      只增不减
        EquipProfileEntity::class,   // 7 equipProfiles 可变（mergeEdits）
        AttendanceEntity::class,     // 8 attendance    只增不减（裁定 #17 新增）
    ],
    version = 1,
    exportSchema = true,             // schemas/ 入库，版本迁移对照
)
@TypeConverters(Converters::class)
abstract class MuheDatabase : RoomDatabase() {

    abstract fun workoutDao(): WorkoutDao
    abstract fun routineDao(): RoutineDao
    abstract fun customExDao(): CustomExDao
    abstract fun bodyweightDao(): BodyweightDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun gymCardDao(): GymCardDao
    abstract fun equipProfileDao(): EquipProfileDao
    abstract fun attendanceDao(): AttendanceDao

    companion object {
        const val DATABASE_NAME = "muhe.db"

        // ── Migration 占位 ─────────────────────────────────────────────
        // 客户端 Room 版本号独立计数，与服务端迁移号无关（服务端 api-go 从 0012 起顺延，
        // 是 PG migrations 的序列；本库 version 从 2 起才需要 MIGRATION 对象）。
        // 迁移纪律：每次改 Entity 必须递增 version + 落对应 MIGRATION_x_y + schemas/ 快照留档，
        // 不允许破坏性 fallbackToDestructiveMigration 吃掉用户本地数据（访客数据只有这一份）。
        // val MIGRATION_1_2 = object : Migration(1, 2) { ... }
    }
}
