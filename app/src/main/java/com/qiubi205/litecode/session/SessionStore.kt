package com.qiubi205.litecode.session

import com.qiubi205.litecode.llm.LlmClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会话持久化：每个会话一个 JSON 文件 + index.json 记录元数据和当前激活会话。
 * 存储位置：/sdcard/Litecode/sessions/（与工作区互通，用户可直接查看）。
 */
class SessionStore(baseDir: File) {

    data class Session(
        val id: String,
        var name: String,
        val createdAtMs: Long,
        val messages: MutableList<LlmClient.Message> = mutableListOf()
    )

    private val dir: File = File(baseDir, "sessions").apply { mkdirs() }
    private val indexFile: File = File(dir, "index.json")

    // ---------- 数据结构 ----------

    private fun sessionFile(id: String): File {
        // id 只允许字母数字，防路径穿越
        require(id.matches(Regex("[A-Za-z0-9]{4,40}"))) { "非法会话 id" }
        return File(dir, "$id.json")
    }

    fun list(): List<Session> {
        val idx = readIndex()
        val metas = idx.optJSONArray("sessions") ?: JSONArray()
        val out = mutableListOf<Session>()
        for (i in 0 until metas.length()) {
            val m = metas.optJSONObject(i) ?: continue
            val s = load(m.getString("id")) ?: continue
            out.add(s)
        }
        return out.sortedByDescending { it.createdAtMs }
    }

    fun activeId(): String? = readIndex().optString("activeId").takeIf { it.isNotBlank() }

    fun setActive(id: String) {
        val idx = readIndex()
        idx.put("activeId", id)
        indexFile.writeText(idx.toString())
    }

    fun create(name: String): Session {
        val id = "s" + System.currentTimeMillis().toString(36)
        val s = Session(id, name, System.currentTimeMillis())
        save(s)
        val idx = readIndex()
        val metas = idx.optJSONArray("sessions") ?: JSONArray()
        metas.put(JSONObject().put("id", id))
        idx.put("sessions", metas).put("activeId", id)
        indexFile.writeText(idx.toString())
        return s
    }

    fun load(id: String): Session? = try {
        val f = sessionFile(id)
        if (!f.isFile) null else fromJson(JSONObject(f.readText()))
    } catch (e: Exception) { null }

    fun save(s: Session) {
        sessionFile(s.id).writeText(toJson(s).toString())
    }

    /** 删除会话；返回被删的名字。若删的是激活会话，激活位切到剩余最新一个（没有则空）。 */
    fun delete(id: String): String? {
        val s = load(id) ?: return null
        sessionFile(id).delete()
        val idx = readIndex()
        val metas = idx.optJSONArray("sessions") ?: JSONArray()
        val newMetas = JSONArray()
        for (i in 0 until metas.length()) {
            val m = metas.optJSONObject(i) ?: continue
            if (m.getString("id") != id) newMetas.put(m)
        }
        idx.put("sessions", newMetas)
        if (idx.optString("activeId") == id) {
            idx.put("activeId", newMetas.optJSONObject(0)?.optString("id") ?: "")
        }
        indexFile.writeText(idx.toString())
        return s.name
    }

    fun ensureActive(): Session {
        val act = activeId()?.let { load(it) }
        if (act != null) return act
        return list().firstOrNull()?.also { setActive(it.id) }
            ?: create("会话 1").also { setActive(it.id) }
    }

    // ---------- 序列化 ----------

    private fun readIndex(): JSONObject = try {
        if (indexFile.isFile) JSONObject(indexFile.readText()) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    private fun toJson(s: Session): JSONObject {
        val arr = JSONArray()
        for (m in s.messages) {
            val o = JSONObject().put("role", m.role)
            if (m.content != null) o.put("content", m.content)
            m.toolCalls?.let { tcs ->
                val ta = JSONArray()
                for (tc in tcs) ta.put(JSONObject()
                    .put("id", tc.id).put("name", tc.name).put("arguments", tc.argumentsJson))
                o.put("tool_calls", ta)
            }
            m.toolCallId?.let { o.put("tool_call_id", it) }
            m.name?.let { o.put("name", it) }
            arr.put(o)
        }
        return JSONObject().put("id", s.id).put("name", s.name)
            .put("created", s.createdAtMs).put("messages", arr)
    }

    private fun fromJson(o: JSONObject): Session {
        val msgs = mutableListOf<LlmClient.Message>()
        val arr = o.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val tcs = mutableListOf<LlmClient.ToolCall>()
            m.optJSONArray("tool_calls")?.let { ta ->
                for (j in 0 until ta.length()) {
                    val tc = ta.optJSONObject(j) ?: continue
                    tcs.add(LlmClient.ToolCall(tc.optString("id"), tc.optString("name"), tc.optString("arguments", "{}")))
                }
            }
            msgs.add(LlmClient.Message(
                role = m.getString("role"),
                content = m.optString("content").takeIf { it.isNotEmpty() || m.has("content") },
                toolCalls = tcs.takeIf { it.isNotEmpty() },
                toolCallId = m.optString("tool_call_id").takeIf { it.isNotEmpty() },
                name = m.optString("name").takeIf { it.isNotEmpty() }
            ))
        }
        return Session(o.getString("id"), o.optString("name", "会话"), o.optLong("created"), msgs)
    }
}
