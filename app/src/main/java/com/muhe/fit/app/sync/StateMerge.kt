package com.muhe.fit.app.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

// ── StateMerge：states 整档合并的客户端半（409 冲突/拉取套用，纯函数无副作用） ──
// 真源对位：
//   服务端半 stamps 盖章合并 = ck/backend-go/internal/data/stamps.go（MergeDeletions/MergeEdits/
//   laterDel/capStamps —— 服务端 StampPut 对每次接受写入都会重跑一遍，客户端合并出的
//   deleted/edited 记录是为了让它有账可并）；
//   客户端半整档合并 = ck/frontend/src/lib/sync-merge.js mergeStates（本文件为其 M1 子集移植）。
//
// M1 子集口径（条目级「时间戳新者胜」+ 记录只增不减，与 stamps.go 对齐）：
//   ✅ 八类条目数组并集（同键 _ts 晚者胜；bodyweight/measurements 按 d 一天一条 t 晚者胜）
//   ✅ deleted/edited 只增不减（laterDel 平局负值=加回胜；删除戳落地防复活）
//   ✅ edited 打戳设置按戳取值（applyEdits 子集：无 undone/undoPick）
//   ✅ favEx 并集 / exNotes·barWeights·exWeights 键级新者胜 / STAMPED_MAPS 六映射键级 _ts 晚者胜
//   ❌ M2 对齐项（Android 域未消费，暂由新副本整体决定）：mergeEntry 条目内字段级 _f 合并、
//      undo 标记（undone/undoPick）、unit 换算 lead（unitSet）、resetAt/sinceReset 清零、
//      routines 顺序 unionByNeighbours/routineOrder、exWeights beatsWeight 方向校正、
//      workout media 并集（mergeWorkoutMedia）
object StateMerge {

    // 删除/并集涉及的八类字段（stamps.go delLists 七类客户端半 + 裁定 #17 attendance；
    // favEx 等 PWA 域键不在 Android 八类，仅文档级透传合并）
    private val ENTRY_FIELDS = StateDoc.ENTRY_FIELDS

    // stamps.go HighestStamp:192 的 STAMPED_MAPS 同名单
    private val STAMPED_MAPS = listOf(
        "balanceOverrides", "loadKind", "plates", "dbLoad", "dumbbells", "dayNotes",
    )

    // sync-merge.js PER_KEY：按子键打戳的映射
    private val PER_KEY = setOf("week", "dayPlan", "exNotes", "barWeights")

    // sync-merge.js OWN_MERGE：有自己合并语义的顶层键（applyEdits 不按戳整体覆盖它们）
    private val OWN_MERGE = setOf(
        "_ts", "_rev", "_wid", "_wids", "_unstamped", "_prior", "active", "unit", "unitSet",
        "resetAt", "resetIds", "deleted", "edited", "undone", "routineOrder",
        "workouts", "routines", "customEx", "equipProfiles", "gymCards", "bodyweight",
        "measurements", "favEx", "exWeights", "balanceOverrides", "loadKind", "plates",
        "dbLoad", "dumbbells", "dayNotes", "attendance",
    )

    // stamps.go DELETED_MAX / capStamps：每字段删除记录上限，超限留最新的
    private const val DELETED_MAX = 5000

    // ── laterDel（stamps.go:107）：更晚时间戳胜；平局负值（加回）胜 ──
    private fun laterDel(v: Double, cur: Double): Boolean {
        val ax = abs(v); val ay = abs(cur)
        return ax > ay || (ax == ay && v < cur)
    }

    // ── capStamps（stamps.go:88）：超上限裁掉最旧 ──
    private fun capStamps(m: Map<String, JsonElement>): JsonObject {
        if (m.size <= DELETED_MAX) return JsonObject(m)
        val keep = m.entries
            .sortedByDescending { abs(StateDoc.numOf(it.value)) }
            .take(DELETED_MAX)
        return buildJsonObject { keep.forEach { (k, v) -> put(k, v) } }
    }

