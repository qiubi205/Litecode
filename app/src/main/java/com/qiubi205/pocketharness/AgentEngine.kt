package com.qiubi205.pocketharness

import android.content.Context
import com.qiubi205.pocketharness.llm.LlmClient
import com.qiubi205.pocketharness.tools.DeviceTools
import com.qiubi205.pocketharness.tools.FileTools
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

    /** 每轮对话开始时重新读 /sdcard/PocketHarness/MEMORY.md，跨会话记忆即时生效 */
    private fun systemPrompt(): String = SYSTEM_PROMPT + "\n\n# 当前长期记忆（/sdcard/${Workspace.DIR_NAME}/MEMORY.md 实时内容）\n\n" + Workspace.readMemory()

    /** 工具循环上限，防止死循环烧 token */
    var maxToolRounds = 8
    /** 每轮回调（UI 线程刷新用） */
    var onEvent: ((String) -> Unit)? = null

    fun configure(url: String, key: String, model: String) = client.updateConfig(url, key, model)

    val messageCount: Int get() = history.size

    fun reset() {
        history.clear()
        history.add(LlmClient.Message("system", systemPrompt()))
    }

    /** 异步执行一轮对话；onEvent 依次收到 agent 的可见输出 */
    fun send(userText: String, onDone: (String?) -> Unit) {
        if (history.isEmpty()) reset()
        // 每次发消息都刷新 system 提示词，把最新 MEMORY.md 注入进去
        history[0] = LlmClient.Message("system", systemPrompt())
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
        }
        var rounds = 0
        while (rounds < maxToolRounds) {
            rounds++
            val resp = client.chat(history, tools)

            // 有工具调用：执行并回填
            if (resp.toolCalls.isNotEmpty()) {
                history.add(LlmClient.Message("assistant", resp.content, toolCalls = resp.toolCalls))
                for (tc in resp.toolCalls) {
                    onEvent?.invoke("🔧 $tc.name …")
                    val args = try { JSONObject(tc.argumentsJson.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
                    val result = when (tc.name) {
                        "list_files" -> FileTools.listFiles(args.optString("path", "."))
                        "read_file" -> FileTools.readFile(args.getString("path"))
                        "write_file" -> FileTools.writeFile(
                            args.getString("path"),
                            args.getString("content"),
                            args.optBoolean("append", false))
                        else -> DeviceTools.dispatch(tc.name, args)
                    }
                    onEvent?.invoke("🔧 ${tc.name} → ${result.optString("ok", result.optString("error", "done")).take(60)}")
                    history.add(LlmClient.Message("tool", result.toString().take(12_000), toolCallId = tc.id, name = tc.name))
                }
                continue // 回给模型继续
            }

            // 纯文本：结束
            if (!resp.content.isNullOrBlank()) return resp.content
            return "(模型返回空回复)"
        }
        return "⚠️ 已达工具轮次上限（$maxToolRounds），强制结束。"
    }

    companion object {
        val SYSTEM_PROMPT = """
你是 PocketHarness，一个运行在用户 Android 手机上的本地代理。你能：

1. 手机操作（无障碍引擎）：get_screen 看屏，tap/swipe/input_text/press_back/press_home 操作。原则：先 get_screen 再动手；坐标用控件树里 <> 标注的中心点；一次只做一步，观察结果再继续。
2. 文件（/sdcard）：list_files / read_file / write_file，路径相对于 /sdcard（如 Download、Documents/xx.txt）。删除操作一律拒绝，让用户手动做。
3. 长期记忆：你的工作区在 /sdcard/PocketHarness/（MEMORY.md = 长期记忆，AGENTS.md = 行为守则）。对话开始时若记忆与本任务相关请参考；对话结束前，把值得长期记住的信息（用户偏好/重要结论/路径）用 write_file（append=true）写入 MEMORY.md。记忆在下次对话自动注入你的 system 提示词，跨会话生效。

行为准则：
- 用户下达任务后，规划最短路径执行，不要反复确认。
- 涉及支付、发送消息给他人、删除数据的动作，先向用户复述并等确认。
- 执行结果如实汇报；失败时读屏诊断原因并尝试一次替代方案。
- 用中文回复，简洁。
        """.trimIndent()
    }
}
