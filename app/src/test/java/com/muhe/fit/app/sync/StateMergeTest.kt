package com.muhe.fit.app.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StateMerge 合并语义真测试（V1.6：net-builder 批次「静态对拍未跑用例」特列的转正）。
 *
 * fixture 手工 JSON 构造（非 resources/golden 生成物路径——真源 sync-merge.js/stamps.go 的
 * M1 子集口径见 StateMerge 头注，用例按该口径逐条推演期望值，字段键与协议一字不差）。
 * 覆盖四组语义：八类条目并集 / 同键 _ts 晚者胜 / deleted·edited 只增不减 / 余部透传。
 *
 * ⚠️ 与 sync-merge.js 全量口径的已知差异（M2 对齐项，测试断的是现实现）：
 * bodyweight/measurements 同键（同 d）并集按 unionById「新副本先手」，非 mergeBodyweight
 * 的「t 晚者胜」——见 bodyweightSameDayKeepsNewerCopyEntry。
 */
class StateMergeTest {

    // ── 手工 fixture 构造助手 ────────────────────────────────────────────────

    /** 文档级 fixture：_ts 必带（新旧副本判定基准），余键透传。 */
    private fun doc(ts: Long, vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        buildJsonObject {
            put("_ts", ts)
            fields.forEach { (k, v) -> put(k, v) }
        }

    private fun entry(id: String, ts: Long, label: String): JsonObject = buildJsonObject {
        put("id", id)
        put("label", label)
        put("_ts", ts)
    }

    private fun workout(id: String, ts: Long, name: String): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("_ts", ts)
    }

    private fun arrOf(doc: JsonObject, field: String) = StateDoc.arrOf(doc[field])

    private fun idOf(e: kotlinx.serialization.json.JsonElement): String? =
        StateDoc.strOf(StateDoc.objOf(e)?.get("id"))

    private fun numOf(e: kotlinx.serialization.json.JsonElement?): Double = StateDoc.numOf(e)

    private fun stampMapOf(doc: JsonObject, key: String): JsonObject? = StateDoc.objOf(doc[key])

    // ── 组一：八类条目数组并集 ────────────────────────────────────────────────

    /** 两边独有条目都保留（只增不减）；新副本条目在前、旧副本独有追加。 */
    @Test
    fun unionKeepsBothSidesEntriesNewerFirst() {
        val local = doc(100, "workouts" to kotlinx.serialization.json.JsonArray(listOf(workout("w1", 100, "push"))))
        val remote = doc(200, "workouts" to kotlinx.serialization.json.JsonArray(listOf(workout("w2", 200, "pull"))))
        val merged = StateMerge.mergeStates(local, remote)
        val ids = arrOf(merged, "workouts").map(::idOf)
        assertEquals(listOf("w2", "w1"), ids)   // n=remote 先手，o=local 独有追加
    }

    /** 同键条目 _ts 晚者胜：新副本（remote 文档 _ts 更晚）先手，其条目版本胜出。 */
    @Test
    fun sameKeyEntryNewerDocWins() {
        val local = doc(100, "gymCards" to kotlinx.serialization.json.JsonArray(listOf(entry("c1", 100, "旧卡名"))))
        val remote = doc(200, "gymCards" to kotlinx.serialization.json.JsonArray(listOf(entry("c1", 300, "新卡名"))))
        val merged = StateMerge.mergeStates(local, remote)
        val only = StateDoc.objOf(arrOf(merged, "gymCards").single())
        assertEquals("新卡名", StateDoc.strOf(only?.get("label")))
    }

    /** 文档 _ts 平局：local 作新副本（nIsRemote = ts(remote) > ts(local)），同键 local 版本胜。 */
    @Test
    fun documentTsTieKeepsLocalCopy() {
        val local = doc(100, "gymCards" to kotlinx.serialization.json.JsonArray(listOf(entry("c1", 5, "本端"))))
        val remote = doc(100, "gymCards" to kotlinx.serialization.json.JsonArray(listOf(entry("c1", 5, "他端"))))
        val merged = StateMerge.mergeStates(local, remote)
        val only = StateDoc.objOf(arrOf(merged, "gymCards").single())
        assertEquals("本端", StateDoc.strOf(only?.get("label")))
    }

