package com.qiubi205.pocketharness.tools

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

object HttpTools {

    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 60000
    private const val MAX_BODY_CHARS = 20000

    val definitions: JSONArray = buildDefinitions()

    fun dispatch(args: JSONObject): JSONObject {
        val urlStr = args.optString("url", "").trim()
        if (urlStr.isEmpty()) {
            return JSONObject().put("error", "url is required")
        }
        val methodRaw = args.optString("method", "GET").trim().uppercase()
        val method = when (methodRaw) {
            "POST", "PUT", "DELETE" -> methodRaw
            else -> "GET"
        }
        val body = if (args.isNull("body")) "" else args.optString("body", "")

        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = method
            applyHeaders(conn, args.optJSONObject("headers"))

            if (method == "POST" || method == "PUT") {
                conn.doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                if (conn.getRequestProperty("Content-Type").isNullOrEmpty()) {
                    conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { out ->
                    out.write(bytes)
                    out.flush()
                }
            }

            val status = conn.responseCode
            val respBody = readBody(if (status in 200..299) conn.inputStream else conn.errorStream)
            JSONObject()
                .put("ok", true)
                .put("status", status)
                .put("body", respBody)
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: (e.javaClass.simpleName + " occurred"))
        } finally {
            conn?.disconnect()
        }
    }

    private fun applyHeaders(conn: HttpURLConnection, headers: JSONObject?) {
        if (headers == null) return
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            conn.setRequestProperty(key, headers.optString(key, ""))
        }
    }

    private fun readBody(stream: InputStream?): String {
        if (stream == null) return ""
        return try {
            val sb = StringBuilder()
            val buf = CharArray(4096)
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                while (sb.length < MAX_BODY_CHARS) {
                    val n = reader.read(buf)
                    if (n == -1) break
                    sb.append(buf, 0, n)
                }
            }
            if (sb.length > MAX_BODY_CHARS) sb.substring(0, MAX_BODY_CHARS) else sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private fun buildDefinitions(): JSONArray {
        val properties = JSONObject()
            .put("url", JSONObject()
                .put("type", "string")
                .put("description", "要请求的完整 URL，必填"))
            .put("method", JSONObject()
                .put("type", "string")
                .put("enum", JSONArray().put("GET").put("POST").put("PUT").put("DELETE"))
                .put("description", "HTTP 方法，默认 GET"))
            .put("headers", JSONObject()
                .put("type", "object")
                .put("description", "可选请求头，键值对对象"))
            .put("body", JSONObject()
                .put("type", "string")
                .put("description", "可选请求体文本，POST/PUT 时以 UTF-8 发送"))

        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray().put("url").put("method"))

        val function = JSONObject()
            .put("name", "http_request")
            .put("description", "发起 HTTP 请求，返回状态码与响应体前 20000 字符；HTTP 错误状态码不抛异常")
            .put("parameters", parameters)

        return JSONArray().put(
            JSONObject()
                .put("type", "function")
                .put("function", function)
        )
    }
}
