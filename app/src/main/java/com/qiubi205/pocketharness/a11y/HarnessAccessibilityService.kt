package com.qiubi205.pocketharness.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍引擎：屏幕感知 + 动作执行。
 * Agent 的"手和眼"。单例持有，工具层通过 instance() 调用。
 */
class HarnessAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: HarnessAccessibilityService? = null
        fun instance(): HarnessAccessibilityService? = instance
        fun isReady(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 按需轮询，不订阅推送 */ }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---------- 感知 ----------

    /** 把当前屏幕控件树序列化成紧凑文本，作为模型上下文 */
    fun dumpWindowTree(maxNodes: Int = 120): String {
        val root = rootInActiveWindow ?: return "<screen>无活动窗口（可能需要开启无障碍或屏幕已熄灭）</screen>"
        val sb = StringBuilder("<screen package=\"${root.packageName}\">\n")
        var count = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= maxNodes || depth > 12) return
            val r = Rect(); node.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) return
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
            val id = node.viewIdResourceName?.substringAfter(':')?.let { " id=$it" } ?: ""
            val txt = node.text?.toString()?.take(40)?.let { " text=\"$it\"" } ?: ""
            val desc = node.contentDescription?.toString()?.take(40)?.let { " desc=\"$it\"" } ?: ""
            val clickable = if (node.isClickable) " [点击]" else ""
            val editable = if (node.isEditable) " [可输入]" else ""
            val scroll = if (node.isScrollable) " [可滚动]" else ""
            sb.append("  ".repeat(depth)).append(cls).append(id).append(txt).append(desc)
                .append(clickable).append(editable).append(scroll)
                .append(" <${r.centerX()},${r.centerY()}>\n")
            count++
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 1)
        sb.append("</screen>")
        return sb.toString()
    }

    // ---------- 动作 ----------

    fun tap(x: Float, y: Float): Boolean = gesture(Path().apply { moveTo(x, y) }, 60)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 300): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /** 向可输入焦点（或按屏幕坐标定位的输入框）写入文本 */
    fun inputText(text: String, x: Float? = null, y: Float? = null): Boolean {
        var target = if (x != null && y != null) {
            tap(x, y)
            Thread.sleep(300)
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } else rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null && x != null && y != null) {
            // 坐标兜底：找包含该点的可编辑节点
            val r = Rect()
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                node.getBoundsInScreen(r)
                if (r.contains(x.toInt(), y.toInt()) && node.isEditable) return node
                for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                return null
            }
            target = find(rootInActiveWindow)
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ?: false
    }

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