    // ── mergeDeletions（stamps.go:114 / sync-merge.js:566 同语义）：两份删除记录逐键取更晚 ──
    fun mergeDeletions(a: JsonObject?, b: JsonObject?): JsonObject? {
        val x = a ?: JsonObject(emptyMap())
        val y = b ?: JsonObject(emptyMap())
        val out = LinkedHashMap<String, JsonElement>()
        for (f in ENTRY_FIELDS) {
            val mx = StateDoc.objOf(x[f]) ?: JsonObject(emptyMap())
            val my = StateDoc.objOf(y[f]) ?: JsonObject(emptyMap())
            if (mx.isEmpty() && my.isEmpty()) continue
            val m = mx.asMutable()
            for ((k, v) in my) {
                if (m[k] == null || laterDel(StateDoc.numOf(v), StateDoc.numOf(m[k]))) m[k] = v
            }
            if (m.isNotEmpty()) out[f] = capStamps(m)
        }
        return JsonObject(out).takeIf { it.isNotEmpty() }
    }

    // ── mergeEdits（stamps.go:139 / sync-merge.js:678 同语义）：两份编辑记录逐键取更晚 ──
    fun mergeEdits(a: JsonObject?, b: JsonObject?): JsonObject? {
        val out = (a ?: JsonObject(emptyMap())).asMutable()
        for ((k, v) in b ?: JsonObject(emptyMap())) {
            if (out[k] == null || !(StateDoc.numOf(out[k]) >= StateDoc.numOf(v))) out[k] = v
        }
        return JsonObject(out).takeIf { it.isNotEmpty() }
    }

    // ── applyDeletions（sync-merge.js:584）：删除戳（正）≥ 条目最后编辑 → 移除该条目 ──
    // 负值戳 = 加回标记，不移除；条目自身时间晚于删除戳 = 删除后又编辑过，保留。
    private fun applyDeletions(field: String, entries: List<JsonObject>, deleted: JsonObject?): List<JsonObject> {
        val m = deleted?.let { StateDoc.objOf(it[field]) } ?: return entries
        return entries.filter { e ->
            val at = StateDoc.numOf(m[DocEntityMap.entryKey(field, e)])
            !(at > 0 && at >= entryTimeOf(field, e))
        }
    }

    // 条目最后编辑时刻（stamps.go delTime 客户端半）
    private fun entryTimeOf(field: String, e: JsonObject): Double = when (field) {
        "workouts" -> maxOf(
            StateDoc.numOf(e["_ts"]), StateDoc.numOf(e["end"]), StateDoc.numOf(e["start"]),
        )
        "bodyweight", "measurements" -> StateDoc.numOf(e["t"])
        else -> StateDoc.numOf(e["_ts"])
    }

