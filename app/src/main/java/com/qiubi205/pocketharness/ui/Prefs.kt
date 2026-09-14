package com.qiubi205.pocketharness.ui

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** 配置持久化：LLM 端点 + key + 模型名（默认智谱开放平台 glm-5.3-flash） */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("pocketharness", Context.MODE_PRIVATE)

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

    fun summary(): JSONObject = JSONObject()
        .put("base_url", baseUrl)
        .put("model", model)
        .put("has_key", apiKey.isNotBlank())
}
