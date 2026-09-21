package com.lumi.lumi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exposes the current screen as an indexed table of controls and executes an
 * action chosen by index. Coordinates are resolved here and never accepted from
 * the caller, so a model can only pick among elements it was actually shown.
 */
class LumiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: LumiAccessibilityService? = null
            private set

        private const val MAX_NODES = 80
        private const val MAX_DEPTH = 24
    }

    private val cache = HashMap<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        cache.clear()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun observe(): String {
        val out = JSONObject()
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        }
        if (root == null) {
            cache.clear()
            out.put("nodes", JSONArray())
            out.put("error", "无法读取当前窗口，请确认屏幕已解锁且目标应用在前台")
            return out.toString()
        }
        val found = ArrayList<AccessibilityNodeInfo>()
        collect(root, found, 0)
        cache.clear()
        val arr = JSONArray()
        for ((i, n) in found.withIndex()) {
            cache[i + 1] = n
            val e = JSONObject()
            e.put("id", i + 1)
            e.put("role", roleOf(n))
            val label = labelOf(n)
            if (label.isNotEmpty()) e.put("label", label)
            val value = valueOf(n)
            if (value.isNotEmpty()) e.put("value", value)
            arr.put(e)
        }
        out.put("nodes", arr)
        out.put("app", str(root.packageName))
        return out.toString()
    }

    fun act(op: String, index: Int, text: String?): String {
        val out = JSONObject()
        when (op) {
            "BACK" -> return out.put("acted", performGlobalAction(GLOBAL_ACTION_BACK)).toString()
            "HOME" -> return out.put("acted", performGlobalAction(GLOBAL_ACTION_HOME)).toString()
            "WAIT" -> return out.put("acted", true).toString()
            "DONE", "BLOCKED" -> {
                out.put("acted", false)
                out.put("error", "$op 由代码处理，不应到达执行层")
                return out.toString()
            }
        }
        val node = cache[index]
        if (node == null) {
            out.put("acted", false)
            out.put("error", "编号 $index 不存在，或界面已重新观察过")
            return out.toString()
        }
        if (!node.refresh()) {
            out.put("acted", false)
            out.put("error", "目标已失效，界面可能已经变化")
            return out.toString()
        }
        val acted = when (op) {
            "TAP" -> tap(node)
            "TYPE" -> type(node, text ?: "")
            "SCROLL_UP" -> scroll(node, false)
            "SCROLL_DOWN" -> scroll(node, true)
            else -> false
        }
        if (!acted && op != "TAP") out.put("error", "$op 在当前目标上不被支持")
        out.put("acted", acted)
        return out.toString()
    }

    private fun collect(n: AccessibilityNodeInfo?, out: ArrayList<AccessibilityNodeInfo>, depth: Int) {
        if (n == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
        if (n.isVisibleToUser && interesting(n)) out.add(n)
        val kids = try {
            n.children
        } catch (e: Exception) {
            null
        } ?: return
        for (c in kids) collect(c, out, depth + 1)
    }

    private fun interesting(n: AccessibilityNodeInfo): Boolean =
        n.isClickable || n.isLongClickable || n.isScrollable || isEditable(n)

    private fun isEditable(n: AccessibilityNodeInfo): Boolean {
        if (n.isEditable) return true
        val c = n.className?.toString() ?: return false
        return c.endsWith("EditText") || c.endsWith("AutoCompleteTextView")
    }

    private fun roleOf(n: AccessibilityNodeInfo): String {
        val c = n.className?.toString() ?: ""
        return when {
            isEditable(n) -> "输入框"
            c.endsWith("Switch") || c.endsWith("ToggleButton") -> "开关"
            c.endsWith("CheckBox") -> "复选框"
            c.endsWith("RadioButton") -> "单选项"
            c.endsWith("Button") -> "按钮"
            c.contains("SeekBar") || c.contains("Slider") -> "滑块"
            n.isScrollable -> "可滚动区域"
            c.endsWith("ImageView") -> "图片"
            c.endsWith("TextView") -> "文本"
            else -> "控件"
        }
    }

    private fun labelOf(n: AccessibilityNodeInfo): String {
        val desc = str(n.contentDescription)
        if (desc.isNotEmpty()) return clip(desc)
        if (isEditable(n)) {
            val hint = if (Build.VERSION.SDK_INT >= 26) str(n.hintText) else ""
            return if (hint.isNotEmpty()) clip(hint) else ""
        }
        return clip(str(n.text))
    }

    private fun valueOf(n: AccessibilityNodeInfo): String {
        if (isEditable(n)) return clip(str(n.text))
        if (n.isCheckable) return if (n.isChecked) "已选中" else "未选中"
        if (n.isSelected) return "已选中"
        return ""
    }

    private fun tap(n: AccessibilityNodeInfo): Boolean {
        if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val b = Rect()
        n.getBoundsInScreen(b)
        if (b.width() <= 0 || b.height() <= 0) return false
        return gestureTap(b.exactCenterX(), b.exactCenterY())
    }

    private fun gestureTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        return try {
            val path = Path()
            path.moveTo(x, y)
            val stroke = GestureDescription.StrokeDescription(path, 0, 60)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) {
            false
        }
    }

    private fun type(n: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun scroll(n: AccessibilityNodeInfo, down: Boolean): Boolean {
        val action = if (down) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isScrollable && cur.performAction(action)) return true
            cur = try {
                cur.parent
            } catch (e: Exception) {
                null
            }
            depth += 1
        }
        return cache.values.any { it.isScrollable && it.performAction(action) }
    }

    private fun str(cs: CharSequence?): String = cs?.toString()?.trim() ?: ""

    private fun clip(s: String): String = if (s.length > 60) s.substring(0, 60) + "…" else s
}
