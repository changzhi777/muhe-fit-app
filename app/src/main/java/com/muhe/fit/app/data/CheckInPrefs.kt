package com.muhe.fit.app.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// ── 打卡屏本地偏好（M2） ─────────────────────────────────────────────────────
// lastGymCardId 记忆：卡轨重开在最后一次用的卡上（CheckIn.jsx useLayoutEffect 语义）——
// 两家健身房的人不用每次划卡找码。载体 = SharedPreferences（net/ServerConfig 同款模式；
// DataStore 不在依赖白名单）。JS 侧该键在 state 文档里随档同步；Android 首版只存本机
// （选卡记忆属设备局部体验，不入协议文档），差异注记于此。
@Singleton
class CheckInPrefs @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("muhe_checkin", Context.MODE_PRIVATE)

    /** 最后使用的会员卡 id；null = 从未选卡（首开落第一张）。 */
    var lastGymCardId: String?
        get() = prefs.getString(KEY_LAST_CARD, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_LAST_CARD).apply()
            else prefs.edit().putString(KEY_LAST_CARD, value).apply()
        }

    private companion object {
        const val KEY_LAST_CARD = "last_gym_card_id"
    }
}