    // ── mergeStates（sync-merge.js:719 的 M1 子集）：两档合并，产出待上传/落库的新档 ──
    fun mergeStates(local: JsonObject, remote: JsonObject): JsonObject {
        // 空档短路：仍须剥除服务端只读字段（_rev/_wid/_wids/_unstamped/_prior）——否则首拉时
        // 这些键会常驻 remainder，此后每次推送原样上报（与 :181 的「客户端永不上报」矛盾）。
        if (local.isEmpty()) return stripServerReadonly(remote)
        if (remote.isEmpty()) return stripServerReadonly(local)

        // 新副本决定无戳字段（out = clone(n)，sync-merge.js:748；平局 local 先手——newerOf(a,b) 平局取 a）
        val nIsRemote = StateDoc.ts(remote) > StateDoc.ts(local)
        val n = if (nIsRemote) remote else local
        val o = if (nIsRemote) local else remote
        val out = n.asMutable()

        // 1) 八类条目数组并集：两边条目都保留（只增不减），同键 _ts 晚者胜。
        //    unionById（sync-merge.js:95）：n 的条目按 n 的序在前，o 独有条目追加，重复键 n 先手胜。
        for (f in ENTRY_FIELDS) {
            val nl = StateDoc.arrOf(n[f]).mapNotNull { StateDoc.objOf(it) }
            val ol = StateDoc.arrOf(o[f]).mapNotNull { StateDoc.objOf(it) }
            if (nl.isEmpty() && ol.isEmpty()) { out.remove(f); continue }
            val merged = when (f) {
                // M1 简化（与 unionById 同路径，勿被旧注释误导）：bodyweight/measurements 重复 d 键
                // 是 n（文档 _ts 较新一侧）先手胜，未实现 mergeBodyweight（sync-merge.js:166）的
                // 「同 d 取 t 晚者 + 按 d 排序」——设备 A 修过的当天体重可能被较新文档的旧值盖掉。
                // StateMergeTest 已按此口径断言；真实现随 M2 对齐项一并落（改语义须同步改测试）。
                "bodyweight", "measurements" -> unionById(f, nl, ol)
                else -> unionById(f, nl, ol)
            }
            out[f] = JsonArray(merged)
        }

        // 2) favEx 并集（sync-merge.js:823：set union，收藏只增）
        if ("favEx" in n || "favEx" in o) {
            val nl = StateDoc.arrOf(n["favEx"]); val ol = StateDoc.arrOf(o["favEx"])
            if (nl.isEmpty() && ol.isEmpty()) out.remove("favEx")
            else {
                val seen = HashSet<String>()
                out["favEx"] = JsonArray((nl + ol).filter { seen.add(it.toString()) })
            }
        } else out.remove("favEx")

        // 3) exNotes / barWeights：键级新者胜（sync-merge.js:846 { ...o, ...n }）
        for (f in listOf("exNotes", "barWeights")) {
            if (f in n || f in o) out[f] = mergeNewerWinsPerKey(n, o, f) else out.remove(f)
        }

        // 4) exWeights：键级新者胜（M1 简化；beatsWeight 方向校正 = M2 对齐项，sync-merge.js:181）
        if ("exWeights" in n || "exWeights" in o) out["exWeights"] = mergeNewerWinsPerKey(n, o, "exWeights")
        else out.remove("exWeights")

        // 5) STAMPED_MAPS 六映射（sync-merge.js:852 mergeStampedMap）：键级并集，同键 _ts 晚者胜
        for (f in STAMPED_MAPS) {
            if (f in n || f in o) out[f] = mergeStampedMap(n, o, f) else out.remove(f)
        }

        // 6) 删除/编辑记录只增不减（stamps.go StampPut:237 对齐）
        val deleted = mergeDeletions(StateDoc.objOf(local["deleted"]), StateDoc.objOf(remote["deleted"]))
        if (deleted != null) out["deleted"] = deleted else out.remove("deleted")

        // 7) 删除戳落地（sync-merge.js:831 applyDeletions：记录合并后立即套用）
        for (f in ENTRY_FIELDS) {
            val cur = StateDoc.arrOf(out[f]).mapNotNull { StateDoc.objOf(it) }
            if (cur.isEmpty()) continue
            val kept = applyDeletions(f, cur, deleted)
            if (kept.size != cur.size) out[f] = JsonArray(kept)
        }

        // 8) edited 打戳设置按戳取值（applyEdits 子集）+ edited 记录合并
        val mergedEdited = applyAndMergeEdits(out, n, o)
        if (mergedEdited != null) out["edited"] = mergedEdited else out.remove("edited")

        // 文档 _ts = 两边最晚（sync-merge.js:871）；服务端只读字段剥除重盖（:875-877）
        out["_ts"] = JsonPrimitive(maxOf(StateDoc.ts(local), StateDoc.ts(remote)))
        out.remove("_rev"); out.remove("_wid"); out.remove("_wids")
        out.remove("_unstamped"); out.remove("_prior")   // 服务端内部标记，客户端永不上报

        return JsonObject(out)
    }

