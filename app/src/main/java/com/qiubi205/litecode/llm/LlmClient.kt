package com.qiubi205.litecode.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 极简 OpenAI 兼容客户端：自定义 base URL + key + 模型。
 * 支持 tool calling（function calling）与单次视觉问答（chatVision）。
 */
class LlmClient(
    private var baseUrl: String,
    private var apiKey: String,
    private var model: String
) {
    data class ToolCall(val id: String, val name: String, val argumentsJson: String)
    data class Message(
        val role: String,
        val content: String? = null,
        val toolCalls: List<ToolCall>? = null,
        val toolCallId: String? = null,
        val name: String? = null,
        val reasoning: String? = null
    )

    data class Response(
        val content: String?,
        val toolCalls: List<ToolCall>,
        val finishReason: String?,
        val rawUsage: JSONObject?,
        val reasoning: String? = null
    )

    /** 生成参数（设置页可调）；温度/多样性/思考强度 */
    var temperature: Double = 0.7
    var topP: Double = 0.9
    var thinkingBudget: String = ""   // ""=不发送（用服务端默认）; "low"/"medium"/"high"
    var streamEnabled: Boolean = true
    /** 伪流式播放速度：每秒吐多少字符（中文 ≈ token 数） */
    var playSpeedCps: Int = 100

    fun updateConfig(url: String, key: String, modelId: String) {
        baseUrl = url.trimEnd('/')
        apiKey = key
        model = modelId
    }

    /** @param tools OpenAI function 定义数组，可空 */
    fun chat(messages: List<Message>, tools: JSONArray? = null, temperature: Double = 0.7): Response {
        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject()
            o.put("role", m.role)
            when {
                m.toolCalls != null -> {
                    o.put("content", m.content ?: JSONObject.NULL)
                    val tcs = JSONArray()
                    for (tc in m.toolCalls) {
                        tcs.put(JSONObject().put("id", tc.id).put("type", "function")
                            .put("function", JSONObject().put("name", tc.name).put("arguments", tc.argumentsJson)))
                    }
                    o.put("tool_calls", tcs)
                }
                m.toolCallId != null -> {
                    o.put("content", m.content ?: "")
                    o.put("tool_call_id", m.toolCallId)
                }
                else -> o.put("content", m.content ?: JSONObject.NULL)
            }
            arr.put(o)
        }
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("top_p", topP)
            if (thinkingBudget.isNotBlank()) put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", thinkingBudget))
            put("messages", arr)
            if (tools != null && tools.length() > 0) put("tools", tools)
        }
        return post(body)
    }

    /** 单次视觉问答：把本地图片（base64）发给多模态模型，返回文本回答。 */
    fun chatVision(question: String, imageBase64: String, mime: String, temperature: Double = 0.2): String? {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", question))
            .put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:$mime;base64,$imageBase64")))
        val body = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", content)))
        return post(body).content
    }

    /**
     * SSE 流式对话：网络层逐 delta 接收，onDelta 回调增量（content 或 reasoning）。
     * 返回攒好的全量 Response。流式不可用或中途失败时回退一次性 post。
     */
    fun chatStream(messages: List<Message>, tools: JSONArray?, onDelta: (reasoning: Boolean, text: String) -> Unit): Response {
        if (!streamEnabled) return chat(messages, tools)
        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject().put("role", m.role)
            o.put("content", m.content ?: JSONObject.NULL)
            if (m.toolCalls != null) {
                val tcs = JSONArray()
                for (tc in m.toolCalls) tcs.put(JSONObject().put("id", tc.id).put("type", "function")
                    .put("function", JSONObject().put("name", tc.name).put("arguments", tc.argumentsJson)))
                o.put("tool_calls", tcs)
            } else if (m.toolCallId != null) o.put("tool_call_id", m.toolCallId)
            arr.put(o)
        }
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("top_p", topP)
            if (thinkingBudget.isNotBlank()) put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", thinkingBudget))
            put("messages", arr)
            if (tools != null && tools.length() > 0) put("tools", tools)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
        }
        val conn = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                // 非 200 一律回退非流式（错误处理/重试逻辑都在那边）
                return chat(messages, tools)
            }
            val contentSb = StringBuilder()
            val reasonSb = StringBuilder()
            val tcs = mutableListOf<ToolCall>()
            var finish: String? = null
            var usage: JSONObject? = null
            val reader = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.startsWith("data:")) {
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    try {
                        val chunk = JSONObject(payload)
                        chunk.optJSONObject("usage")?.let { usage = it }
                        val choice = chunk.optJSONArray("choices")?.optJSONObject(0)
                        if (choice != null) {
                            choice.optString("finish_reason")?.takeIf { it.isNotEmpty() && it != "null" }?.let { finish = it }
                            val delta = choice.optJSONObject("delta")
                            if (delta != null) {
                                val r = delta.optString("reasoning_content").takeIf { it.isNotEmpty() }
                                    ?: delta.optString("reasoning").takeIf { it.isNotEmpty() }
                                if (r != null) { reasonSb.append(r); onDelta(true, r) }
                                val c = delta.optString("content")
                                if (c.isNotEmpty()) { contentSb.append(c); onDelta(false, c) }
                                delta.optJSONArray("tool_calls")?.let { tca ->
                                    for (i in 0 until tca.length()) {
                                        val tc = tca.optJSONObject(i) ?: continue
                                        val fn = tc.optJSONObject("function") ?: continue
                                        val idx = tc.optInt("index", i)
                                        while (tcs.size <= idx) tcs.add(ToolCall("", "", ""))
                                        val old = tcs[idx]
                                        tcs[idx] = ToolCall(
                                            tc.optString("id", old.id),
                                            fn.optString("name", old.name),
                                            old.argumentsJson + fn.optString("arguments", ""))
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) { /* 跳过坏 chunk */ }
                }
                line = reader.readLine()
            }
            return Response(contentSb.toString().takeIf { it.isNotBlank() }, tcs, finish, usage, reasonSb.toString().takeIf { it.isNotBlank() })
        } catch (e: Exception) {
            return chat(messages, tools)   // 流式中断/异常 → 回退一次性
        } finally {
            conn.disconnect()
        }
    }

    private fun post(body: JSONObject, attempt: Int = 0): Response {
        val conn = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 180_000   // 慢模型友好
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "application/json")

            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream ?: ByteArrayInputStream(ByteArray(0)), StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                // 网络层/过载类错误重试（429/5xx），其他 4xx 直接抛
                if (attempt < 2 && (code == 429 || code >= 500)) {
                    Thread.sleep(if (attempt == 0) 2_000 else 5_000)
                    return post(body, attempt + 1)
                }
                throw LlmException("HTTP $code: ${text.take(400)}")
            }

            val json = JSONObject(text)
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
            val msg = choice?.optJSONObject("message")
            val tcs = mutableListOf<ToolCall>()
            msg?.optJSONArray("tool_calls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val tc = arr.optJSONObject(i) ?: continue
                    val fn = tc.optJSONObject("function") ?: continue
                    tcs.add(ToolCall(tc.optString("id"), fn.optString("name"), fn.optString("arguments", "{}")))
                }
            }
            return Response(
                content = msg?.optString("content")?.takeIf { it.isNotEmpty() && it != "null" },
                toolCalls = tcs,
                finishReason = choice?.optString("finish_reason"),
                rawUsage = json.optJSONObject("usage"),
                reasoning = msg?.optString("reasoning_content")?.takeIf { it.isNotEmpty() && it != "null" }
                    ?: msg?.optString("reasoning")?.takeIf { it.isNotEmpty() && it != "null" }
            )
        } catch (e: LlmException) {
            throw e
        } catch (e: InterruptedException) {
            throw LlmException("重试等待被中断")
        } catch (e: java.io.IOException) {
            // 网络异常（超时/断连）重试 2 次
            if (attempt < 2) {
                Thread.sleep(if (attempt == 0) 2_000 else 5_000)
                return post(body, attempt + 1)
            }
            throw LlmException("网络异常（已重试2次）: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    class LlmException(message: String) : RuntimeException(message)
}
