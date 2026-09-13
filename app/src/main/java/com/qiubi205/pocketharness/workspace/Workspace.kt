package com.qiubi205.pocketharness.workspace

import android.os.Environment
import java.io.File

/**
 * Agent 工作区：/sdcard/PocketHarness/
 * 首次启动自动创建并写入引导文件（MEMORY.md / AGENTS.md）。
 * SYSTEM_PROMPT 只放「灵魂规则」，长期记忆放文件，每次对话自动注入。
 */
object Workspace {

    const val DIR_NAME = "PocketHarness"

    fun dir(): File {
        val d = File(Environment.getExternalStorageDirectory(), DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun memoryFile(): File = File(dir(), "MEMORY.md")
    fun agentsFile(): File = File(dir(), "AGENTS.md")

    /**
     * 首次启动播种引导文件；已存在的不覆盖（记忆是持久的）。
     * 返回本次新建的文件名列表，仅用于日志显示。
     */
    fun seedIfFirstRun(): List<String> {
        val created = mutableListOf<String>()
        try {
        if (!memoryFile().exists()) {
            memoryFile().writeText(
                """# MEMORY.md - PocketHarness 长期记忆

用 list_files / read_file 检查我有没有写过重要内容；每次对话结束前，把值得长期记住的
（用户偏好、任务结论、重要路径）用 write_file 追加到本文件。下次对话我会自动读到它。

## 已知信息

- （空，等待第一课）
""".trimIndent() + "\n"
            )
            created.add("MEMORY.md")
        }
        if (!agentsFile().exists()) {
            agentsFile().writeText(
                """# AGENTS.md - 行为守则

- 文件工具路径都相对 /sdcard；你的专属工作区是 /sdcard/$DIR_NAME/，重要产出写在这里。
- 删除、覆盖大量内容前先问用户。
- 用户纠正你时，把教训写进 MEMORY.md，同样的错不犯第二次。
""".trimIndent() + "\n"
            )
            created.add("AGENTS.md")
        }
        } catch (e: Exception) {
            // 权限未授时 mkdirs/write 会失败，静默跳过，onResume 会重试
        }
        return created
    }

    /** 读取当前记忆（不存在返回空串）。超长截断，防止撑爆上下文。 */
    fun readMemory(maxChars: Int = 8_000): String {
        val f = memoryFile()
        if (!f.isFile) return ""
        return try {
            val text = f.readText()
            if (text.length > maxChars) text.take(maxChars) + "\n…(已截断，完整内容请用 read_file 查看)"
            else text
        } catch (e: Exception) {
            ""
        }
    }
}
