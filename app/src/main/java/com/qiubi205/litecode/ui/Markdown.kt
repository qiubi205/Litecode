package com.qiubi205.litecode.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Minimal Markdown renderer built on SpannableStringBuilder.
 * Never throws: any internal failure returns the source unchanged.
 */
object Markdown {

    private val CODE_BG = 0xFFEEEEEE.toInt()

    fun render(src: String): CharSequence = try {
        renderInternal(src)
    } catch (t: Throwable) {
        src
    }

    private fun renderInternal(src: String): CharSequence {
        val out = SpannableStringBuilder()
        var inCodeBlock = false
        src.split('\n').forEachIndexed { index, line ->
            if (index > 0) out.append('\n')
            if (line.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock
            } else if (inCodeBlock) {
                appendCodeLine(out, line)
            } else {
                appendNormalLine(out, line)
            }
        }
        return out
    }

    private fun appendNormalLine(out: SpannableStringBuilder, line: String) {
        when {
            line.startsWith("###") -> appendHeading(out, line.removePrefix("###"), 1.1f)
            line.startsWith("##") -> appendHeading(out, line.removePrefix("##"), 1.25f)
            line.startsWith("#") -> appendHeading(out, line.removePrefix("#"), 1.4f)
            isListItem(line) -> appendListItem(out, line)
            else -> appendInline(out, line)
        }
    }

    /** 列表项：短横线或星号换成圆点，保留缩进，剩余内容走行内渲染（粗体/斜体/代码） */
    private fun appendListItem(out: SpannableStringBuilder, line: String) {
        val indent = line.length - line.trimStart().length
        if (indent > 0) out.append(" ".repeat(indent))
        val t = line.trimStart()
        if (t.startsWith("- ") || t.startsWith("* ")) {
            out.append("• ")
            appendInline(out, t.substring(2).trimStart())
        } else {
            // 数字列表：保留 "N." 前缀，后面的内容仍要行内渲染
            val dot = t.indexOf(". ")
            if (dot > 0) {
                out.append(t.substring(0, dot + 2))
                appendInline(out, t.substring(dot + 2))
            } else appendInline(out, t)
        }
    }

    private fun isListItem(line: String): Boolean {
        val t = line.trimStart()
        if (t.startsWith("- ") || t.startsWith("* ")) return true
        var i = 0
        while (i < t.length && t[i] in '0'..'9') i++
        return i > 0 && i + 1 < t.length && t[i] == '.' && t[i + 1] == ' '
    }

    private fun appendCodeLine(out: SpannableStringBuilder, line: String) {
        val start = out.length
        out.append(line)
        out.setSpan(TypefaceSpan(Typeface.MONOSPACE), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(BackgroundColorSpan(CODE_BG), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendHeading(out: SpannableStringBuilder, text: String, scale: Float) {
        val start = out.length
        appendInline(out, text.trimStart())
        out.setSpan(RelativeSizeSpan(scale), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Appends [text] applying `code` / **bold** / *italic* spans where markers match. */
    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end >= 0) {
                    val start = out.length
                    out.append(text.substring(i + 1, end))
                    out.setSpan(TypefaceSpan(Typeface.MONOSPACE), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1
                    continue
                }
            } else if (c == '*' && i + 1 < text.length && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end >= 0) {
                    val start = out.length
                    appendInline(out, text.substring(i + 2, end))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                    continue
                }
            } else if (c == '*') {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    val start = out.length
                    appendInline(out, text.substring(i + 1, end))
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
    }
}
