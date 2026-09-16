package com.qiubi205.litecode.agent

import com.qiubi205.litecode.llm.LlmClient
import com.qiubi205.litecode.tools.FileTools
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * 子代理：独立的 LLM 对话（自己的 system prompt + 任务），只给文件工具（不给手机控制，
 * 避免与主代理的无障碍手势互相打架）。同步阻塞执行，由调用方线程控制。
 */
object SubAgent {

    /** @return 子代理的最终文本回复 */
    fun run(
        client: LlmClient,
        task: String,
        context: String,
        maxRounds: Int = 6,
        onEvent: ((String) -> Unit)? = null
    ): String {
        val sys = """
你是 Litecode 的子代理，由主代理派生，专注完成单一任务后交还结果。
- 你只有文件工具（list_files / read_file / write_file，路径相对 /sdcard），没有手机操作能力。
- 工作区：/sdcard/Litecode/。不要写入 MEMORY.md（那是主代理的），产出写到 /sdcard/Litecode/sub/<任务名>/ 下。
- 完成任务后，用一段简洁的文本汇报结果；不要反问用户，你没有和用户对话的通道。
        """.trimIndent()

        val messages = mutableListOf(
            LlmClient.Message("system", sys + (if (context.isNotBlank()) "\n\n# 主代理提供的上下文\n$context" else "")),
            LlmClient.Message("user", task)
        )

        val tools = JSONArray()
        for (i in 0 until FileTools.definitions.length()) tools.put(FileTools.definitions.get(i))

        var rounds = 0
        while (rounds < maxRounds) {
            rounds++
            val resp = client.chat(messages, tools)
            if (resp.toolCalls.isNotEmpty()) {
                messages.add(LlmClient.Message("assistant", resp.content, toolCalls = resp.toolCalls))
                for (tc in resp.toolCalls) {
                    val args = try { JSONObject(tc.argumentsJson.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
                    val result = try {
                        when (tc.name) {
                            "list_files" -> FileTools.listFiles(args.optString("path", "."))
                            "read_file" -> FileTools.readFile(args.getString("path"))
                            "write_file" -> FileTools.writeFile(
                                args.getString("path"),
                                args.getString("content"),
                                args.optBoolean("append", false))
                            else -> JSONObject().put("error", "子代理没有工具: ${tc.name}")
                        }
                    } catch (e: Exception) {
                        JSONObject().put("error", e.message ?: "工具执行失败")
                    }
                    onEvent?.invoke("  🛰️ 子代理 ${tc.name}")
                    messages.add(LlmClient.Message("tool", result.toString().take(8_000),
                        toolCallId = tc.id, name = tc.name))
                }
                continue
            }
            return resp.content ?: "(子代理空回复)"
        }
        return "⚠️ 子代理达到轮次上限（$maxRounds），未给出最终答复。"
    }

    /** 异步包装 */
    fun runAsync(client: LlmClient, task: String, context: String,
                 onEvent: ((String) -> Unit)? = null, onDone: (String) -> Unit) {
        thread(name = "subagent") {
            val r = try { run(client, task, context, onEvent = onEvent) }
            catch (e: Exception) { "⚠️ 子代理错误：${e.message}" }
            onDone(r)
        }
    }
}
