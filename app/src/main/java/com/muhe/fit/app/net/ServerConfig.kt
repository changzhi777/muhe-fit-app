package com.muhe.fit.app.net

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// ── 服务器地址配置（登录页折叠服务器栏 M1 后续批次接此配置） ─────────────────
// 语义对照：ck/frontend/src/lib/api.js 的 remoteBase —— 配对手机带一个绝对地址自己的
// 服务器（Settings → Pair the mobile app 输入）；此处即该地址的 Android 侧存放点。
@Singleton
class ServerConfig @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("muhe_server", Context.MODE_PRIVATE)

    /**
     * baseUrl，带尾斜杠（Retrofit 要求 baseUrl 以 / 结尾）。
     * 默认指向官方实例；自托管用户经登录页服务器栏覆盖（后续批次落 UI）。
     */
    var baseUrl: String = normalize(prefs.getString(KEY_BASE_URL, null) ?: DEFAULT_BASE_URL)
        private set

    /**
     * 更新服务器地址并持久化。地址变更在会话层是重配对事件：token 属于旧服务器，
     * 调用方应同时 TokenStore.clear()。
     *
     * ⚠️ M1 批次注记：Retrofit 实例在 Hilt 首次 provision 时读取本值，进程内改址不重建
     * 网络栈——登录批次接入服务器栏时，改址后走「持久化 + 提示重启」或届时再引入
     * Retrofit 重建机制（见 NetModule 注释）。
     */
    fun setBaseUrl(url: String) {
        baseUrl = normalize(url)
        prefs.edit().putString(KEY_BASE_URL, baseUrl).apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"

        /** 默认实例地址（裁定：登录页服务器栏可覆盖；自托管场景填内网地址）。 */
        const val DEFAULT_BASE_URL = "https://mufit.qingmulife.com"

        // 尾斜杠归一：无 scheme 的地址按 https:// 补（对照前端 api.js whyUnreachable 的 #428 处理：
        // 家庭服务器常是纯 http，UI 批次应允许显式 http:// 前缀，此处仅兜底 scheme 缺失）。
        // 空串/坏值回退默认实例：normalize 产物直接喂 Retrofit baseUrl，"//" 会在 Hilt 首次
        // provision 抛 IllegalArgumentException 且每次启动必崩（持久化的是坏值），必须兜底。
        fun normalize(url: String): String {
            var u = url.trim().trimEnd('/')
            if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            if (u.isEmpty()) u = DEFAULT_BASE_URL
            return "$u/"
        }
    }
}
