package com.qiubi205.pocketharness.tools

import com.qiubi205.pocketharness.a11y.HarnessAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机操作工具集：基于无障碍服务的感知与动作。
 * 命名对齐 Android 无障碍语义，参数全部来自模型 tool call。
 */
object DeviceTools {

    // ---------- 执行 ----------

    fun dispatch(name: String, args: JSONObject): JSONObject {
        val a11y = HarnessAccessibilityService.instance()
            ?: return JSONObject().put("error", "无障碍服务未启用。请到系统设置开启 PocketHarness 的无障碍服务。")
        return try {
            when (name) {
                "get_screen" -> getScreen(a11y)
                "tap" -> ok(a11y.tap(args.getDouble("x").toFloat(), args.getDouble("y").toFloat()))
                "swipe" -> ok(a11y.swipe(
                    args.getDouble("x1").toFloat(), args.getDouble("y1").toFloat(),
                    args.getDouble("x2").toFloat(), args.getDouble("y2").toFloat(),
                    args.optDouble("duration_ms", 300.0).toLong().coerceIn(100, 10_000)))
                "input_text" -> ok(a11y.inputText(
                    args.getString("text"),
                    args.optDouble("x", Double.NaN).takeUnless { it.isNaN() }?.toFloat(),
                    args.optDouble("y", Double.NaN).takeUnless { it.isNaN() }?.toFloat()))
                "press_back" -> ok(a11y.back())
                "press_home" -> ok(a11y.home())
                "open_notifications" -> ok(a11y.notifications())
                else -> JSONObject().put("error", "未知工具: $name")
            }
        } catch (e: Exception) {
            JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun getScreen(a11y: HarnessAccessibilityService): JSONObject =
        JSONObject().put("screen_tree", a11y.dumpWindowTree())

    private fun ok(success: Boolean): JSONObject =
        if (success) JSONObject().put("ok", true)
        else JSONObject().put("error", "动作执行失败（目标可能已消失）")

    // ---------- OpenAI function 定义 ----------

    val definitions: JSONArray = JSONArray()
        .put(fn("get_screen", "获取当前屏幕的控件树（元素文本、id、坐标中心点、是否可点击/输入）",
            JSONObject()))
        .put(fn("tap", "点击屏幕坐标", JSONObject()
            .put("x", JSONObject().put("type", "number"))
            .put("y", JSONObject().put("type", "number"))))
        .put(fn("swipe", "从一点滑动到另一点", JSONObject()
            .put("x1", JSONObject().put("type", "number"))
            .put("y1", JSONObject().put("type", "number"))
            .put("x2", JSONObject().put("type", "number"))
            .put("y2", JSONObject().put("type", "number"))
            .put("duration_ms", JSONObject().put("type", "number"))))
        .put(fn("input_text", "向输入框写入文本（可先 tap 输入框，或直接给坐标）", JSONObject()
            .put("text", JSONObject().put("type", "string"))
            .put("x", JSONObject().put("type", "number"))
            .put("y", JSONObject().put("type", "number"))))
        .put(fn("press_back", "按返回键", JSONObject()))
        .put(fn("press_home", "回到桌面", JSONObject()))
        .put(fn("open_notifications", "下拉打开通知栏", JSONObject()))

    private fun fn(name: String, desc: String, params: JSONObject): JSONObject {
        val required = JSONArray()
        // 简单规则：params 里除 x/y 可选外都必填（input_text 的 text 必填）
        val keys = params.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (name == "input_text" && (k == "x" || k == "y")) continue
            if (name == "swipe" && k == "duration_ms") continue
            required.put(k)
        }
        return JSONObject().put("type", "function")
            .put("function", JSONObject()
                .put("name", name).put("description", desc)
                .put("parameters", JSONObject()
                    .put("type", "object").put("properties", params)
                    .put("required", required)))
    }
}
