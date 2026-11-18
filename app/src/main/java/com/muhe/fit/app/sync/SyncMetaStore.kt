package com.muhe.fit.app.sync

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

// ── 文档级同步元数据存储（MuheDatabase.kt 预留的 M1 裁定落点） ────────────────
// MuheDatabase 头注释：「本表不含文档级元数据（_rev / deleted.* / edited.* / unitSet 等
// settings 键）——存储载体由 M1 网络层批次裁定」。本文件即该裁定：**SharedPreferences
// 承载文档级余部（remainder）+ 乐观并发基座**，Room 八表只管条目数组。
//
// remainder = 整档减八类条目数组（StateDoc.split）：_ts/_rev/_wid/_wids、deleted/edited、
// unit/week/queue/settings… 全在里面。拉取时整块替换，上传时与 Room 条目重新合档
// （StateDoc.assemble）。DataStore 不在依赖白名单，SharedPreferences 对该量级（一个
// profile 的 settings 块）足够；M2 若需要可迁移，接口不变。
@Singleton
class SyncMetaStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("muhe_sync", Context.MODE_PRIVATE)

    // 余部读-改-写互斥（2026-11-18 修复）：remainder 是单键整串 RMW——UI 写路径（recordDeletion/
    // recordEdit）与同步 writeThrough 的 setRemainder 并发交错会把刚拉取的余部整块回滚为旧档。
    // SharedPreferences 单键写原子但跨「读-改-写」不复原子，临界区极短，synchronized 足够。
    private val lock = Any()

    // ── 乐观并发基座（useStore.js base {rev, ts, wid} 的 Android 半） ──
    // baseRev = 本端副本所依据的服务端版本（下次推送作为 baseRev 回传，条件写）；
    // null = 尚未与服务器对过版（先拉再推）。
    var baseRev: Long?
        get() = prefs.getLong(KEY_REV, -1).takeIf { it >= 0 }
        set(v) { setLong(KEY_REV, v) }

    // baseWid = 该版本对应的写入 id（rev 在服务器时间回退后复用时的区分符，契约 PUT baseWid）
    var baseWid: String?
        get() = prefs.getString(KEY_WID, null)
        set(v) { setString(KEY_WID, v) }

    // lastSyncTs = 最近一次确认同步时的文档 _ts（是否欠推送的判定基线，useStore.js base.ts 语义）
    var lastSyncTs: Long
        get() = prefs.getLong(KEY_SYNC_TS, 0)
        set(v) { prefs.edit().putLong(KEY_SYNC_TS, v).apply() }

    // ── 文档级余部（remainder） ─────────────────────────────────────────────
    fun remainder(): JsonObject = synchronized(lock) {
        prefs.getString(KEY_REMAINDER, null)?.let { StateDoc.parse(json, it) } ?: JsonObject(emptyMap())
    }

    fun setRemainder(doc: JsonObject) {
        synchronized(lock) { setString(KEY_REMAINDER, doc.toString()) }
    }

    /** 文档 _ts（余部内 `_ts` 键）；本地改动盖章/对账共用。 */
    fun docTs(): Long = StateDoc.ts(remainder())

    // ── 本地改动盖章（同步层职责——各 Dao 注释「删除戳由同步层负责」的落点） ──
    // 语义对齐 sync-merge.js stampDeletions:541「删除戳永不早于条目自身时间」：
    // at = max(now, entryTime+1)。M2 UI 批次调 deleteById 前先调本方法记戳。

    /** 记删除戳（deleted.<field>.<key> = at）；防合并复活。与既有戳取 max——戳只增不减，
     *  本地时钟回退不得覆盖更晚的既有戳（否则 laterDel 不胜 → 删除保护回退/条目复活）。 */
    fun recordDeletion(field: String, key: String, entryTime: Long, now: Long) {
        synchronized(lock) {
            val rem = remainder().asMutable()
            val del = StateDoc.objOf(rem["deleted"])?.asMutable() ?: LinkedHashMap()
            val m = StateDoc.objOf(del[field])?.asMutable() ?: LinkedHashMap()
            val existing = StateDoc.longOf(JsonObject(m)[key])
            m[key] = JsonPrimitive(maxOf(now, entryTime + 1, existing))
            del[field] = JsonObject(m)
            rem["deleted"] = JsonObject(del)
            stampDocChange(rem, now)
        }
    }

    /** 记设置/计划日编辑戳（edited.<key> = now），对齐 stampEdits（sync-merge.js:636）。
     *  同取 max：合并进来的远程更晚戳不得被本地更早时钟覆盖。 */
    fun recordEdit(key: String, now: Long) {
        synchronized(lock) {
            val rem = remainder().asMutable()
            val ed = StateDoc.objOf(rem["edited"])?.asMutable() ?: LinkedHashMap()
            val existing = StateDoc.longOf(JsonObject(ed)[key])
            ed[key] = JsonPrimitive(maxOf(now, existing))
            rem["edited"] = JsonObject(ed)
            stampDocChange(rem, now)
        }
    }

    /** 文档 _ts 推进（对齐 useStore.js persist:571：max(now, 自身+1)——时钟回拨不倒退）。 */
    fun stampDocChange(rem: MutableMap<String, kotlinx.serialization.json.JsonElement>, now: Long) {
        rem["_ts"] = JsonPrimitive(maxOf(now, StateDoc.ts(JsonObject(rem)) + 1))
        setRemainder(JsonObject(rem))
    }

    /** 清空（换账号/登出：本地条目是下一账号或访客的世界，元数据必须跟着清）。 */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun setLong(k: String, v: Long?) {
        if (v == null) prefs.edit().remove(k).apply() else prefs.edit().putLong(k, v).apply()
    }

    private fun setString(k: String, v: String?) {
        if (v == null) prefs.edit().remove(k).apply() else prefs.edit().putString(k, v).apply()
    }

    private fun JsonObject.asMutable(): LinkedHashMap<String, kotlinx.serialization.json.JsonElement> =
        LinkedHashMap(this)

    private companion object {
        const val KEY_REV = "base_rev"
        const val KEY_WID = "base_wid"
        const val KEY_SYNC_TS = "last_sync_ts"
        const val KEY_REMAINDER = "remainder_json"
    }
}