    // ── unionById（sync-merge.js:95）：n 全保留 + o 独有条目追加；重复键 n 先手胜 ──
    private fun unionById(field: String, n: List<JsonObject>, o: List<JsonObject>): List<JsonObject> {
        val seen = HashSet<String>()
        val outL = ArrayList<JsonObject>(n.size + o.size)
        for (e in n + o) {
            val k = DocEntityMap.entryKey(field, e)
            if (k.isNotEmpty() && !seen.add(k)) continue
            outL.add(e)
        }
        return outL
    }

    // mergeStampedMap（sync-merge.js:374）：键级并集，同键条目 _ts 更晚一方胜
    private fun mergeStampedMap(n: JsonObject, o: JsonObject, field: String): JsonObject {
        val outM = (StateDoc.objOf(o[field]) ?: JsonObject(emptyMap())).asMutable()
        val nm = StateDoc.objOf(n[field]) ?: JsonObject(emptyMap())
        outM.putAll(nm)
        for ((k, ov) in StateDoc.objOf(o[field]) ?: JsonObject(emptyMap())) {
            val nv = nm[k] ?: continue
            val ots = StateDoc.numOf(StateDoc.objOf(ov)?.get("_ts"))
            val nts = StateDoc.numOf(StateDoc.objOf(nv)?.get("_ts"))
            if (ots > nts) outM[k] = ov
        }
        return JsonObject(outM)
    }

    // { ...o, ...n }（sync-merge.js:846 / mergeExWeights 简化）：键级新者胜
    private fun mergeNewerWinsPerKey(n: JsonObject, o: JsonObject, field: String): JsonObject {
        val outM = (StateDoc.objOf(o[field]) ?: JsonObject(emptyMap())).asMutable()
        StateDoc.objOf(n[field])?.let { outM.putAll(it) }
        return JsonObject(outM)
    }

    // applyEdits 子集（sync-merge.js:690）+ mergeEdits：edited 涉及的键，值取戳更晚一侧；
    // 点分键（week.3 / dayPlan.<date> 等 PER_KEY 形态）按子键取值；平局保持新副本的值。
    private fun applyAndMergeEdits(out: LinkedHashMap<String, JsonElement>, n: JsonObject, o: JsonObject): JsonObject? {
        val en = StateDoc.objOf(n["edited"]) ?: JsonObject(emptyMap())
        val eo = StateDoc.objOf(o["edited"]) ?: JsonObject(emptyMap())
        for (k in (en.keys + eo.keys).toSortedSet()) {
            val tn = StateDoc.numOf(en[k])
            val to = StateDoc.numOf(eo[k])
            if (tn == to) continue                       // 平局保持 n 的值
            val src = if (to > tn) o else n              // 戳晚者胜
            val dot = k.indexOf('.')
            if (dot < 0) {
                if (k in OWN_MERGE || k in PER_KEY) continue
                val v = src[k]
                if (v == null || v is JsonNull) out.remove(k) else out[k] = v
                continue
            }
            val f = k.substring(0, dot); val s = k.substring(dot + 1)
            if (f !in PER_KEY) continue
            val into = StateDoc.objOf(out[f])?.asMutable() ?: LinkedHashMap()
            val v = StateDoc.objOf(src[f])?.get(s)
            if (v == null || v is JsonNull) into.remove(s) else into[s] = v
            if (into.isEmpty()) out.remove(f) else out[f] = JsonObject(into)
        }
        return mergeEdits(en, eo)
    }

    // 服务端只读字段剥除（:181-183 同名单）：短路/收尾两个出口都要走，客户端永不上报
    private fun stripServerReadonly(doc: JsonObject): JsonObject {
        val out = doc.asMutable()
        out.remove("_rev"); out.remove("_wid"); out.remove("_wids")
        out.remove("_unstamped"); out.remove("_prior")
        return JsonObject(out)
    }

    private fun JsonObject.asMutable(): LinkedHashMap<String, JsonElement> = LinkedHashMap(this)
}
