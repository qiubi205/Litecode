package com.qiubi205.pocketharness.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.qiubi205.pocketharness.a11y.HarnessAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机操作工具集：基于无障碍服务的感知与动作。
 * 命名对齐 Android 无障碍语义，参数全部来自模型 tool call。
 */
object DeviceTools {

    /** dispatch 需要 Context 的操作（open_app/剪贴板）由 AgentEngine 传入 */
    lateinit var appContext: Context

    /** 停止协作标志：AgentEngine.cancel() 置位，wait 系列长阻塞提前退出 */
    @Volatile var cancelled = false

    // ---------- 执行 ----------

    fun dispatch(name: String, args: JSONObject): JSONObject {
        // 不依赖无障碍的工具先分发
        when (name) {
            "open_app" -> return openApp(args.optString("package_name", ""))
            "clipboard_read" -> return clipboardRead()
            "clipboard_write" -> return clipboardWrite(args.getString("text"))
            "wait" -> return waitSeconds(args.getDouble("seconds"))
            "wait_for_text" -> return waitForText(
                args.getString("text"),
                args.optDouble("timeout_seconds", 15.0))
        }
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

    // ---------- 新工具：应用拉起 / 剪贴板 / 等待 ----------

    private fun openApp(pkg: String): JSONObject {
        if (pkg.isBlank()) return JSONObject().put("error", "缺少 package_name")
        return try {
            val pm = appContext.packageManager
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                JSONObject().put("error", "未安装应用 $pkg（可用 list_files 确认，或让用户手动安装）")
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
                JSONObject().put("ok", true).put("opened", pkg)
            }
        } catch (e: Exception) {
            JSONObject().put("error", "open_app 失败: ${e.message}")
        }
    }

    private fun clipboardRead(): JSONObject {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = cm.primaryClip
        val text = if (item != null && item.itemCount > 0) item.getItemAt(0)?.coerceToText(appContext)?.toString() else null
        return if (text.isNullOrEmpty()) JSONObject().put("ok", true).put("text", "")
        else JSONObject().put("ok", true).put("text", text.take(20_000))
    }

    private fun clipboardWrite(text: String): JSONObject {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("pocketharness", text))
        return JSONObject().put("ok", true).put("written", text.length)
    }

    private fun waitSeconds(seconds: Double): JSONObject {
        val ms = (seconds * 1000).toLong().coerceIn(0, 60_000)
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cancelled) return JSONObject().put("ok", false).put("interrupted", true)
            try { Thread.sleep(minOf(200L, end - System.currentTimeMillis())) } catch (e: InterruptedException) {
                return JSONObject().put("ok", false).put("interrupted", true)
            }
        }
        return JSONObject().put("ok", true).put("waited_ms", ms)
    }

    private fun waitForText(text: String, timeoutSeconds: Double): JSONObject {
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000).toLong().coerceIn(500, 120_000)
        while (System.currentTimeMillis() < deadline) {
            if (cancelled) return JSONObject().put("ok", false).put("interrupted", true)
            val a11y = HarnessAccessibilityService.instance()
                ?: return JSONObject().put("error", "无障碍服务未启用")
            val tree = a11y.dumpWindowTree()
            if (tree.contains(text)) {
                return JSONObject().put("ok", true).put("found", true).put("elapsed_ms", deadline - System.currentTimeMillis())
            }
            try { Thread.sleep(500) } catch (e: InterruptedException) {
                return JSONObject().put("ok", false).put("interrupted", true)
            }
        }
        return JSONObject().put("ok", false).put("found", false)
            .put("hint", "超时未出现「$text」，可能需要滑动或界面未加载")
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
        .put(fn("open_app", "按包名直接启动应用（如 com.tencent.mm 微信），比手动导航快得多", JSONObject()
            .put("package_name", JSONObject().put("type", "string").put("description", "Android 包名，如 com.tencent.mm"))))
        .put(fn("clipboard_read", "读取系统剪贴板文本", JSONObject()))
        .put(fn("clipboard_write", "把文本写入系统剪贴板（长文本输入建议剪贴板+粘贴，比 input_text 稳）", JSONObject()
            .put("text", JSONObject().put("type", "string"))))
        .put(fn("wait", "等待 N 秒（页面加载/动画时用，别反复 get_screen 干烧轮次）", JSONObject()
            .put("seconds", JSONObject().put("type", "number").put("description", "等待秒数，上限 60"))))
        .put(fn("wait_for_text", "轮询等待某段文本出现在屏幕上（每0.5s检查一次）", JSONObject()
            .put("text", JSONObject().put("type", "string").put("description", "要等待出现的屏幕文本"))
            .put("timeout_seconds", JSONObject().put("type", "number").put("description", "超时秒数，上限 120，默认 15"))))

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
