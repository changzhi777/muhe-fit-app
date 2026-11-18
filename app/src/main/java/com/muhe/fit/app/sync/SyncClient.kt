package com.muhe.fit.app.sync

import androidx.room.withTransaction
import com.muhe.fit.app.data.MuheDatabase
import com.muhe.fit.app.data.TokenStore
import com.muhe.fit.app.net.MuheApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// ── 同步协议客户端半（M1 核心编排） ─────────────────────────────────────────
// 语义真源：
//   ck/frontend/src/lib/api.js（唯一后端通信层：超时/错误语义）
//   ck/frontend/src/store/useStore.js（checkRev:647 / pullState / doPush:705 —— rev 先比再拉、
//   409 合并重推 ≤2 次、baseRev/baseWid 条件写、stamped:true 自盖章）
//   ck/backend-go/internal/data/stamps.go（服务端半盖章合并；客户端合并输出喂给它重跑）
// 本地载体：Room 八表（条目数组，raw 透传）+ SyncMetaStore（文档级余部与乐观并发基座）。
@Singleton
class SyncClient @Inject constructor(
    private val api: MuheApi,
    private val db: MuheDatabase,
    private val tokens: TokenStore,
    private val meta: SyncMetaStore,
    private val json: Json,
) {

    /** 一步到位：版本变了就拉，本地欠账就推（前台恢复/周期轮询入口，useStore checkRev+poll 语义）。 */
    suspend fun syncNow(): SyncOutcome {
        if (tokens.get() == null) return SyncOutcome.GuestSkip   // 访客态一切留本地，从不触网
        when (val pulled = pullIfChanged()) {
            is SyncOutcome.Failed -> return pulled
            else -> {}
        }
        return pushIfOwed()
    }

    /**
     * GET /api/data/rev 先比版本，rev/wid 变了才拉全档（useStore.js checkRev:647：
     * `rev !== base.rev || (base.wid && wid && wid !== base.wid)` → pull）。
     * 从未对过版（baseRev 空）直接建基座。
     */
    suspend fun pullIfChanged(): SyncOutcome {
        if (tokens.get() == null) return SyncOutcome.GuestSkip
        val base = meta.baseRev ?: return pull()
        return try {
            val r = api.getDataRev()
            val widMoved = meta.baseWid != null && r.wid != null && r.wid != meta.baseWid
            if (r.rev == base && !widMoved) SyncOutcome.UpToDate(r.rev) else pull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncOutcome.Failed(toError(e))
        }
    }

    /**
     * GET /api/data 全档拉取。state 为 null（服务端还没有档）只立基座、本地条目等推送
     * （契约：rev 0 = nothing synced yet）；有档则合并落地。
     */
    suspend fun pull(): SyncOutcome {
        if (tokens.get() == null) return SyncOutcome.GuestSkip
        return try {
            val r = api.getData()
            val state = StateDoc.objOf(r.state)
            if (state == null) {
                meta.baseRev = r.rev
                return SyncOutcome.Pulled(r.rev)
            }
            // 拉取侧也走条目级合并：本端独有条目保留、服务端已删条目经 deleted 戳移除
            // （前端无欠账时整档采纳，合并结果在无欠账时与服务端档一致；M1 无本地 UI 写入面，
            // 合并口径安全且不丢欠账）。writeSync(rev, ts, remote._wid) → 基座从文档盖章读 wid
            // （GET /api/data 响应体不带 wid，契约只给 state/rev）。
            val merged = StateMerge.mergeStates(buildLocalDoc(), state)
            writeThrough(merged)
            meta.baseRev = r.rev
            meta.baseWid = StateDoc.wid(state)
            meta.lastSyncTs = StateDoc.ts(merged)
            SyncOutcome.Pulled(r.rev)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncOutcome.Failed(toError(e))
        }
    }

    /**
     * PUT /api/data 整档推送。条件写（baseRev/baseWid）+ stamped:true（自盖章客户端，
     * useStore doPush:715）；409 携带服务端当前档（契约），合并重推 ≤2 次（doPush:750，
     * 第二次仍冲突则留给下次 syncNow 的拉取对账）。
     * 401/413 原样上抛给调用面（前端语义： owed 副本保留，登录批次处理重配对/过大提示）。
     */
    suspend fun push(retries: Int = 0): SyncOutcome {
        if (tokens.get() == null) return SyncOutcome.GuestSkip
        // 基座未立先拉（useStore:613：boot pull 之前推送会被旧副本覆盖服务端）
        if (meta.baseRev == null) {
            when (val pulled = pull()) {
                is SyncOutcome.Failed -> return pulled
                else -> {}
            }
        }
        // buildLocalDoc 读 Room 八表（磁盘满/损坏可抛）——包进与 409 路径同一条护栏
        // （2026-11-18 修复：原 buildLocalDoc 与 errorBody 读取、409 合并重推全裸奔在 try 外）
        try {
            val doc = buildLocalDoc()
            val body = buildJsonObject {
                put("state", doc)
                put("stamped", true)
                meta.baseRev?.let { put("baseRev", it) }
                meta.baseWid?.let { put("baseWid", it) }
            }
            val resp = api.putData(body)
            if (resp.isSuccessful) {
                val r = resp.body() ?: return SyncOutcome.Failed(SyncError(resp.code(), "bad-response", "empty body"))
                // 写档基座更新（useStore writeSync:727）：rev 必有（契约 200 响应），直接立基座
                meta.baseRev = StateDoc.longOf(r["rev"])
                meta.baseWid = StateDoc.strOf(r["wid"])
                meta.lastSyncTs = StateDoc.ts(doc)
                return SyncOutcome.Pushed(meta.baseRev ?: 0, meta.baseWid)
            }
            val errText = try { resp.errorBody()?.string() } catch (e: IOException) { null }
            if (resp.code() == 409 && retries < 2) {
                val conflict = errText?.let { StateDoc.parse(json, it) }
                val remote = StateDoc.objOf(conflict?.get("state"))
                if (conflict != null && remote != null) {
                    // 另一设备写过：并两边的账，按服务端给的版本重推（useStore:750 mergeInto）
                    val merged = StateMerge.mergeStates(buildLocalDoc(), remote)
                    writeThrough(merged)
                    meta.baseRev = StateDoc.longOf(conflict["rev"])
                    meta.baseWid = StateDoc.wid(remote)
                    return push(retries + 1)
                }
            }
            return SyncOutcome.Failed(toError(HttpException(resp), errText))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SyncOutcome.Failed(toError(e))
        }
    }

    /**
     * 欠账判定：本地最高活动（文档 _ts / 条目盖章 / deleted·edited 记录）晚于上次确认同步
     * （useStore.js owes():409 + base.ts 语义的 Android 半；本地写入一律经 SyncMetaStore
     * 盖章，M2 实体写入同步盖条目 _ts）。
     */
    suspend fun pushIfOwed(): SyncOutcome {
        val doc = buildLocalDoc()
        return if (maxStamp(doc) > meta.lastSyncTs) push() else SyncOutcome.UpToDate(meta.baseRev ?: 0)
    }

    // ── 本地档组装：Room 八表 raw 透传 + 文档级余部（组装协议见 StateDoc） ──
    private suspend fun buildLocalDoc(): JsonObject {
        val entries = LinkedHashMap<String, List<JsonObject>>()
        entries["workouts"] = db.workoutDao().getAll().mapNotNull { parseRaw(it.raw) }
        entries["routines"] = db.routineDao().getAll().mapNotNull { parseRaw(it.raw) }
        entries["customEx"] = db.customExDao().getAll().mapNotNull { parseRaw(it.raw) }
        entries["bodyweight"] = db.bodyweightDao().getAll().mapNotNull { parseRaw(it.raw) }
        entries["measurements"] = db.measurementDao().getAll().mapNotNull { parseRaw(it.raw) }
        entries["gymCards"] = db.gymCardDao().getAll().mapNotNull { parseRaw(it.raw) }
        entries["equipProfiles"] = db.equipProfileDao().getAll().mapNotNull { parseRaw(it.raw) }
        entries["attendance"] = db.attendanceDao().getAll().mapNotNull { parseRaw(it.raw) }
        return StateDoc.assemble(meta.remainder(), entries)
    }

    private fun parseRaw(raw: String): JsonObject? =
        try { StateDoc.objOf(json.parseToJsonElement(raw)) } catch (_: Exception) { null }

    // ── 合并/拉取结果落地：Room 八表整表重建 + 余部替换 ──
    // clearAll + upsertAll：REPLACE 只覆盖同键行，服务端已删的行靠整表重建清干净；
    // 合并产物含本端独有条目，重建零丢失。单事务防半档（room-ktx withTransaction）。
    private suspend fun writeThrough(doc: JsonObject) {
        val split = StateDoc.split(doc)
        db.withTransaction {
            db.workoutDao().clearAll()
            db.workoutDao().upsertAll(split.entries["workouts"].orEmpty().map(DocEntityMap::workout))
            db.routineDao().clearAll()
            db.routineDao().upsertAll(split.entries["routines"].orEmpty().map(DocEntityMap::routine))
            db.customExDao().clearAll()
            db.customExDao().upsertAll(split.entries["customEx"].orEmpty().map(DocEntityMap::customEx))
            db.bodyweightDao().clearAll()
            db.bodyweightDao().upsertAll(split.entries["bodyweight"].orEmpty().map(DocEntityMap::bodyweight))
            db.measurementDao().clearAll()
            db.measurementDao().upsertAll(split.entries["measurements"].orEmpty().map(DocEntityMap::measurement))
            db.gymCardDao().clearAll()
            db.gymCardDao().upsertAll(split.entries["gymCards"].orEmpty().map(DocEntityMap::gymCard))
            db.equipProfileDao().clearAll()
            db.equipProfileDao().upsertAll(split.entries["equipProfiles"].orEmpty().map(DocEntityMap::equipProfile))
            db.attendanceDao().clearAll()
            db.attendanceDao().upsertAll(split.entries["attendance"].orEmpty().map(DocEntityMap::attendance))
        }
        meta.setRemainder(split.remainder)
    }

    // 文档级最高活动（StateDoc.highestStamp 的八类补全：bodyweight/measurements 按 t、
    // attendance 按 ts——stamps.go HighestStamp 只扫五类可变条目，欠账判定须覆盖全部八类）
    private fun maxStamp(doc: JsonObject): Long {
        var best = StateDoc.highestStamp(doc)
        for (f in listOf("bodyweight", "measurements", "attendance")) {
            for (e in StateDoc.arrOf(doc[f])) {
                val t = StateDoc.numOf(StateDoc.objOf(e)?.get(if (f == "attendance") "ts" else "t"))
                if (t > best) best = t
            }
        }
        return best.toLong()
    }

    // 错误语义对齐 api.js exchange:93（status 挂上、body 随行）：HTTP 异常带 status，
    // 网络失败无 status（离线，调用面据此显示离线而非服务端错误）
    private fun toError(e: Exception, bodyText: String? = null): SyncError {
        if (e is HttpException) {
            val body = bodyText?.let { StateDoc.parse(json, it) }
            return SyncError(
                status = e.code(),
                code = body?.let { StateDoc.strOf(it["code"]) },
                message = body?.let { StateDoc.strOf(it["error"]) } ?: e.message(),
            )
        }
        if (e is IOException) return SyncError(status = null, code = "offline", message = e.message)
        // 非 JSON 应答（代理页/HTML 劫持）→ 解析异常：与「离线」区分开（ MuheAuthApi redeem 同款防御）
        if (e is SerializationException) return SyncError(status = null, code = "bad-response", message = e.message)
        return SyncError(status = null, code = "sync-error", message = e.message)
    }
}

/** 同步结果（M1 无 UI 挂接，调用面按型分流；401 → 重配对，409 重试耗尽 → 留待下次拉取对账）。 */
sealed class SyncOutcome {
    /** 访客态（无 token）：一切留本地，从不触网（useStore 访客纪律）。 */
    data object GuestSkip : SyncOutcome()

    /** 版本未动 / 无欠账。 */
    data class UpToDate(val rev: Long) : SyncOutcome()

    /** 全档拉取落地。 */
    data class Pulled(val rev: Long) : SyncOutcome()

    /** 推送被接受（rev = 服务端新版本；wid = 本次写入 id）。 */
    data class Pushed(val rev: Long, val wid: String?) : SyncOutcome()

    /** 失败（status null = 网络不可达；401 = token 失效待重配对；413 = 超服务端 5 MiB 上限）。 */
    data class Failed(val error: SyncError) : SyncOutcome()
}

data class SyncError(val status: Int?, val code: String?, val message: String?)
