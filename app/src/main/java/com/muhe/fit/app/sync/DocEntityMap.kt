package com.muhe.fit.app.sync

import com.muhe.fit.app.data.entity.AttendanceEntity
import com.muhe.fit.app.data.entity.BodyweightEntity
import com.muhe.fit.app.data.entity.CustomExEntity
import com.muhe.fit.app.data.entity.EquipProfileEntity
import com.muhe.fit.app.data.entity.GymCardEntity
import com.muhe.fit.app.data.entity.MeasurementEntity
import com.muhe.fit.app.data.entity.RoutineEntity
import com.muhe.fit.app.data.entity.Stamp
import com.muhe.fit.app.data.entity.WorkoutEntity
import kotlinx.serialization.json.JsonObject

// ── 文档条目 ↔ Room 八表实体 映射（同步层所有，data/ 包零改动） ────────────────
// 真源：各 Entity 文件头注释（协议形状与 stamps.go 键语义）；raw 整条目 JSON 列是
// 唯一重建源（透传），投影列只服务查询面——doc → Entity 一次性展开，Entity → doc
// 直接读 raw（动态字段零丢失，不做列重建）。
object DocEntityMap {

    // ── 盖章列（Stamps.kt：_ts/_wid/_wids，列名与 JS 条目盖章键一字不差） ──
    private fun stampOf(e: JsonObject): Stamp = Stamp(
        ts = StateDoc.longOf(e["_ts"]),
        wid = StateDoc.strOf(e["_wid"]),
        wids = StateDoc.arrOf(e["_wids"]).mapNotNull { StateDoc.strOf(it) }.ifEmpty { null },
    )

    /** 条目主键（协议键）。workouts 缺 id 时以 d|start 作存储身份（stamps.go workoutKey 兜底同源；raw 不动）。 */
    fun entryKey(field: String, e: JsonObject): String = when (field) {
        "workouts" -> StateDoc.strOf(e["id"]) ?: (StateDoc.strOf(e["d"]) ?: "") + "|" + StateDoc.longOf(e["start"])
        "bodyweight", "measurements" -> StateDoc.strOf(e["d"]) ?: ""
        else -> StateDoc.strOf(e["id"]) ?: ""
    }

    /** 条目最后编辑时刻（stamps.go delTime 语义，删除戳防复活的比较基线）。 */
    fun entryTime(field: String, e: JsonObject): Long = when (field) {
        "workouts" -> maxOf(StateDoc.longOf(e["_ts"]), StateDoc.longOf(e["end"]), StateDoc.longOf(e["start"]))
        "bodyweight", "measurements" -> StateDoc.longOf(e["t"])
        else -> StateDoc.longOf(e["_ts"])
    }

    fun workout(e: JsonObject) = WorkoutEntity(
        id = entryKey("workouts", e),
        d = StateDoc.strOf(e["d"]) ?: "",
        start = StateDoc.longOf(e["start"]),
        end = StateDoc.longOf(e["end"]),
        name = StateDoc.strOf(e["name"]),
        routineId = StateDoc.strOf(e["routineId"]),
        vol = StateDoc.numOrNull(e["vol"]),
        raw = e.toString(),
        stamp = stampOf(e),
    )

    fun routine(e: JsonObject) = RoutineEntity(
        id = entryKey("routines", e),
        name = StateDoc.strOf(e["name"]),
        raw = e.toString(),
        stamp = stampOf(e),
    )

    fun customEx(e: JsonObject) = CustomExEntity(
        id = entryKey("customEx", e),
        name = StateDoc.strOf(e["n"]),
        eq = StateDoc.strOf(e["eq"]),
        raw = e.toString(),
        stamp = stampOf(e),
    )

    fun bodyweight(e: JsonObject) = BodyweightEntity(
        d = entryKey("bodyweight", e),
        w = StateDoc.numOrNull(e["w"]),
        t = StateDoc.longOrNull(e["t"]),
        raw = e.toString(),
        stamp = stampOf(e),
    )

    fun measurement(e: JsonObject) = MeasurementEntity(
        d = entryKey("measurements", e),
        t = StateDoc.longOrNull(e["t"]),
        raw = e.toString(),
        stamp = stampOf(e),
    )

    fun gymCard(e: JsonObject) = GymCardEntity(
        id = entryKey("gymCards", e),
        label = StateDoc.strOf(e["label"]),
        value = StateDoc.strOf(e["value"]),
        fmt = StateDoc.strOf(e["fmt"]),
        raw = e.toString(),
        stamp = stampOf(e),
    )

    fun equipProfile(e: JsonObject) = EquipProfileEntity(
        id = entryKey("equipProfiles", e),
        name = StateDoc.strOf(e["name"]),
        accV = StateDoc.longOrNull(e["accV"])?.toInt(),
        raw = e.toString(),
        stamp = stampOf(e),
    )

    fun attendance(e: JsonObject) = AttendanceEntity(
        id = entryKey("attendance", e),
        gymCardId = StateDoc.strOf(e["gymCardId"]),
        at = StateDoc.longOf(e["ts"]),   // 协议键 ts → 列 ts（属性 at 避让盖章 _ts）
        note = StateDoc.strOf(e["note"]),
        raw = e.toString(),
        stamp = stampOf(e),
    )
}
