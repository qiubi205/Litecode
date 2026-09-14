package com.qiubi205.pocketharness

import android.content.Context
import com.qiubi205.pocketharness.llm.LlmClient
import com.qiubi205.pocketharness.agent.SubAgent
import com.qiubi205.pocketharness.skills.SkillLoader
import com.qiubi205.pocketharness.tools.DeviceTools
import com.qiubi205.pocketharness.tools.FileTools
import com.qiubi205.pocketharness.tools.HttpTools
import com.qiubi205.pocketharness.workspace.Workspace
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Agent 引擎：消息历史 + 工具循环。
 * 模型返回 tool_calls → 本地执行 → 结果回填 → 继续，直到纯文本回复或达到上限。
 */
class AgentEngine(private val context: Context) {

    private val client = LlmClient("", "", "")
    private val history = mutableListOf<LlmClient.Message>()

    /** 停止协作标志：cancel() 置 true，工具循环在安全点检查并退出 */
    @Volatile private var cancelled = false

    init {
        DeviceTools.appContext = context
    }

    /** UI 停止按钮调用：中断当前任务 */
    fun cancel() {
        cancelled = true
        DeviceTools.cancelled = true
    }

    /** 每轮对话开始时重新读 /sdcard/PocketHarness/MEMORY.md + skills 清单，跨会话记忆即时生效 */
    private fun systemPrompt(): String {
        val sb = StringBuilder(SYSTEM_PROMPT)
        sb.append("\n\n# 当前长期记忆（/sdcard/${Workspace.DIR_NAME}/MEMORY.md 实时内容）\n\n")
        sb.append(Workspace.readMemory())
        val skills = try { SkillLoader.promptBlock() } catch (e: Exception) { "" }
        if (skills.isNotBlank()) sb.append("\n\n").append(skills)
        return sb.toString()
    }

    /** 工具循环上限，防止死循环烧 token（可在设置页调） */
    var maxToolRounds = 25
    /** 每轮回调（UI 线程刷新用） */
    var onEvent: ((String) -> Unit)? = null

    fun configure(url: String, key: String, model: String) = client.updateConfig(url, key, model)

    val messageCount: Int get() = history.size

    /** 用外部加载的历史替换当前上下文（system 位刷新为最新记忆注入） */
    fun replaceHistory(msgs: List<LlmClient.Message>) {
        history.clear()
        history.addAll(msgs)
        val fresh = LlmClient.Message("system", systemPrompt())
        if (history.isNotEmpty() && history[0].role == "system") history[0] = fresh
        else history.add(0, fresh)
    }

    /** 导出当前全部消息（含 system）用于持久化 */
    fun snapshotHistory(): List<LlmClient.Message> = history.toList()

    fun reset() {
        history.clear()
        history.add(LlmClient.Message("system", systemPrompt()))
    }

    /** 异步执行一轮对话；onEvent 依次收到 agent 的可见输出 */
    fun send(userText: String, onDone: (String?) -> Unit) {
        if (history.isEmpty()) reset()
        cancelled = false
        DeviceTools.cancelled = false
        // 每次发消息都刷新 system 提示词，把最新 MEMORY.md 注入进去
        history[0] = LlmClient.Message("system", systemPrompt())
        compactIfNeeded()
        history.add(LlmClient.Message("user", userText))
        thread(name = "agent-loop") {
            try {
                val final = runLoop()
                onDone(final)
            } catch (e: Exception) {
                onDone("⚠️ 引擎错误：${e.message}")
            }
        }
    }

