package com.qiubi205.litecode.ui

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** 配置持久化：LLM 端点 + key + 模型名（默认智谱开放平台 glm-5.3-flash） */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("litecode", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", "https://open.bigmodel.cn/api/paas/v4/")!!
        set(v) = sp.edit().putString("base_url", v).apply()

    var apiKey: String
        get() = sp.getString("api_key", "")!!
        set(v) = sp.edit().putString("api_key", v).apply()

    var model: String
        get() = sp.getString("model", "glm-5.3-flash")!!
        set(v) = sp.edit().putString("model", v).apply()

    var maxToolRounds: Int
        get() = sp.getInt("max_tool_rounds", 25)
        set(v) = sp.edit().putInt("max_tool_rounds", v).apply()

    var temperature: Double
        get() = java.lang.Double.longBitsToDouble(
            sp.getLong("temperature", java.lang.Double.doubleToRawLongBits(0.7)))
        set(v) = sp.edit().putLong("temperature", java.lang.Double.doubleToRawLongBits(v)).apply()

    var topP: Double
        get() = java.lang.Double.longBitsToDouble(
            sp.getLong("top_p", java.lang.Double.doubleToRawLongBits(0.9)))
        set(v) = sp.edit().putLong("top_p", java.lang.Double.doubleToRawLongBits(v)).apply()

    /** "" = 服务端默认；low/medium/high */
    var thinkingBudget: String
        get() = sp.getString("thinking_budget", "")!!
        set(v) = sp.edit().putString("thinking_budget", v).apply()

    var streamEnabled: Boolean
        get() = sp.getBoolean("stream_enabled", true)
        set(v) = sp.edit().putBoolean("stream_enabled", v).apply()

    /** 伪流式播放速度（字符/秒） */
    var playSpeedCps: Int
        get() = sp.getInt("play_speed_cps", 100)
        set(v) = sp.edit().putInt("play_speed_cps", v).apply()

    fun summary(): JSONObject = JSONObject()
        .put("base_url", baseUrl)
        .put("model", model)
        .put("has_key", apiKey.isNotBlank())
}
