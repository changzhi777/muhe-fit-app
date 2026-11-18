package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * session-noprog.js 全量移植——整节会话「不计入渐进」（训练头 ⋮，伤停日整场排除）。
 *
 * 移植自 ck/frontend/src/lib/session-noprog.js（断言语义校准：session-noprog.test.js）。
 * 条目级 noProg 是下游唯一读取面（落盘 keep 逐条 flag、history.js entryExcluded 读过去、
 * 下一处方跳过）；active.noProg 只记「整场选择」本身，落在运行会话上，落盘形态不带它
 * （buildCompletedWorkout 从条目重建）。
 * 语义差异注记：JS 侧 setSessionNoProg / setEntryNoProg 就地变异 active（store update 内用），
 * Kotlin JsonObject 不可变 → 纯函数化，返回新档由调用侧替换；键位/删位语义逐行对齐 JS。
 */
object SessionNoprog {

    // session-noprog.js:15 list——非数组归空
    private fun list(active: JsonObject?): List<JsonObject> {
        val raw = active?.arr("entries") ?: return emptyList()
        return raw.mapNotNull { it as? JsonObject }
    }

    /** session-noprog.js:17 sessionNoProg——整场是否被排除（严格 true） */
    fun sessionNoProg(active: JsonObject?): Boolean = active?.bool("noProg") == true

    /**
     * session-noprog.js:25-34 setSessionNoProg——整场排除开/关。开：active.noProg = true 且
     * 每条目 noProg = true。关：删 active.noProg，条目逐个删 noProg——deload/康复计划自持的
     * （routineOwns，即计划设置）除外，同动作菜单口径。active 为 null 时不动（返回 null）。
     */
    fun setSessionNoProg(active: JsonObject?, on: Boolean, routineOwns: (JsonObject) -> Boolean = { false }): JsonObject? {
        if (active == null) return null
        if (on) {
            var out = Js.with(active, "noProg", JsonPrimitive(true))
            val raw = out.arr("entries")
            if (raw != null) {
                val mapped = raw.map { e ->
                    val eo = e as? JsonObject
                    if (eo != null) Js.with(eo, "noProg", JsonPrimitive(true)) else e
                }
                out = Js.with(out, "entries", JsonArray(mapped))
            }
            return out
        }
        var out = Js.without(active, "noProg")
        val raw = out.arr("entries")
        if (raw != null) {
            val mapped = raw.map { e ->
                val eo = e as? JsonObject
                if (eo != null && !routineOwns(eo)) Js.without(eo, "noProg") else e
            }
            out = Js.with(out, "entries", JsonArray(mapped))
        }
        return out
    }

    /**
     * session-noprog.js:41-50 setEntryNoProg——单动作排除/重新计数（动作 ⋯ 菜单或标记 Undo）。
     * 重新计数一条 = 整场选择随之结束（删 active.noProg，头部不再显示整场排除，之后加入的
     * 动作也计数），其余条目保持排除。index 无条目（越界/非对象）时不动。
     */
    fun setEntryNoProg(active: JsonObject?, index: Int, on: Boolean): JsonObject? {
        val entries = active?.arr("entries") ?: return active
        val entry = entries.getOrNull(index) as? JsonObject ?: return active
        return if (on) {
            val mapped = entries.mapIndexed { i, e -> if (i == index) Js.with(entry, "noProg", JsonPrimitive(true)) else e }
            Js.with(active, "entries", JsonArray(mapped))
        } else {
            val mapped = entries.mapIndexed { i, e -> if (i == index) Js.without(entry, "noProg") else e }
            Js.with(Js.without(active, "noProg"), "entries", JsonArray(mapped))
        }
    }

    /**
     * session-noprog.js:53 joinSessionNoProg——新条目（后加动作或带入计划）在整场排除时会话里
     * 一并排除；否则原样返回（引用不动，对应 JS 直接返回 entry 本体）。
     */
    fun joinSessionNoProg(active: JsonObject?, entry: JsonObject): JsonObject =
        if (sessionNoProg(active)) Js.with(entry, "noProg", JsonPrimitive(true)) else entry

    /** 供遍历真实条目列表的调用方使用（对应 JS list(active?.entries) 语义：非数组/缺失 → 空） */
    fun entriesOf(active: JsonObject?): List<JsonObject> = list(active)
}
