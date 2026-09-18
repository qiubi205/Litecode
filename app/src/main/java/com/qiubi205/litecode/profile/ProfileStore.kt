// 多档案：不同会话可绑不同 API 配置
package com.qiubi205.litecode.profile

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Profile(val id: String, var name: String, var baseUrl: String, var apiKey: String, var model: String)

class ProfileStore(private val dir: File) {

    private val file = File(dir, "profiles.json")
    private val entries = mutableListOf<Entry>()
    private var active = ""

    init {
        dir.mkdirs()
        load()
    }

    fun list(): List<Profile> = entries.sortedBy { it.created }.map { copy(it.p) }

    fun get(id: String): Profile? = entries.firstOrNull { it.p.id == id }?.let { copy(it.p) }

    fun activeId(): String? = if (active.isEmpty()) null else active

    fun setActive(id: String) {
        active = id
        save()
    }

    fun ensureActive(): Profile {
        if (entries.isEmpty()) {
            val p = Profile("default", "默认", "", "", "")
            entries.add(Entry(p, now()))
            active = p.id
            save()
            return copy(p)
        }
        val cur = entries.firstOrNull { it.p.id == active } ?: entries[0]
        if (cur.p.id != active) {
            active = cur.p.id
            save()
        }
        return copy(cur.p)
    }

    fun create(name: String): Profile {
        val t = now()
        val p = Profile("p" + t.toString(36), name, "", "", "")
        entries.add(Entry(copy(p), t))
        save()
        return p
    }

    fun update(p: Profile) {
        val idx = entries.indexOfFirst { it.p.id == p.id }
        if (idx < 0) return
        entries[idx] = Entry(copy(p), entries[idx].created)
        save()
    }

    fun delete(id: String): String? {
        if (id == "default") return null
        val idx = entries.indexOfFirst { it.p.id == id }
        if (idx < 0) return null
        val name = entries[idx].p.name
        entries.removeAt(idx)
        if (active == id) active = entries.firstOrNull()?.p?.id ?: ""
        save()
        return name
    }

    fun ensureDefaults(baseUrl: String, apiKey: String, model: String): Profile {
        if (entries.isEmpty()) {
            val p = Profile("default", "默认", baseUrl, apiKey, model)
            entries.add(Entry(p, now()))
            active = p.id
            save()
            return copy(p)
        }
        return ensureActive()
    }

    private class Entry(val p: Profile, val created: Long)

    private fun now() = System.currentTimeMillis()

    private fun copy(p: Profile) = Profile(p.id, p.name, p.baseUrl, p.apiKey, p.model)

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            active = root.optString("activeId", "")
            val arr = root.optJSONArray("profiles") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty()) continue
                val p = Profile(id, o.optString("name", ""), o.optString("base_url", ""), o.optString("api_key", ""), o.optString("model", ""))
                entries.add(Entry(p, o.optLong("created", now())))
            }
            entries.sortBy { it.created }
        } catch (e: Exception) {
            entries.clear()
            active = ""
        }
    }

    private fun save() {
        try {
            dir.mkdirs()
            val root = JSONObject()
            root.put("activeId", active)
            val arr = JSONArray()
            for (e in entries) arr.put(JSONObject()
                .put("id", e.p.id).put("name", e.p.name)
                .put("base_url", e.p.baseUrl).put("api_key", e.p.apiKey)
                .put("model", e.p.model).put("created", e.created))
            root.put("profiles", arr)
            file.writeText(root.toString())
        } catch (e: Exception) {
        }
    }
}
