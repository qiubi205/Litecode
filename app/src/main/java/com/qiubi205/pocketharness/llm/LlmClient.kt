package com.qiubi205.pocketharness.llm

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
        val name: String? = null
    )

    data class Response(val content: String?, val toolCalls: List<ToolCall>, val finishReason: String?, val rawUsage: JSONObject?)

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

    private fun post(body: JSONObject): Response {
        val conn = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
        return try {
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
            if (code !in 200..299) throw LlmException("HTTP $code: ${text.take(400)}")

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
            Response(
                content = msg?.optString("content")?.takeIf { it.isNotEmpty() && it != "null" },
                toolCalls = tcs,
                finishReason = choice?.optString("finish_reason"),
                rawUsage = json.optJSONObject("usage")
            )
        } finally {
            conn.disconnect()
        }
    }

    class LlmException(message: String) : RuntimeException(message)
}