    private fun runLoop(): String? {
        val tools = JSONArray().apply {
            for (i in 0 until DeviceTools.definitions.length()) put(DeviceTools.definitions.get(i))
            for (i in 0 until FileTools.definitions.length()) put(FileTools.definitions.get(i))
            for (i in 0 until HttpTools.definitions.length()) put(HttpTools.definitions.get(i))
            put(spawnAgentDefinition())
        }
        var rounds = 0
        while (rounds < maxToolRounds) {
            rounds++
            val resp = client.chat(history, tools)
            if (cancelled) return "⏹ 已停止当前任务。"

            // 有工具调用：执行并回填
            if (resp.toolCalls.isNotEmpty()) {
                history.add(LlmClient.Message("assistant", resp.content, toolCalls = resp.toolCalls))
                for (tc in resp.toolCalls) {
                    onEvent?.invoke("🔧 $tc.name …")
                    val args = try { JSONObject(tc.argumentsJson.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
                    val result = when (tc.name) {
                        "spawn_agent" -> {
                            onEvent?.invoke("🛰️ 子代理启动：${args.optString("task", "").take(30)}…")
                            val r = SubAgent.run(
                                client,
                                task = args.optString("task", "").ifBlank { "（未提供任务）" },
                                context = args.optString("context", "")
                            ) { ev -> onEvent?.invoke(ev) }
                            onEvent?.invoke("🛰️ 子代理完成")
                            JSONObject().put("ok", true).put("result", r)
                        }
                        "open_file" -> FileTools.openFile(args.optString("path", ""), context)
                        "http_request" -> HttpTools.dispatch(args)
                        "look_at_file" -> {
                            val img = try { FileTools.readImageBase64(args.optString("path", "")) } catch (e: Exception) { null }
                            if (img == null) {
                                JSONObject().put("error", "不是图片或读取失败")
                            } else {
                                onEvent?.invoke("👁 视觉识别中…")
                                val answer = try {
                                    client.chatVision(
                                        args.optString("question", "请详细描述这张图片的内容"),
                                        img.first, img.second)
                                } catch (e: Exception) { null }
                                if (answer != null) JSONObject().put("ok", true).put("answer", answer)
                                else JSONObject().put("error", "视觉识别失败（模型可能不支持图片输入）")
                            }
                        }
                        "list_files" -> FileTools.listFiles(args.optString("path", "."))
                        "read_file" -> FileTools.readFile(args.getString("path"))
                        "write_file" -> FileTools.writeFile(
                            args.getString("path"),
                            args.getString("content"),
                            args.optBoolean("append", false))
                        "delete_file" -> FileTools.trashFile(args.getString("path"))
                        "move_file" -> FileTools.moveFile(args.getString("src"), args.getString("dst"))
                        else -> DeviceTools.dispatch(tc.name, args)
                    }
                    onEvent?.invoke("🔧 ${tc.name} → ${result.optString("ok", result.optString("error", "done")).take(60)}")
                    history.add(LlmClient.Message("tool", result.toString().take(12_000), toolCallId = tc.id, name = tc.name))
                    if (cancelled) return "⏹ 已停止当前任务。"
                }
                continue // 回给模型继续
            }

            // 纯文本：结束
            if (!resp.content.isNullOrBlank()) return withTokens(resp.content, resp.rawUsage)
            return "(模型返回空回复)"
        }
        // 达到轮次上限：不给工具，强制让模型带着已有信息总结收尾
        onEvent?.invoke("⚠️ 已达工具轮次上限（$maxToolRounds），正在总结收尾…")
        history.add(LlmClient.Message("user",
            "（系统）已达工具轮次上限（$maxToolRounds）。不要再调用任何工具，直接根据以上已获取的信息总结：任务进展、已完成步骤、结果、剩余建议。"))
        return try {
            val r = client.chat(history, null)
            withTokens(r.content ?: "（模型未能总结；任务未完成，请拆小步重试）", r.rawUsage)
        } catch (e: Exception) {
            "⚠️ 已达工具轮次上限（$maxToolRounds）且总结失败：${e.message}"
        }
    }

    /** 历史压缩：非 system 消息 >40 条时，旧消息浓缩成一条摘要，保留最近 12 条 */
    private fun compactIfNeeded() {
        val nonSystem = history.drop(1)
        if (nonSystem.size <= 40) return
        val recent = nonSystem.takeLast(12)
        val old = nonSystem.dropLast(12)
        if (old.isEmpty()) return
        val msgs = mutableListOf<LlmClient.Message>()
        msgs.addAll(old)
        msgs.add(LlmClient.Message("user",
            "（系统）请把以上对话史浓缩成要点摘要：保留关键事实、决定、未完成事项、重要路径与数字。直接输出摘要，不要客套。"))
        val summary = try { client.chat(msgs, null).content } catch (e: Exception) { null }
        if (summary.isNullOrBlank()) return  // 压缩失败照常执行
        val sys = history[0]
        history.clear()
        history.add(sys)
        history.add(LlmClient.Message("assistant", "📋 [历史摘要] $summary"))
        history.addAll(recent)
    }

    /** 把 usage 拼进回复文本（callback 签名保持 String?） */
    private fun withTokens(text: String, usage: JSONObject?): String {
        if (usage == null) return text
        val p = usage.optInt("prompt_tokens", 0)
        val c = usage.optInt("completion_tokens", 0)
        return if (p == 0 && c == 0) text else text + "\n\n_[tokens: 输入 $p / 输出 $c]_"
    }

    companion object {
        val SYSTEM_PROMPT = """
你是 PocketHarness，一个运行在用户 Android 手机上的本地代理。你能：

1. 手机操作（无障碍引擎）：get_screen 看屏，tap/swipe/input_text/press_back/press_home 操作。原则：先 get_screen 再动手；坐标用控件树里 <> 标注的中心点；一次只做一步，观察结果再继续；页面加载时用 wait / wait_for_text 等待，别干烧轮次。
2. 文件（/sdcard）：list_files / read_file / write_file / move_file / delete_file，路径相对 /sdcard（如 Download、Documents/xx.txt）。delete_file 是移入回收站（/sdcard/PocketHarness/trash/，可恢复）；清空回收站、不可逆删除仍要先问用户。open_app 可按包名直接拉起应用；clipboard_read/clipboard_write 读写剪贴板（长文本输入用剪贴板+粘贴更稳）。http_request 可直接调 HTTP 接口。
3. 长期记忆：你的工作区在 /sdcard/PocketHarness/（MEMORY.md = 长期记忆，AGENTS.md = 行为守则）。对话开始时若记忆与本任务相关请参考；对话结束前，把值得长期记住的信息（用户偏好/重要结论/路径）用 write_file（append=true）写入 MEMORY.md。记忆在下次对话自动注入你的 system 提示词，跨会话生效。
4. 子代理：复杂任务（多文件整理/长文本处理/批量分析）可调 spawn_agent 派生子代理并行处理。给它清晰独立的 task 和必要 context；子代理只有文件工具、没有手机控制，结果会原样返回给你汇总。适合用来读大量文件、写草稿等重活，别为小事派它。
5. 文件直达：open_file 用系统应用直接打开文件（一步到位，不要手动导航文件管理器）；APK 安装等敏感操作先征得用户确认。look_at_file 直接识别图片内容（需多模态模型），看完后再决定下一步。

行为准则：
- 用户下达任务后，规划最短路径执行，不要反复确认。
- 涉及支付、发送消息给他人、删除数据的动作，先向用户复述并等确认。
- 执行结果如实汇报；失败时读屏诊断原因并尝试一次替代方案。
- 用中文回复，简洁。
        """.trimIndent()

        /** spawn_agent 的 function 定义（放这里避免循环依赖 tools 包） */
        private fun spawnAgentDefinition(): JSONObject =
            JSONObject().put("type", "function")
                .put("function", JSONObject()
                    .put("name", "spawn_agent")
                    .put("description", "派生一个子代理执行独立任务并等待其结果。子代理只有文件工具（list/read/write，相对 /sdcard），没有手机操作能力。")
                    .put("parameters", JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject()
                            .put("task", JSONObject().put("type", "string").put("description", "子代理要完成的任务，写清楚目标和产出物"))
                            .put("context", JSONObject().put("type", "string").put("description", "主代理提供的背景信息（可选）")))
                        .put("required", JSONArray().put("task"))))
    }
}
