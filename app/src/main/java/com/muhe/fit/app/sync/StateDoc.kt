package com.muhe.fit.app.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ── StateDoc：服务端 states 整档的客户端模型 ─────────────────────────────────
// 契约：api-go/openapi.yaml #/components/schemas/State —— "representative, not enforced"，
// 服务端视为不透明。真源拆解（stamps 盖章字段语义）：
//   ck/backend-go/internal/data/stamps.go（_rev/_wid/_wids/_ts 文档级盖章 + deleted/edited 只增不减）
//   ck/frontend/src/lib/sync-merge.js（客户端半合并；highestStamp/DEL_LISTS 同处）
// 本模型以 JsonObject 宽松承载整档：八类条目数组 + 其余一切键（settings/week/queue/unit/…）
// 原样透传——未知字段进 SyncMetaStore 的 remainder，重建上传零丢失。
//
// 八类条目数组（stamps.go delLists 七类 + 裁定 #17 新增 attendance；favEx 是 PWA 概念不在 Android 八类）：
object StateDoc {

    val ENTRY_FIELDS: List<String> = listOf(
        "workouts", "routines", "customEx", "bodyweight", "measurements",
        "gymCards", "equipProfiles", "attendance",
    )

    // ── 数值读取（JS number 同源，ms epoch 一律 Long；条目键可能带小数/科学计数的兜底双保险） ──
    fun numOf(e: JsonElement?): Double = when {
        e == null || e is JsonNull -> 0.0
        e is JsonPrimitive -> e.doubleOrNull ?: e.content.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    fun longOf(e: JsonElement?): Long = numOf(e).toLong()

    /** 可空数值读取：键缺失/显式 null → null（投影列的 Optional 语义）。 */
    fun numOrNull(e: JsonElement?): Double? = when {
        e == null || e is JsonNull -> null
        e is JsonPrimitive -> e.doubleOrNull ?: e.content.toDoubleOrNull()
        else -> null
    }

    fun longOrNull(e: JsonElement?): Long? = numOrNull(e)?.toLong()

    fun strOf(e: JsonElement?): String? = when {
        e == null || e is JsonNull -> null
        e is JsonPrimitive -> e.content
        else -> null
    }

    fun objOf(e: JsonElement?): JsonObject? = if (e is JsonObject) e else null

    fun arrOf(e: JsonElement?): List<JsonElement> = if (e is JsonArray) e else emptyList()

    /** 从 JSON 文本解析整档；非对象（代理页/损坏响应）返回 null（对齐 api.js exchange 的坏响应防御）。 */
    fun parse(json: Json, text: String): JsonObject? =
        try { objOf(json.parseToJsonElement(text)) } catch (_: Exception) { null }

    // ── 拆档：八类条目数组 与 文档级余部（remainder）分离 ──────────────────────
    // 余部（_ts/_rev/_wid/_wids/deleted/edited/settings 键…）进 SyncMetaStore 持久化，
    // 条目数组落 Room 八表（raw 透传）。删键重建 = 原档减条目键，其余一字不动。
    fun split(doc: JsonObject): Split =
        Split(
            remainder = buildJsonObject {
                doc.forEach { (k, v) -> if (k !in ENTRY_FIELDS) put(k, v) }
            },
            entries = ENTRY_FIELDS.associateWith { field ->
                arrOf(doc[field]).mapNotNull { objOf(it) }
            },
        )

    // ── 合档：余部 + 八类条目数组重建整档（上传前组装） ─────────────────────────
    // 条目非空才放键（对齐服务端/前端「无内容不建数组」习惯；workouts/routines 契约允许 null，
    // 但空数组与缺键等价，客户端一律缺键表达）。
    fun assemble(remainder: JsonObject, entries: Map<String, List<JsonObject>>): JsonObject =
        buildJsonObject {
            remainder.forEach { (k, v) -> put(k, v) }
            entries.forEach { (field, list) ->
                if (field in ENTRY_FIELDS && list.isNotEmpty()) put(field, JsonArray(list))
            }
        }

    // ── 文档级盖章字段（stamps.go：_ts 客户端写、_rev/_wid/_wids 服务端只读，客户端上报忽略） ──
    fun ts(doc: JsonObject): Long = longOf(doc["_ts"])

    /** 服务端版本号；无 _rev 键（客户端组装的档）返回 null。 */
    fun rev(doc: JsonObject): Long? =
        if (doc.containsKey("_rev") && doc["_rev"] !is JsonNull) longOf(doc["_rev"]) else null

    fun wid(doc: JsonObject): String? = strOf(doc["_wid"])

    fun wids(doc: JsonObject): List<String> =
        arrOf(doc["_wids"]).mapNotNull { strOf(it) }

    // ── highestStamp（stamps.go HighestStamp 客户端半：文档携带的最晚时间戳） ──
    // 用途：写档时 _ts 不因时钟回拨倒退（useStore.js persist:571 S._ts = max(now, base.ts+1, S._ts)
    // 与 stamps.go StampPut t = max(now, highest+1) 同思想）。嵌套 _f/STAMPED_MAPS 的扫描
    // 随 raw 透传天然保留，此处扫 M1 影响面：文档 _ts/unitSet.at/resetAt + deleted/edited
    // + 五类可变条目 _ts 与 _f（stamps.go entryLists 五类同集）。
    fun highestStamp(doc: JsonObject): Double {
        var best = 0.0
        fun see(v: JsonElement?) {
            val n = kotlin.math.abs(numOf(v))
            if (n > best) best = n
        }
        see(doc["_ts"])
        objOf(doc["unitSet"])?.let { see(it["at"]) }
        see(doc["resetAt"])
        objOf(doc["edited"])?.forEach { (_, v) -> see(v) }
        objOf(doc["deleted"])?.forEach { (_, m) -> objOf(m)?.forEach { (_, v) -> see(v) } }
        for (f in listOf("routines", "customEx", "equipProfiles", "gymCards", "workouts")) {
            for (e in arrOf(doc[f])) {
                val o = objOf(e) ?: continue
                see(o["_ts"])
                objOf(o["_f"])?.forEach { (_, v) -> see(v) }
            }
        }
        return best
    }

    /** 拆档结果：remainder（文档级余部）+ entries（八类条目数组）。 */
    data class Split(val remainder: JsonObject, val entries: Map<String, List<JsonObject>>)
}
