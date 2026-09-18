package com.qiubi205.litecode.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qiubi205.litecode.AgentEngine
import com.qiubi205.litecode.a11y.HarnessAccessibilityService
import com.qiubi205.litecode.profile.Profile
import com.qiubi205.litecode.profile.ProfileStore
import com.qiubi205.litecode.session.SessionStore
import com.qiubi205.litecode.tools.DeviceTools
import com.qiubi205.litecode.workspace.Workspace
import java.io.File

/**
 * v0.6.0 Compose 换皮：Activity 只做状态接线（引擎/会话/档案 ↔ ChatScreen），
 * UI 全部在 ChatUi.kt（装配）+ ChatUiParts.kt（零件）。
 */
class MainActivity : ComponentActivity() {

    private lateinit var engine: AgentEngine
    private lateinit var prefs: Prefs
    private lateinit var store: SessionStore
    private lateinit var profiles: ProfileStore

    private var entries by mutableStateOf(listOf<ChatEntry>())
    private var busy by mutableStateOf(false)
    private var sessions by mutableStateOf(listOf<SessionUi>())
    private var activeSessionId by mutableStateOf<String?>(null)
    private var config by mutableStateOf(ConfigUi("", "", "", 25))
    private var a11yReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)
        engine = AgentEngine(this)
        engine.maxToolRounds = prefs.maxToolRounds
        engine.onEvent = { ev -> runOnUiThread { onEngineEvent(ev) } }

        // 多档案：APP 私有目录存 profiles.json；首次用旧 Prefs 值播种默认档案
        profiles = ProfileStore(File(filesDir, "profiles"))
        val active = profiles.ensureDefaults(prefs.baseUrl, prefs.apiKey, prefs.model)
        applyProfileToEngine(active)

        // 会话：恢复上次激活的（无则建新），历史回填引擎
        store = SessionStore(java.io.File(Environment.getExternalStorageDirectory(), Workspace.DIR_NAME))
        val s = try { store.ensureActive() } catch (e: Exception) { null }
        if (s != null) {
            engine.replaceHistory(s.messages)
            activeSessionId = s.id
            entries = rebuildEntries(s)
            pushStatus("💬 已恢复会话「${s.name}」（${s.messages.size} 条消息）")
        }
        refreshSessionList()
        refreshConfigState()

        setContent {
            ChatScreen(
                entries = entries,
                busy = busy,
                a11yReady = a11yReady,
                sessions = sessions,
                activeSessionId = activeSessionId,
                config = config,
                onSend = ::onSend,
                onStop = ::onStop,
                onNewSession = ::onNewSession,
                onSelectSession = ::onSelectSession,
                onDeleteSession = ::onDeleteSession,
                onSaveConfig = ::onSaveConfig,
                onOpenA11ySettings = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            )
        }

        requestStorage()
        Workspace.seedIfFirstRun()
    }

    // ---------- 档案 ----------

    private fun applyProfileToEngine(p: Profile) {
        engine.configure(p.baseUrl, p.apiKey, p.model)
        engine.maxToolRounds = prefs.maxToolRounds
    }

    private fun refreshConfigState() {
        val p = profiles.ensureActive()
        config = ConfigUi(p.baseUrl, p.apiKey, p.model, prefs.maxToolRounds)
    }

    private fun onSaveConfig(url: String, key: String, model: String, rounds: Int) {
        val p = profiles.ensureActive()
        p.baseUrl = url.trim()
        p.apiKey = key.trim()
        p.model = model.trim()
        profiles.update(p)
        prefs.maxToolRounds = rounds.coerceIn(3, 100)
        // 兼容旧 Prefs（作为默认档案镜像）
        prefs.baseUrl = p.baseUrl
        prefs.apiKey = p.apiKey
        prefs.model = p.model
        applyProfileToEngine(p)
        refreshConfigState()
        pushStatus("✅ 配置已保存：${p.baseUrl} / ${p.model} / 循环上限 ${prefs.maxToolRounds}")
    }

    // ---------- 会话 ----------

    private fun refreshSessionList() {
        sessions = try {
            store.list().map { SessionUi(it.id, it.name, it.messages.size) }
        } catch (e: Exception) { emptyList() }
        activeSessionId = try { store.activeId() } catch (e: Exception) { null }
    }

    private fun saveActive() {
        try {
            val s = store.ensureActive()
            s.messages.clear()
            s.messages.addAll(engine.snapshotHistory())
            store.save(s)
        } catch (e: Exception) { /* 权限未给时静默 */ }
    }

    private fun rebuildEntries(s: SessionStore.Session): List<ChatEntry> =
        s.messages.filter { it.role == "user" || it.role == "assistant" }
            .takeLast(30)
            .map { m ->
                if (m.role == "user") ChatEntry("user", m.content ?: "")
                else ChatEntry("assistant", m.content ?: "(工具调用)")
            }

    private fun onNewSession() {
        try {
            saveActive()
            val s = store.create("会话 ${sessions.size + 1}")
            store.setActive(s.id)
            engine.replaceHistory(s.messages)
            entries = listOf(ChatEntry("status", "💬 新会话「${s.name}」"))
            refreshSessionList()
        } catch (e: Exception) {
            pushStatus("⚠️ 新建失败：${e.message}")
        }
    }

    private fun onSelectSession(id: String) {
        try {
            val target = store.load(id) ?: return
            saveActive()
            store.setActive(id)
            engine.replaceHistory(target.messages)
            entries = rebuildEntries(target)
            refreshSessionList()
            pushStatus("💬 会话「${target.name}」（${target.messages.size} 条消息）")
        } catch (e: Exception) {
            pushStatus("⚠️ 切换失败：${e.message}")
        }
    }

    private fun onDeleteSession(id: String) {
        try {
            val wasActive = id == activeSessionId
            val name = store.delete(id)
            if (name != null) {
                if (wasActive) {
                    val next = try { store.ensureActive() } catch (e: Exception) { null }
                    if (next != null) {
                        store.setActive(next.id)
                        engine.replaceHistory(next.messages)
                        entries = rebuildEntries(next)
                    }
                }
                refreshSessionList()
                pushStatus("🗑 已删除「$name」")
            }
        } catch (e: Exception) {
            pushStatus("⚠️ 删除失败：${e.message}")
        }
    }

    // ---------- 发送 / 停止 ----------

    private fun onSend(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val p = profiles.ensureActive()
        if (p.apiKey.isBlank() || p.baseUrl.isBlank()) {
            pushStatus("⚠️ 请先在「⚙ 设置」里填写 LLM 配置。")
            return
        }
        entries = entries + ChatEntry("user", t) + ChatEntry("status", "…")
        busy = true
        engine.send(t) { reply ->
            runOnUiThread {
                busy = false
                a11yReady = HarnessAccessibilityService.isReady()
                if (reply != null) replaceTrailingStatus(reply)
                else dropTrailingStatus()
                saveActive()
            }
        }
    }

    private fun onStop() {
        engine.cancel()
        DeviceTools.cancelled = true
        pushStatus("⏹ 已请求停止")
    }

    // ---------- 引擎事件 → 消息流 ----------

    private fun onEngineEvent(ev: String) {
        // 🔧 工具调用事件替换尾部"…"，不追加新行（避免刷屏）
        if (ev.startsWith("🔧") || ev.startsWith("🛰️") || ev.startsWith("👁")) {
            replaceTrailingStatus(ev)
        } else {
            pushStatus(ev)
        }
    }

    private fun pushStatus(text: String) {
        entries = entries + ChatEntry("status", text)
    }

    private fun replaceTrailingStatus(text: String) {
        val last = entries.lastOrNull()
        entries = if (last?.role == "status") {
            entries.dropLast(1) + ChatEntry("assistant", text)
        } else {
            entries + ChatEntry("assistant", text)
        }
    }

    private fun dropTrailingStatus() {
        if (entries.lastOrNull()?.role == "status") entries = entries.dropLast(1)
    }

    // ---------- 权限 ----------

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                pushStatus("⚠️ 需要存储权限：请在接下来系统页允许「所有文件访问」")
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            }
        } else if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        a11yReady = HarnessAccessibilityService.isReady()
        // 用户可能刚授予存储权限回来，补播种
        val created = Workspace.seedIfFirstRun()
        if (created.isNotEmpty()) pushStatus("📁 工作区已就绪：${created.joinToString("、")} 已创建")
    }
}
