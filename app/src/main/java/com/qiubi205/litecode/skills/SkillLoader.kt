package com.qiubi205.litecode.skills

import android.os.Environment
import java.io.File

object SkillLoader {

    data class Skill(val name: String, val description: String, val path: File)

    private fun skillsRoot(): File {
        val external = Environment.getExternalStorageDirectory()
        val base = if (external != null) external.absolutePath else "/sdcard"
        return File(base, "Litecode/skills")
    }

    fun listSkills(): List<Skill> {
        val root = skillsRoot()
        if (!root.exists() || !root.isDirectory) return emptyList()
        val result = ArrayList<Skill>()
        val dirs = root.listFiles()
        if (dirs == null) return emptyList()
        for (dir in dirs.sortedBy { it.name }) {
            if (!dir.isDirectory) continue
            val skillFile = File(dir, "SKILL.md")
            if (!skillFile.isFile) continue
            try {
                val parsed = parseFrontmatter(skillFile) ?: continue
                if (parsed.first.isEmpty()) continue
                result.add(Skill(parsed.first, parsed.second, skillFile))
            } catch (e: Exception) {
                // 单技能解析失败，跳过该技能
            }
        }
        return result
    }

    // 解析文件开头 YAML frontmatter（--- 与 --- 之间）的 name: 和 description: 单行键
    private fun parseFrontmatter(file: File): Pair<String, String>? {
        val text = file.readText()
        val lines = text.lines()
        var name = ""
        var description = ""
        var inFrontmatter = false
        var closed = false
        var counted = 0
        for (raw in lines) {
            counted++
            if (counted > 200) break // 防御超大文件：frontmatter 只看开头
            val line = raw.trimEnd()
            if (counted == 1) {
                if (line.trim() == "---") {
                    inFrontmatter = true
                    continue
                } else {
                    return null // 没有 frontmatter
                }
            }
            if (inFrontmatter) {
                val trimmed = line.trim()
                if (trimmed == "---") {
                    closed = true
                    break
                }
                if (trimmed.startsWith("name:")) {
                    name = trimmed.removePrefix("name:").trim().trim('"', '\'')
                } else if (trimmed.startsWith("description:")) {
                    description = trimmed.removePrefix("description:").trim().trim('"', '\'')
                }
            }
        }
        if (!inFrontmatter || !closed) return null
        return Pair(name, description)
    }

    fun promptBlock(maxChars: Int = 4000): String {
        val skills = listSkills()
        if (skills.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("# 可用技能\n")
        for (skill in skills) {
            sb.append("- ")
                .append(skill.name)
                .append(": ")
                .append(skill.description)
                .append("（手册：")
                .append(skill.path.absolutePath)
                .append("）\n")
        }
        sb.append("任务与某技能描述匹配时，先用 read_file 读取该技能的 SKILL.md 全文，再按手册行动。没有匹配技能时忽略本清单。")
        var out = sb.toString()
        if (out.length > maxChars) {
            val suffix = "\n（内容过长，已截断至 $maxChars 字符）"
            val keep = maxChars - suffix.length
            out = if (keep > 0) out.take(keep) + suffix else suffix
        }
        return out
    }
}
