package com.qiubi205.pocketharness.tools

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 文件工具集：/sdcard 读写（MANAGE_EXTERNAL_STORAGE / requestLegacyExternalStorage）。
 * 与 OpenClaw 容器共享 /sdcard，天然互通。
 */
object FileTools {

    /** 图片/文件体积上限：>4MB 先压到长边 2048 再编码，防 base64 撑爆请求 */
    private const val RAW_LIMIT = 4L * 1024 * 1024

    fun base(): File = Environment.getExternalStorageDirectory()

    fun listFiles(relPath: String): JSONObject {
        val dir = resolve(relPath)
        val arr = org.json.JSONArray()
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

    /**
     * 用系统默认应用打开文件（ACTION_VIEW）。
     * APK/安装包需要用户确认；普通文件走系统查看器。一步到位，不再手动导航。
     */
    fun openFile(relPath: String, androidContext: android.content.Context): JSONObject {
        val f = resolve(relPath)
        require(f.isFile) { "不是文件: $relPath" }
        return try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                androidContext, "${androidContext.packageName}.fileprovider", f)
            val mime = guessMime(f)
            val it = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            androidContext.startActivity(it)
            JSONObject().put("ok", true).put("opened", f.absolutePath).put("mime", mime)
        } catch (e: Exception) {
            JSONObject().put("error", "打开失败：${e.message}")
        }
    }

    /** 读图片为 base64（过大自动压缩），供 look_at_file 视觉问答用。 */
    fun readImageBase64(relPath: String): Pair<String, String>? {
        val f = resolve(relPath)
        require(f.isFile) { "不是文件: $relPath" }
        val mime = guessMime(f)
        if (!(mime.startsWith("image/"))) return null
        val raw = f.readBytes()
        if (raw.size <= RAW_LIMIT) {
            return Pair(Base64.encodeToString(raw, Base64.NO_WRAP), mime)
        }
        // 压缩：长边 2048，JPEG 85
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val scale = 2048.0 / maxOf(bmp.width, bmp.height)
        val scaled = if (scale < 1.0) Bitmap.createScaledBitmap(
            bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Pair(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), "image/jpeg")
    }

    fun guessMime(f: File): String = when (f.extension.lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"
        "gif" -> "image/gif"; "bmp" -> "image/bmp"
        "pdf" -> "application/pdf"
        "txt", "md", "log", "json", "kt", "java", "py", "sh", "xml", "csv" -> "text/plain"
        "mp3", "wav", "flac", "ogg", "m4a" -> "audio/*"
        "mp4", "mkv", "webm", "avi" -> "video/*"
        "apk" -> "application/vnd.android.package-archive"
        else -> "*/*"
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

    val definitions: org.json.JSONArray = org.json.JSONArray()
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
        .put(fn("open_file", "用系统默认应用打开文件（一步到位，无需手动导航）", JSONObject()
            .put("path", JSONObject().put("type", "string").put("description", "相对 /sdcard 的文件路径"))
            .put("needs_confirm", JSONObject().put("type", "boolean").put("description", "APK安装等敏感操作须为true，表示已获用户确认")))
        )
        .put(fn("look_at_file", "看图：识别图片文件内容并返回文字描述（需多模态模型）", JSONObject()
            .put("path", JSONObject().put("type", "string").put("description", "相对 /sdcard 的图片路径，如 Download/xx.png"))
            .put("question", JSONObject().put("type", "string").put("description", "想问关于图片的问题，如图片里有什么/验证码内容")))
        )
        .put(fn("open_file", "用系统默认应用打开文件（一步到位，无需手动导航）", JSONObject()
            .put("path", JSONObject().put("type", "string").put("description", "相对 /sdcard 的文件路径"))
            .put("needs_confirm", JSONObject().put("type", "boolean").put("description", "APK安装等敏感操作须为true，表示已获用户确认")))
        )

    private fun fn(name: String, desc: String, params: JSONObject): JSONObject =
        JSONObject().put("type", "function")
            .put("function", JSONObject()
                .put("name", name).put("description", desc)
                .put("parameters", JSONObject()
                    .put("type", "object").put("properties", params)
                    .put("required", JSONArray().put("path"))))
}
