package com.muhe.fit.app.di

import android.content.Context
import androidx.room.Room
import com.muhe.fit.app.data.MuheDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// ── Room 数据库装配（MuHeApp.kt 预留的「Room DB 挂 Module」落点，M1 网络层批次补齐） ──
// 迁移纪律见 MuheDatabase.kt 头注释：改 Entity 必须 version+1 + MIGRATION_x_y，
// 禁破坏性 fallback（访客数据只有本地这一份）。
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): MuheDatabase =
        Room.databaseBuilder(context, MuheDatabase::class.java, MuheDatabase.DATABASE_NAME)
            .build()

    // 八 Dao 不单独 provide：消费面经 db.workoutDao() 等取用（接口面集中在 MuheDatabase，
    // SyncClient 是唯一注入方；后续 ViewModel 若需直接读库，再按表拆 @Provides）
    // ↳ M2 打卡屏开始直读两张表，按上述注记落点拆出（SyncClient 仍走 db 直取，不经这两处）：
    @Provides
    fun gymCardDao(db: MuheDatabase): com.muhe.fit.app.data.dao.GymCardDao = db.gymCardDao()

    @Provides
    fun attendanceDao(db: MuheDatabase): com.muhe.fit.app.data.dao.AttendanceDao = db.attendanceDao()

    // ── Workout 屏（批 2 · MVP）：训练会话落库 + StartChooser 例行清单 ──
    @Provides
    fun workoutDao(db: MuheDatabase): com.muhe.fit.app.data.dao.WorkoutDao = db.workoutDao()

    @Provides
    fun routineDao(db: MuheDatabase): com.muhe.fit.app.data.dao.RoutineDao = db.routineDao()
}
