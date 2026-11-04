package com.legion.controlcenter

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

/**
 * المساعدة: شخصية عايمة فوق أي حاجة (اسحبها لأي مكان). ضغطة عليها تفتح الشات.
 * الدماغ Gemini، والصوت صوت بنت من Gemini TTS، وبتنفّذ أوامر التابلت عن طريق DeviceActions.
 */
class AssistantOverlay(private val svc: ControlAccessibilityService, private val dev: DeviceActions) {

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val prefs = dev.prefs
    private val client = GeminiClient({ prefs.getString("gemini_key", "") ?: "" }) { name, args -> runTool(name, args) }

    private var bubble: ImageView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var chat: LinearLayout? = null
    private var log: TextView? = null
    private var logScroll: ScrollView? = null
    private var input: EditText? = null
    private var micBtn: GlassButton? = null
    private var recognizer: SpeechRecognizer? = null
    private var track: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private val pulses = ArrayList<ObjectAnimator>()
    @Volatile private var busy = false

    private fun metrics() = DisplayMetrics().also {
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it)
    }

    val isShowing get() = bubble != null

    // ================= الشخصية العايمة =================
    fun show() {
        if (bubble != null) return
        val size = svc.dpi(84)
        val img = ImageView(svc).apply {
            setImageResource(R.drawable.avatar)
            scaleType = ImageView.ScaleType.FIT_CENTER
            elevation = svc.dpf(8f)
        }
        val m = metrics()
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubble_x", m.widthPixels - size - svc.dpi(12))
            y = prefs.getInt("bubble_y", m.heightPixels / 2)
        }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        img.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - sx) > 12 || Math.abs(e.rawY - sy) > 12) moved = true
                    if (moved) {
                        val mm = metrics()
                        lp.x = (ox + (e.rawX - sx)).toInt().coerceIn(0, mm.widthPixels - size)
                        lp.y = (oy + (e.rawY - sy)).toInt().coerceIn(0, mm.heightPixels - size)
                        try { wm.updateViewLayout(img, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit().putInt("bubble_x", lp.x).putInt("bubble_y", lp.y).apply()
                    else toggleChat()
                }
            }
            true
        }
        bubble = img; bubbleLp = lp
        wm.addView(img, lp)
    }

    /** يرجّع الشخصية فوق كل الشبابيك (لما لوحة تانية تتفتح). */
    fun bringToFront() {
        val b = bubble ?: return; val lp = bubbleLp ?: return
        chat?.let { c -> try { val l = c.layoutParams; wm.removeView(c); wm.addView(c, l) } catch (_: Exception) {} }
        try { wm.removeView(b); wm.addView(b, lp) } catch (_: Exception) {}
    }

    fun hide() {
        closeChat()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        stopSpeaking(); recognizer?.destroy(); recognizer = null
        tts?.shutdown(); tts = null
    }

    // ================= الشات =================
    fun toggleChat() { if (chat == null) openChat() else closeChat() }

    private fun openChat() {
        val m = metrics()
        val w = minOf(svc.dpi(420), (m.widthPixels * 0.92f).toInt())
        val h = minOf(svc.dpi(520), (m.heightPixels * 0.6f).toInt())
        val root = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(svc, 24f, true)
            setPadding(svc.dpi(12), svc.dpi(12), svc.dpi(12), svc.dpi(12))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val title = TextView(svc).apply {
            text = "Raven  •  مساعدتك الذكية"; setTextColor(Color.WHITE); textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        root.addView(title)
        val sv = ScrollView(svc).apply { isVerticalScrollBarEnabled = false }
        val tv = TextView(svc).apply { setTextColor(Color.parseColor("#FFEBDADA")); textSize = 14f; setLineSpacing(0f, 1.2f) }
        sv.addView(tv)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = svc.dpi(8); bottomMargin = svc.dpi(8) })

        val row = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        val et = EditText(svc).apply {
            hint = "اكتبلي أو اضغط المايك"; setHintTextColor(Color.parseColor("#99C9B4B6")); setTextColor(Color.WHITE)
            textSize = 14f; maxLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = Ui.glass(svc, 18f)
            setPadding(svc.dpi(14), svc.dpi(8), svc.dpi(14), svc.dpi(8))
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        row.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        val mic = GlassButton(svc, GlassButton.Shape.ORB, Glyphs.get(svc, R.drawable.g_mic), "", 46f).apply { setOnClickListener { listen() } }
        val snd = GlassButton(svc, GlassButton.Shape.ORB, Glyphs.get(svc, R.drawable.g_next), "", 46f).apply { on = true; setOnClickListener { send() } }
        row.addView(mic, LinearLayout.LayoutParams(svc.dpi(54), -2).apply { marginStart = svc.dpi(6) })
        row.addView(snd, LinearLayout.LayoutParams(svc.dpi(54), -2))
        root.addView(row)

        log = tv; logScroll = sv; input = et; micBtn = mic; chat = root
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = svc.dpi(12); y = svc.dpi(24)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(root, lp)
        if (tv.text.isEmpty()) say("Raven", "أهلاً! أنا Raven. اطلبي أو اطلب أي حاجة في التابلت: السطوع، الصوت، الواي فاي، فتح تطبيق، أي حاجة.")
        bringToFront()
    }

    fun closeChat() {
        chat?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        chat = null; log = null; input = null; logScroll = null; micBtn = null
        recognizer?.cancel()
    }

    private fun say(who: String, text: String) {
        val l = log ?: return
        l.append((if (l.text.isEmpty()) "" else "\n\n") + who + ": " + text)
        logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // ================= إرسال وتنفيذ =================
    private fun send() {
        val t = input?.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return
        input?.setText("")
        ask(t)
    }

    private fun ask(text: String) {
        if (busy) { say("Raven", "ثانية واحدة، بخلص اللي في إيدي."); return }
        if ((prefs.getString("gemini_key", "") ?: "").isBlank()) {
            say("Raven", "محتاجة مفتاح Gemini API. افتح تطبيق Legion Control Center وحطه في خانة المساعد.")
            return
        }
        say("إنت", text)
        busy = true; stopSpeaking()
        thread {
            try {
                val reply = client.chat(text)
                ui.post { say("Raven", reply) }
                speak(reply)
            } catch (e: Exception) {
                val msg = when {
                    e.message == "NO_KEY" -> "المفتاح مش موجود."
                    e.message?.startsWith("HTTP 429") == true -> "وصلنا لحد الاستخدام المجاني لـ Gemini. جرّب بعد شوية. (${e.message})"
                    else -> "حصل خطأ: ${e.message}"
                }
                ui.post { say("Raven", msg) }
            } finally { busy = false }
        }
    }

    /** الأدوات اللي Gemini بينفذها. بتتنفذ على الـ thread الرئيسي وبترجع نتيجة نصية. */
    private fun runTool(name: String, a: JSONObject): String {
        var out = "error"
        val latch = java.util.concurrent.CountDownLatch(1)
        ui.post {
            out = try {
                when (name) {
                    "set_brightness" -> { val p = a.optInt("percent", 50).coerceIn(1, 100); if (dev.setBrightness(p * 255 / 100)) "brightness set to $p%" else "failed: WRITE_SETTINGS permission missing" }
                    "set_volume" -> { val p = a.optInt("percent", 50).coerceIn(0, 100); dev.setVolume(p * dev.maxVolume() / 100); "volume set to $p%" }
                    "set_toggle" -> dev.set(a.optString("name"), a.optBoolean("enabled"))
                    "set_refresh_rate" -> { val hz = a.optInt("hz", 120); if (dev.setRefresh(hz.toFloat())) "refresh rate requested: $hz Hz (device may ignore)" else "failed" }
                    "set_performance_mode" -> {
                        val i = listOf("power_saving", "balanced", "performance", "extreme").indexOf(a.optString("mode"))
                        if (i < 0) "unknown mode" else { dev.setPerformanceMode(i) { s -> try { svc.startActivity(s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }; "mode set" }
                    }
                    "media_control" -> {
                        val k = when (a.optString("action")) { "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
                        dev.mediaKey(k); "sent"
                    }
                    "system_action" -> dev.globalAction(a.optString("action"))
                    "open_app" -> { closeChat(); dev.openApp(a.optString("name")) }
                    "open_control_center" -> { closeChat(); svc.showPanel(); "opened" }
                    "toggle_performance_monitor" -> { svc.toggleMonitor(); "toggled" }
                    "get_status" -> dev.status()
                    else -> "unknown tool"
                }
            } catch (e: Exception) { "error: ${e.message}" }
            latch.countDown()
        }
        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    // ================= الصوت =================
    private fun speak(text: String) {
        startPulse()
        try {
            val pcm = client.speak(text)
            val minBuf = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size))
                .setTransferMode(AudioTrack.MODE_STATIC).build()
            track = t
            t.write(pcm, 0, pcm.size); t.play()
            val ms = pcm.size / 2 * 1000L / 24000
            Thread.sleep(ms + 200)
            t.release(); if (track === t) track = null
        } catch (e: Exception) {
            // احتياطي: صوت النظام لو Gemini TTS مش متاح
            ui.post { fallbackTts(text) }
        } finally { ui.post { stopPulse() } }
    }

    private fun fallbackTts(text: String) {
        if (tts == null) {
            tts = TextToSpeech(svc) { s ->
                if (s == TextToSpeech.SUCCESS) { tts?.language = Locale("ar"); tts?.setPitch(1.2f); tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "legion") }
            }
        } else { tts?.setPitch(1.2f); tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "legion") }
    }

    private fun stopSpeaking() {
        try { track?.stop(); track?.release() } catch (_: Exception) {}
        track = null; tts?.stop()
    }

    private fun startPulse() {
        ui.post {
            val b = bubble ?: return@post
            stopPulse()
            for (prop in listOf(View.SCALE_X, View.SCALE_Y)) {
                pulses += ObjectAnimator.ofFloat(b, prop, 1f, 1.12f).apply {
                    duration = 500; repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE; start()
                }
            }
        }
    }
    private fun stopPulse() {
        pulses.forEach { it.cancel() }; pulses.clear()
        bubble?.apply { scaleX = 1f; scaleY = 1f }
    }

    // ================= المايك (سماع) =================
    private fun listen() {
        if (svc.checkSelfPermission("android.permission.RECORD_AUDIO") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            say("Raven", "محتاجة صلاحية المايك. افتح التطبيق واضغط 'صلاحية المايك'."); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(svc)) { say("Raven", "خدمة التعرف على الصوت مش متاحة على الجهاز."); return }
        stopSpeaking()
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(svc)
        recognizer = r
        micBtn?.on = true
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                micBtn?.on = false
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) ask(t) else say("Raven", "ماسمعتش حاجة، جرّب تاني.")
            }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { input?.setText(it) }
            }
            override fun onError(e: Int) { micBtn?.on = false; say("Raven", "ماسمعتش كويس (كود $e)، جرّب تاني.") }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }
}
