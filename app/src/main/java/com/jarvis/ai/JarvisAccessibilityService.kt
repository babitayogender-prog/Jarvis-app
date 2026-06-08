package com.jarvis.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
            private set
        fun isRunning() = instance != null
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onDestroy() { super.onDestroy(); instance = null }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(), null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long = 300) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(), null, null)
    }

    fun scrollDown() {
        val h = resources.displayMetrics.heightPixels.toFloat()
        val w = resources.displayMetrics.widthPixels.toFloat()
        swipe(w/2, h*0.7f, w/2, h*0.3f, 400)
    }
    fun scrollUp() {
        val h = resources.displayMetrics.heightPixels.toFloat()
        val w = resources.displayMetrics.widthPixels.toFloat()
        swipe(w/2, h*0.3f, w/2, h*0.7f, 400)
    }

    fun pressHome()    = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressBack()    = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, text.lowercase()) ?: return false
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true
        }
        var p = node.parent
        while (p != null) {
            if (p.isClickable) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
            p = p.parent
        }
        return false
    }

    private fun findNodeByText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val t = node.text?.toString()?.lowercase() ?: ""
        val d = node.contentDescription?.toString()?.lowercase() ?: ""
        if (t.contains(text) || d.contains(text)) return node
        for (i in 0 until node.childCount) {
            findNodeByText(node.getChild(i), text)?.let { return it }
        }
        return null
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Screen unavailable"
        val sb = StringBuilder()
        collect(root, sb)
        return sb.toString().trim().ifEmpty { "No text on screen" }
    }
    private fun collect(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        node.text?.let { if (it.isNotBlank()) sb.append(it).append(" | ") }
        for (i in 0 until node.childCount) collect(node.getChild(i), sb)
    }

    fun launchApp(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }
    fun findPackageByAppName(name: String): String? {
        val pm = packageManager
        for (app in pm.getInstalledApplications(0)) {
            if (pm.getApplicationLabel(app).toString().lowercase().contains(name.lowercase()))
                return app.packageName
        }
        return null
    }
}
