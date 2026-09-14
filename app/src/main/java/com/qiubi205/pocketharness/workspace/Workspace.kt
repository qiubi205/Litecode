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

    /** 技能目录：/sdcard/PocketHarness/skills/<name>/SKILL.md */
    fun skillsDir(): File {
        val d = File(dir(), "skills")
        if (!d.exists()) d.mkdirs()
        return d
    }

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
        // 播种 skills 目录 + 示例技能（已存在不覆盖）
        val exDir = File(skillsDir(), "example")
        val exSkill = File(exDir, "SKILL.md")
        if (!exSkill.exists()) {
            try {
                exDir.mkdirs()
                exSkill.writeText(
                    """---
name: tidy-download
description: 整理 /sdcard/Download 目录：按扩展名分类移动文件到对应文件夹
---

# 整理 Download 目录

任务匹配（整理/归类下载目录）时按以下手册行动：

1. list_files Download 列出全部文件，先报给用户当前有什么。
2. 建议分类方案（图片→Pictures/Download整理、文档→Documents/下载整理、
   压缩包→Download/archives、安装包→Download/apks），等用户确认。
3. 用户确认后用 move_file 逐个移动，每 10 个汇报一次进度。
4. 只动文件不动目录；同名冲突跳过并汇报。
5. 结束后 list_files 汇总结果，并询问是否清空回收站。
""".trimIndent() + "\n"
                )
                created.add("skills/example/SKILL.md")
            } catch (e: Exception) { /* 权限未给时静默，onResume 重试 */ }
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