    /** bodyweight 同一天两条：并集按键 d 去重、新副本先手（现口径）——非「t 晚者胜」。 */
    @Test
    fun bodyweightSameDayKeepsNewerCopyEntry() {
        val local = doc(200, "bodyweight" to kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
            put("d", "2026-01-01"); put("t", 100L); put("w", 80.0)
        })))
        val remote = doc(100, "bodyweight" to kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
            put("d", "2026-01-01"); put("t", 300L); put("w", 82.0)
        })))
        val merged = StateMerge.mergeStates(local, remote)
        val only = StateDoc.objOf(arrOf(merged, "bodyweight").single())
        // remote 文档更旧但条目 t 更晚——仍取 local（新副本）条目；t 晚者胜是 M2 对齐项
        assertEquals(100.0, numOf(only?.get("t")), 0.0)
    }

    // ── 组二：deleted 只增不减 + 删除戳落地 ──────────────────────────────────

    /** 删除记录两边并集（逐键取更晚），不同键只增不减。 */
    @Test
    fun deletedRecordsMergeUnion() {
        val local = doc(100, "deleted" to buildJsonObject { put("gymCards", buildJsonObject { put("a", 100L) }) })
        val remote = doc(200, "deleted" to buildJsonObject { put("gymCards", buildJsonObject { put("b", 200L) }) })
        val merged = StateMerge.mergeStates(local, remote)
        val del = stampMapOf(merged, "deleted")?.let { stampMapOf(it, "gymCards") }
        assertEquals(100.0, numOf(del?.get("a")), 0.0)
        assertEquals(200.0, numOf(del?.get("b")), 0.0)
    }

    /** 删除戳落地：戳 ≥ 条目最后编辑即移除（他端已删，本端条目不再合并回来——防复活）。 */
    @Test
    fun deletionStampRemovesEntry() {
        val local = doc(550, "deleted" to buildJsonObject { put("gymCards", buildJsonObject { put("c1", 500L) }) })
        val remote = doc(600, "gymCards" to kotlinx.serialization.json.JsonArray(listOf(entry("c1", 100, "他端卡"))))
        val merged = StateMerge.mergeStates(local, remote)
        assertTrue("删除戳应移除他端条目", arrOf(merged, "gymCards").isEmpty())
        // 记录本身保留（stamps 服务端重跑要有账可查）
        assertEquals(500.0, numOf(stampMapOf(stampMapOf(merged, "deleted")!!, "gymCards")?.get("c1")), 0.0)
    }

    /** 删除后又编辑保留：条目时间晚于删除戳 → 该条目不删（他端删了旧版，本端新版留着）。 */
    @Test
    fun entryEditedAfterDeletionSurvives() {
        val local = doc(550, "deleted" to buildJsonObject { put("gymCards", buildJsonObject { put("c9", 500L) }) })
        val remote = doc(600, "gymCards" to kotlinx.serialization.json.JsonArray(listOf(entry("c9", 800, "删后新版"))))
        val merged = StateMerge.mergeStates(local, remote)
        assertEquals(listOf("c9"), arrOf(merged, "gymCards").map(::idOf))
    }

    /** laterDel 平局负值（加回）胜：+100 vs -100 取 -100，条目不删。 */
    @Test
    fun deletionTieNegativeRestoreWins() {
        val local = doc(
            100,
            "deleted" to buildJsonObject { put("gymCards", buildJsonObject { put("x", -100L) }) },
            "gymCards" to kotlinx.serialization.json.JsonArray(listOf(entry("x", 5, "加回卡"))),
        )
        val remote = doc(
            200,
            "deleted" to buildJsonObject { put("gymCards", buildJsonObject { put("x", 100L) }) },
            "gymCards" to kotlinx.serialization.json.JsonArray(listOf(entry("x", 5, "加回卡"))),
        )
        val merged = StateMerge.mergeStates(local, remote)
        val del = stampMapOf(merged, "deleted")?.let { stampMapOf(it, "gymCards") }
        assertEquals(-100.0, numOf(del?.get("x")), 0.0)
        assertEquals(listOf("x"), arrOf(merged, "gymCards").map(::idOf))
    }

    // ── 组三：edited 只增不减 + 按戳取值 ─────────────────────────────────────

    /** 编辑记录并集；值按戳更晚一侧取（点分键除外，见下）。 */
    @Test
    fun editedRecordsUnionValueByLaterStamp() {
        // uiPref = 任意非 OWN_MERGE/PER_KEY 的普通设置键（OWN_MERGE 收录协议结构键）
        val local = doc(
            2000,
            "edited" to buildJsonObject { put("uiPref", 300L) },
            "uiPref" to JsonPrimitive("compact"),
        )
        val remote = doc(
            1000,
            "edited" to buildJsonObject { put("uiPref", 100L); put("theme", 150L) },
            "theme" to JsonPrimitive("dark"),
        )
        val merged = StateMerge.mergeStates(local, remote)
        // local 戳 300 > remote 100 → uiPref 取 local 值；theme 仅 remote 有戳 → 取 remote 值
        assertEquals("compact", StateDoc.strOf(merged["uiPref"]))
        assertEquals("dark", StateDoc.strOf(merged["theme"]))
        // 记录两边并集
        val edited = stampMapOf(merged, "edited")!!
        assertEquals(300.0, numOf(edited["uiPref"]), 0.0)
        assertEquals(150.0, numOf(edited["theme"]), 0.0)
    }

    /** OWN_MERGE 协议结构键（unit 等）不吃 edited 取值——记录照并，值不动。 */
    @Test
    fun editedSkipsOwnMergeKeys() {
        val local = doc(
            2000,
            "edited" to buildJsonObject { put("unit", 300L) },
            "unit" to JsonPrimitive("kg"),
        )
        val remote = doc(
            1000,
            "edited" to buildJsonObject { put("unit", 100L) },
            "unit" to JsonPrimitive("lb"),
        )
        val merged = StateMerge.mergeStates(local, remote)
        assertEquals("kg", StateDoc.strOf(merged["unit"]))   // 值不被 edited 改写
        assertEquals(300.0, numOf(stampMapOf(merged, "edited")?.get("unit")), 0.0)   // 记录照并
    }

    // ── 组四：余部透传与文档级盖章 ───────────────────────────────────────────

    /** 余部透传：新副本的无戳字段（settings/未知键）原样进合并档；仅旧副本持有的余部键被弃（新副本决定）。 */
    @Test
    fun remainderPassesThroughFromNewerCopy() {
        val local = doc(
            100,
            "unit" to JsonPrimitive("kg"),
            "queue" to kotlinx.serialization.json.JsonArray(emptyList()),
        )
        val remote = doc(
            200,
            "settings" to buildJsonObject { put("lang", "zh") },
            "customThing" to JsonPrimitive("keep-me"),
        )
        val merged = StateMerge.mergeStates(local, remote)
        assertTrue(stampMapOf(merged, "settings")?.get("lang") != null)
        assertEquals("keep-me", StateDoc.strOf(merged["customThing"]))
        // 新副本决定无戳字段：只在新副本（local 此处为旧）侧的余部键不并入
        assertFalse(merged.containsKey("unit"))
        assertFalse(merged.containsKey("queue"))
    }

    /** 文档 _ts = 两边最晚；服务端只读盖章（_rev/_wid/_wids/_prior）剥除重盖。 */
    @Test
    fun docTsIsMaxAndServerStampsStripped() {
        val local = doc(100)
        val remote = buildJsonObject {
            put("_ts", 200)
            put("_rev", 5)
            put("_wid", "abc")
            put("_wids", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
            put("_prior", buildJsonObject { put("x", 1L) })
        }
        val merged = StateMerge.mergeStates(local, remote)
        assertEquals(200L, StateDoc.ts(merged))
        assertFalse(merged.containsKey("_rev"))
        assertFalse(merged.containsKey("_wid"))
        assertFalse(merged.containsKey("_wids"))
        assertFalse(merged.containsKey("_prior"))
    }

    // ── 补充面：favEx 并集 / STAMPED_MAPS 键级 / 空档短路 ────────────────────

    /** favEx 收藏并集（只增）。 */
    @Test
    fun favExUnionOnlyGrows() {
        val local = doc(100, "favEx" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("bench"))))
        val remote = doc(200, "favEx" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("squat"), JsonPrimitive("bench"))))
        val merged = StateMerge.mergeStates(local, remote)
        val favs = arrOf(merged, "favEx").map { StateDoc.strOf(it) }.toSet()
        assertEquals(setOf("squat", "bench"), favs)
    }

    /** STAMPED_MAPS 六映射键级并集，同键条目 _ts 晚者胜；单边独有键保留。 */
    @Test
    fun stampedMapPerKeyLaterTsWins() {
        val local = doc(100, "dumbbells" to buildJsonObject {
            put("db25", buildJsonObject { put("_ts", 100L); put("owned", true) })
        })
        val remote = doc(200, "dumbbells" to buildJsonObject {
            put("db25", buildJsonObject { put("_ts", 200L); put("owned", false) })
            put("db30", buildJsonObject { put("_ts", 50L); put("owned", true) })
        })
        val merged = StateMerge.mergeStates(local, remote)
        val dbs = stampMapOf(merged, "dumbbells")!!
        assertEquals("false", (StateDoc.objOf(dbs["db25"]) as JsonObject)["owned"].let { (it as JsonPrimitive).content })
        assertEquals(200.0, numOf(StateDoc.objOf(dbs["db25"])?.get("_ts")), 0.0)   // 键级 _ts 晚者胜
        assertTrue(dbs.containsKey("db30"))   // 单边独有键保留
    }

    /** 空档短路：真空档直接采纳服务端档；仅立了基座（只有 _ts）的近空档合并结果亦与服务端一致。 */
    @Test
    fun emptyLocalShortCircuitsToRemote() {
        val remote = doc(200, "workouts" to kotlinx.serialization.json.JsonArray(listOf(workout("w1", 100, "push"))))
        assertEquals(remote, StateMerge.mergeStates(kotlinx.serialization.json.JsonObject(emptyMap()), remote))
        assertEquals(remote, StateMerge.mergeStates(buildJsonObject { put("_ts", 0) }, remote))
    }
}
