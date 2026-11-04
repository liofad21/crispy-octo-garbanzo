package com.legion.controlcenter

import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray

/** User-configured system shortcuts, not an in-game touch mapper. */
class GameToolsOverlay(private val svc: ControlAccessibilityService, private val dev: DeviceActions) {
    private val wm = svc.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    private var window: View? = null
    private val actions = arrayOf("back", "home", "recents", "screenshot", "split_screen")
    private var generation = 0
    fun close() {
        generation++
        window?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        window = null
    }
    fun open(mode: String) {
        close()
        val col = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(svc, 20f, true)
            setPadding(svc.dpi(16), svc.dpi(16), svc.dpi(16), svc.dpi(16))
        }
        fun text(value: String) { col.addView(TextView(svc).apply { text = value; setTextColor(Color.WHITE); textSize = 14f }) }
        fun button(label: String, action: () -> Unit) {
            col.addView(Button(svc).apply { text = label; setOnClickListener { action() } })
        }
        text(when (mode) { "keys" -> "Key Mapping — system shortcuts"; "macros" -> "Macros — system actions"; else -> "Floating Tools" })
        if (mode == "keys") {
            text("Physical key code (e.g. 131 = F1). Only explicitly mapped keys are intercepted.")
            val code = EditText(svc).apply { hint = "Key code"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE) }
            col.addView(code)
            val choice = Spinner(svc).apply { adapter = ArrayAdapter(svc, android.R.layout.simple_spinner_dropdown_item, actions) }
            col.addView(choice)
            val enabled = Switch(svc).apply {
                text = "Enable mappings"; setTextColor(Color.WHITE)
                isChecked = dev.prefs.getBoolean("key_mapping_on", false)
                setOnCheckedChangeListener { _, v -> dev.prefs.edit().putBoolean("key_mapping_on", v).apply() }
            }
            col.addView(enabled)
            button("Save mapping") {
                val key = code.text.toString().toIntOrNull()
                if (key == null || key <= 0 || key == 26) dev.toast("Enter a valid key code other than Power")
                else { dev.prefs.edit().putString("key_map_$key", actions[choice.selectedItemPosition]).apply(); dev.toast("Mapping saved") }
            }
            button("Remove mapping") {
                code.text.toString().toIntOrNull()?.let { dev.prefs.edit().remove("key_map_$it").apply() }
            }
        } else if (mode == "macros") {
            text("Choose a sequence of system actions; 1 second between steps. No automatic loop.")
            val sequence = ArrayList<String>()
            try {
                val saved = JSONArray(dev.prefs.getString("macro_sequence", "[]"))
                for (i in 0 until saved.length()) saved.optString(i).takeIf { it in actions }?.let { sequence.add(it) }
            } catch (_: Exception) {}
            val status = TextView(svc).apply { setTextColor(Color.WHITE); text = sequence.joinToString(" → ") }
            col.addView(status)
            val choice = Spinner(svc).apply { adapter = ArrayAdapter(svc, android.R.layout.simple_spinner_dropdown_item, actions) }
            col.addView(choice)
            fun save() { dev.prefs.edit().putString("macro_sequence", JSONArray(sequence).toString()).apply(); status.text = sequence.joinToString(" → ") }
            button("Add step") { if (sequence.size < 20) { sequence.add(actions[choice.selectedItemPosition]); save() } }
            button("Clear") { sequence.clear(); save() }
            button("Run saved sequence") {
                val steps = sequence.toList()
                if (steps.isEmpty()) dev.toast("Add a step first") else {
                    close()
                    val token = generation
                    steps.forEachIndexed { i, action -> svc.postDelayed(800L + i * 1000L) { if (generation == token) dev.globalAction(action) } }
                    dev.toast("Macro started; reopen tools to cancel")
                }
            }
        } else {
            text("Floating system shortcuts (not an arbitrary app in freeform mode).")
            actions.forEach { action -> button(action) { close(); dev.globalAction(action) } }
            button("Raven") { close(); svc.openRaven() }
            button("Performance Monitor") { svc.toggleMonitor() }
        }
        button("Close / cancel pending macro") { close() }
        val scroll = ScrollView(svc).apply { addView(col) }
        val dm = svc.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            minOf(svc.dpi(360), (dm.widthPixels * 0.9f).toInt()),
            minOf(svc.dpi(560), (dm.heightPixels * 0.8f).toInt()),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        window = scroll
        try { wm.addView(scroll, lp) } catch (e: Exception) { window = null; dev.toast("Unable to open tools: ${e.message}") }
    }
}
