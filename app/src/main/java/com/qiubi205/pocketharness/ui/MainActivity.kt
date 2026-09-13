package com.qiubi205.pocketharness.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.qiubi205.pocketharness.AgentEngine
import com.qiubi205.pocketharness.R
import com.qiubi205.pocketharness.a11y.HarnessAccessibilityService
import com.qiubi205.pocketharness.session.SessionStore
import com.qiubi205.pocketharness.workspace.Workspace

/**
 * 单 Activity 极简界面：状态条 + 对话流 + 输入行。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var engine: AgentEngine
    private lateinit var prefs: Prefs
    private lateinit var store: SessionStore
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var settingsPanel: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)
        engine = AgentEngine(this)
        engine.configure(prefs.baseUrl, prefs.apiKey, prefs.model)
        engine.maxToolRounds = prefs.maxToolRounds
        engine.onEvent = { ev -> runOnUiThread { appendLog(ev) } }

        // 会话：恢复上次激活的会话（无则建新），历史回填引擎
        store = SessionStore(java.io.File(Environment.getExternalStorageDirectory(), Workspace.DIR_NAME))
        val s = try { store.ensureActive() } catch (e: Exception) { null }
        if (s != null) {
            engine.replaceHistory(s.messages)
        }

        buildUi()
        if (s != null) {
            appendLog("💬 已恢复会话「${s.name}」（${s.messages.size} 条消息）")
        }
        requestStorage()
        val created = Workspace.seedIfFirstRun()
        if (created.isNotEmpty()) {
            appendLog("📁 已创建工作区 /sdcard/${Workspace.DIR_NAME}/ ：${created.joinToString("、")}")
        }
    }

    /** 存储权限：Android 11+ 走 MANAGE_EXTERNAL_STORAGE 引导页，10 走运行时授权 */
    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                appendLog("⚠️ 需要存储权限才能建工作区：点击后请在系统页允许「所有文件访问」")
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

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply {
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        log = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
            text = "PocketHarness 就绪。配置好 LLM 后下达指令。\n"
        }
        scroll = ScrollView(this).apply { addView(log) }

        input = EditText(this).apply {
            hint = getString(R.string.hint_input)
            setSingleLine(false)
            maxLines = 3
        }

        val sendBtn = Button(this).apply {
            text = getString(R.string.send)
            setOnClickListener { onSend() }
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad / 2, 0, pad / 2, pad / 2)
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendBtn)
        }

        val sessionsBtn = TextView(this).apply {
            text = "💬"
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener { showSessionDialog() }
        }

        // 设置面板（首行点"设置"展开）：会话管理按钮放状态行右侧
        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(pad, 0, pad, pad / 2)
            val url = EditText(this@MainActivity).apply { hint = "Base URL (如 https://api.xx.com/v1)"; setText(prefs.baseUrl) }
            val key = EditText(this@MainActivity).apply { hint = "API Key"; setText(prefs.apiKey) }
            val model = EditText(this@MainActivity).apply { hint = "模型名"; setText(prefs.model) }
            val rounds = EditText(this@MainActivity).apply {
                hint = "工具循环上限（默认 25）"
                setText(prefs.maxToolRounds.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            val save = Button(this@MainActivity).apply {
                text = "保存配置"
                setOnClickListener {
                    prefs.baseUrl = url.text.toString().trim()
                    prefs.apiKey = key.text.toString().trim()
                    prefs.model = model.text.toString().trim()
                    prefs.maxToolRounds = rounds.text.toString().toIntOrNull()?.coerceIn(3, 100) ?: 25
                    engine.maxToolRounds = prefs.maxToolRounds
                    engine.configure(prefs.baseUrl, prefs.apiKey, prefs.model)
                    appendLog("✅ 配置已保存：${prefs.baseUrl} / ${prefs.model} / 循环上限 ${prefs.maxToolRounds}")
                    settingsPanel.visibility = View.GONE
                    refreshStatus()
                }
            }
            addView(url); addView(key); addView(model); addView(rounds); addView(save)
        }

        val settingsBtn = TextView(this).apply {
            text = getString(R.string.settings)
            setPadding(pad, pad / 2, pad / 4, pad / 2)
            setOnClickListener { settingsPanel.visibility = if (settingsPanel.visibility == View.GONE) View.VISIBLE else View.GONE }
        }

        // 顶行：状态 + 设置 + 会话
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(settingsBtn)
            addView(sessionsBtn)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topRow)
            addView(settingsPanel)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(inputRow)
        }
        setContentView(root)
        refreshStatus()
    }

    private fun saveActive() {
        try {
            val s = store.ensureActive()
            s.messages.clear()
            s.messages.addAll(engine.snapshotHistory())
            store.save(s)
        } catch (e: Exception) { /* 权限未给时静默 */ }
    }

    /** 会话管理：列表/新建/切换/删除 */
    private fun showSessionDialog() {
        val sessions = try { store.list() } catch (e: Exception) { emptyList<SessionStore.Session>() }
        val active = try { store.activeId() } catch (e: Exception) { null }
        val items = Array(sessions.size + 1) { i ->
            if (i == 0) "➕ 新建会话"
            else (if (sessions[i - 1].id == active) "● " else "  ") +
                sessions[i - 1].name + "（${sessions[i - 1].messages.size}条）"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("会话")
            .setItems(items) { _, which ->
                try {
                    if (which == 0) {
                        switchTo(store.create("会话 ${sessions.size + 1}"))
                    } else {
                        val target = sessions[which - 1]
                        if (target.id == active) return@setItems
                        android.app.AlertDialog.Builder(this)
                            .setTitle(target.name)
                            .setItems(arrayOf("切换到此会话", "删除此会话")) { _, w ->
                                try {
                                    if (w == 0) switchTo(target)
                                    else {
                                        val wasActive = target.id == active
                                        store.delete(target.id)
                                        appendLog("🗑 已删除「${target.name}」")
                                        if (wasActive) {
                                            val next = try { store.ensureActive() } catch (e: Exception) { null }
                                            if (next != null) switchTo(next, saveCurrent = false)
                                        }
                                    }
                                } catch (e: Exception) {
                                    appendLog("⚠️ 会话操作失败：${e.message}（检查存储权限）")
                                }
                            }.show()
                    }
                } catch (e: Exception) {
                    appendLog("⚠️ 会话操作失败：${e.message}（检查存储权限）")
                }
            }
            .show()
    }

    private fun switchTo(s: SessionStore.Session, saveCurrent: Boolean = true) {
        if (saveCurrent) saveActive() // 切走前保存当前（删除后切换时不保存）
        store.setActive(s.id)
        engine.replaceHistory(s.messages)
        log.text = ""
        appendLog("💬 会话「${s.name}」（${s.messages.size} 条消息）")
        // 把旧消息渲染出来（最多 30 条，防止 UI 卡顿）
        s.messages.filter { it.role != "system" }.takeLast(30).forEach { m ->
            appendLog(if (m.role == "user") "你：${m.content ?: ""}" else "🤖：${m.content ?: "(工具调用)"}")
        }
    }

    private fun refreshStatus() {
        val on = HarnessAccessibilityService.isReady()
        status.text = getString(if (on) R.string.a11y_status_on else R.string.a11y_status_off)
        status.setTextColor(getColor(if (on) android.R.color.holo_green_dark else android.R.color.holo_red_light))
    }

    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (prefs.apiKey.isBlank() || prefs.baseUrl.isBlank()) {
            appendLog("⚠️ 请先点右上「设置」填写 LLM 配置。")
            settingsPanel.visibility = View.VISIBLE
            return
        }
        input.setText("")
        appendLog("你：$text")
        appendLog("…思考中")
        engine.send(text) { reply ->
            runOnUiThread {
                refreshStatus()
                if (reply != null) appendLog(reply) // "…思考中" 行保留，回复接在后面
                saveActive() // 每轮落盘
            }
        }
    }

    private fun appendLog(s: String) {
        log.append(if (log.text.isEmpty()) s else "\n$s")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 用户可能刚授予存储权限回来，补播种
        val created = Workspace.seedIfFirstRun()
        if (created.isNotEmpty()) appendLog("📁 工作区已就绪：${created.joinToString("、")} 已创建")
    }
}
