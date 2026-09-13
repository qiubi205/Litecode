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
import com.qiubi205.pocketharness.workspace.Workspace

/**
 * 单 Activity 极简界面：状态条 + 对话流 + 输入行。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var engine: AgentEngine
    private lateinit var prefs: Prefs
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
        engine.onEvent = { ev -> runOnUiThread { appendLog(ev) } }

        buildUi()
        engine.reset()
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

        // 设置面板（首行点"设置"展开）
        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(pad, 0, pad, pad / 2)
            val url = EditText(this@MainActivity).apply { hint = "Base URL (如 https://api.xx.com/v1)"; setText(prefs.baseUrl) }
            val key = EditText(this@MainActivity).apply { hint = "API Key"; setText(prefs.apiKey) }
            val model = EditText(this@MainActivity).apply { hint = "模型名"; setText(prefs.model) }
            val save = Button(this@MainActivity).apply {
                text = "保存配置"
                setOnClickListener {
                    prefs.baseUrl = url.text.toString().trim()
                    prefs.apiKey = key.text.toString().trim()
                    prefs.model = model.text.toString().trim()
                    engine.configure(prefs.baseUrl, prefs.apiKey, prefs.model)
                    appendLog("✅ 配置已保存：${prefs.baseUrl} / ${prefs.model}")
                    settingsPanel.visibility = View.GONE
                    refreshStatus()
                }
            }
            addView(url); addView(key); addView(model); addView(save)
        }

        val settingsBtn = TextView(this).apply {
            text = getString(R.string.settings)
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener { settingsPanel.visibility = if (settingsPanel.visibility == View.GONE) View.VISIBLE else View.GONE }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(settingsBtn)
            addView(settingsPanel)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(inputRow)
        }
        setContentView(root)
        refreshStatus()
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
