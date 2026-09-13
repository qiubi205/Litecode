package com.qiubi205.pocketharness.ui

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** 配置持久化：LLM 端点 + key + 模型名 */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("pocketharness", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", "https://api.openai.com/v1")!!
        set(v) = sp.edit().putString("base_url", v).apply()

    var apiKey: String
        get() = sp.getString("api_key", "")!!
        set(v) = sp.edit().putString("api_key", v).apply()

    var model: String
        get() = sp.getString("model", "gpt-4o-mini")!!
        set(v) = sp.edit().putString("model", v).apply()

    var maxToolRounds: Int
        get() = sp.getInt("max_tool_rounds", 25)
        set(v) = sp.edit().putInt("max_tool_rounds", v).apply()

    fun summary(): JSONObject = JSONObject()
        .put("base_url", baseUrl)
        .put("model", model)
        .put("has_key", apiKey.isNotBlank())
}
