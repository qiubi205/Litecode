package com.qiubi205.pocketharness.tools

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 文件工具集：/sdcard 读写（MANAGE_EXTERNAL_STORAGE / requestLegacyExternalStorage）。
 * 与 OpenClaw 容器共享 /sdcard，天然互通。
 */
object FileTools {

    fun base(): File = Environment.getExternalStorageDirectory()

    fun listFiles(relPath: String): JSONObject {
        val dir = resolve(relPath)
        val arr = JSONArray()
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            arr.put(JSONObject()
                .put("name", f.name)
                .put("type", if (f.isDirectory) "dir" else "file")
                .put("size", if (f.isFile) f.length() else JSONObject.NULL))
        }
        return JSONObject().put("path", dir.absolutePath).put("entries", arr)
    }

    fun readFile(relPath: String, maxBytes: Long = 200_000): JSONObject {
        val f = resolve(relPath)
        require(f.isFile) { "不是文件: $relPath" }
        val bytes = f.readBytes()
        val truncated = bytes.size > maxBytes
        val content = bytes.decodeToString(0, if (truncated) maxBytes.toInt() else bytes.size)
        return JSONObject().put("path", f.absolutePath).put("size", bytes.size)
            .put("truncated", truncated).put("content", content)
    }

    fun writeFile(relPath: String, content: String, append: Boolean = false): JSONObject {
        val f = resolve(relPath)
        f.parentFile?.mkdirs()
        if (append) f.appendText(content) else f.writeText(content)
        return JSONObject().put("path", f.absolutePath).put("written", content.length.toLong())
    }

    fun delete(recursive: Boolean = false): Nothing =
        throw UnsupportedOperationException("删除操作不在 agent 自动工具内，请手动处理")

    private fun resolve(relPath: String): File {
        val base = base()
        val f = if (relPath.startsWith("/")) File(relPath) else File(base, relPath)
        val canon = f.canonicalFile
        // 防穿越：必须落在 /sdcard 下
        require(canon.path.startsWith(base.canonicalPath)) { "路径越界: $relPath" }
        return canon
    }

    // ---------- OpenAI function 定义 ----------

    val definitions: JSONArray = JSONArray()
        .put(fn("list_files", "列出目录内容", JSONObject()
            .put("path", JSONObject().put("type", "string").put("description", "相对 /sdcard 的路径，如 Download 或 Pictures/xx.png")))
        )
        .put(fn("read_file", "读取文本文件内容（前200KB）", JSONObject()
            .put("path", JSONObject().put("type", "string")))
        )
        .put(fn("write_file", "写文件（UTF-8文本）", JSONObject()
            .put("path", JSONObject().put("type", "string"))
            .put("content", JSONObject().put("type", "string"))
            .put("append", JSONObject().put("type", "boolean").put("description", "true=追加，默认覆盖")))
        )

    private fun fn(name: String, desc: String, params: JSONObject): JSONObject =
        JSONObject().put("type", "function")
            .put("function", JSONObject()
                .put("name", name).put("description", desc)
                .put("parameters", JSONObject()
                    .put("type", "object").put("properties", params)
                    .put("required", JSONArray().put("path"))))
}
