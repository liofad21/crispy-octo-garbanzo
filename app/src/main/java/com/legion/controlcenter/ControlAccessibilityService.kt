package com.legion.controlcenter

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ControlAccessibilityService : AccessibilityService() {

    companion object { @Volatile var instance: ControlAccessibilityService? = null }

    private lateinit var wm: WindowManager
    lateinit var dev: DeviceActions
    private var panel: ControlPanel? = null
    private var trigger: View? = null
    private var monitor: MonitorOverlay? = null
    private var assistant: AssistantOverlay? = null
    private var gameTools: GameToolsOverlay? = null
    fun openRaven() {
        dev.prefs.edit().putBoolean("assistant_on", true).apply()
        assistant?.show(); assistant?.toggleChat(); assistant?.bringToFront()
    }
    fun openGameTool(mode: String) {
        hidePanel()
        val tools = gameTools ?: GameToolsOverlay(this, dev).also { gameTools = it }
        tools.open(mode)
        assistant?.bringToFront()
    }
    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        val action = dev.prefs.getString("key_map_${event.keyCode}", null) ?: return false
        if (!dev.prefs.getBoolean("key_mapping_on", false)) return false
        if (event.action == android.view.KeyEvent.ACTION_UP) dev.globalAction(action)
        return true
    }
    private var lastShow = 0L
    @Volatile private var suppress = false
    private val handler = Handler(Looper.getMainLooper())

    fun postDelayed(ms: Long, r: () -> Unit) { handler.postDelayed({ r() }, ms) }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        dev = DeviceActions(this)
        addTrigger()
        assistant = AssistantOverlay(this, dev)
        refreshAssistant()
    }

    /** بتتنادى من التطبيق لما المستخدم يشغّل/يطفّي المساعدة. */
    fun refreshAssistant() {
        val on = dev.prefs.getBoolean("assistant_on", true)
        if (on) assistant?.show() else assistant?.hide()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (suppress || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        val now = System.currentTimeMillis()
        if (pkg == "com.android.systemui") {
            val cls = e.className?.toString() ?: return
            val isShade = cls.contains("QuickSettings", true) || cls.contains("QSPanel", true) ||
                    cls.contains("NotificationShade", true) || cls.contains("NotificationPanel", true)
            if (!isShade || now - lastShow < 800) return
            if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            else performGlobalAction(GLOBAL_ACTION_BACK)
            showPanel()
        } else if (panel != null && now - lastShow > 900) {
            hidePanel() // المستخدم راح لتطبيق تاني (هوم مثلًا)
        }
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({
            if (panel != null) { hidePanel(); showPanel(false) }
            assistant?.let { if (it.isShowing) { it.hide(); refreshAssistant() } }
        }, 300)
    }

    /** بيفتح لوحة النظام ورا لوحتنا ويدوس على الزر بالاسم. onFail لو الزر مالقيناهوش. */
    fun clickSystemTile(labels: List<String>, onFail: () -> Unit) {
        suppress = true
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        var tries = 0
        fun attempt() {
            var done = false
            for (w in windows) {
                val root = w.root ?: continue
                if (root.packageName?.toString() != "com.android.systemui") continue
                for (l in labels) {
                    for (n in root.findAccessibilityNodeInfosByText(l)) {
                        var c: AccessibilityNodeInfo? = n
                        while (c != null && !c.isClickable) c = c.parent
                        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { done = true; break }
                    }
                    if (done) break
                }
                if (done) break
            }
            if (done || ++tries >= 8) {
                handler.postDelayed({
                    if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    else performGlobalAction(GLOBAL_ACTION_BACK)
                    handler.postDelayed({ suppress = false }, 700)
                    if (!done) onFail()
                }, 300)
            } else handler.postDelayed({ attempt() }, 250)
        }
        handler.postDelayed({ attempt() }, 450)
    }

    private fun addTrigger() {
        val dm = resources.displayMetrics
        val v = View(this)
        var startY = 0f
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> startY = ev.rawY
                MotionEvent.ACTION_MOVE -> if (ev.rawY - startY > 60 * dm.density) { showPanel(); startY = Float.MAX_VALUE }
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, (24 * dm.density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        trigger = v
        wm.addView(v, lp)
    }

    fun showPanel(animate: Boolean = true) {
        if (panel != null) return
        lastShow = System.currentTimeMillis()
        val p = ControlPanel(this, dev, animate) { hidePanel() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        panel = p
        wm.addView(p, lp)
        assistant?.bringToFront() // الشخصية دايمًا فوق اللوحة
    }

    fun hidePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        lastShow = System.currentTimeMillis()
    }

    fun toggleMonitor() {
        val m = monitor
        if (m != null && m.isShowing) { m.hide(); return }
        monitor = MonitorOverlay(this) { monitor = null }.also { it.show() }
        assistant?.bringToFront()
    }
    fun isMonitorOn() = monitor?.isShowing == true

    override fun onDestroy() {
        hidePanel(); monitor?.hide(); assistant?.hide(); gameTools?.close()
        trigger?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        instance = null
        super.onDestroy()
    }
}
