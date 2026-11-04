package com.legion.controlcenter

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.*
import kotlin.math.abs

class ControlPanel(
    private val svc: ControlAccessibilityService,
    private val dev: DeviceActions,
    animateIn: Boolean,
    private val onClose: () -> Unit
) : FrameLayout(svc) {

    private val prefs = dev.prefs
    private val refreshers = ArrayList<() -> Unit>()
    private val hzBtns = ArrayList<GlassButton>()
    private val perfBtns = ArrayList<GlassButton>()
    private val hzValues = listOf(60, 90, 120, 144)

    private val dm = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        (svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
    }
    private val land = dm.widthPixels > dm.heightPixels

    private val thumb = ImageView(svc)
    private val mediaTitle = tv("Media", 15f, true)
    private val mediaSub = tv("Not Playing", 12f, false, Ui.TEXT_DIM)
    private val mediaBar = MiniBar(svc)
    private lateinit var playBtn: GlassButton
    private var lastArt: Bitmap? = null
    private var artIsDefault = true

    private val ticker = object : Runnable {
        override fun run() { refreshAll(); postDelayed(this, 1000) }
    }

    private fun d(v: Int) = context.dpi(v)
    private fun refreshAll() = refreshers.forEach { try { it() } catch (_: Exception) {} }
    private fun toast(t: String) = dev.toast(t)

    private fun openIntent(i: Intent) {
        try { svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); onClose() }
        catch (e: Exception) { toast("غير متاح على جهازك") }
    }

    private fun tv(t: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(svc).apply {
        text = t; textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun glyphView(res: Int, sizeDp: Int, tint: Int = Ui.GLYPH_OFF) = ImageView(svc).apply {
        setImageBitmap(Glyphs.get(svc, res)); scaleType = ImageView.ScaleType.FIT_CENTER
        setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        layoutParams = LinearLayout.LayoutParams(d(sizeDp), d(sizeDp))
    }

    private fun LinearLayout.put(v: View, top: Int = 0, w: Int = -1, h: Int = -2) =
        addView(v, LinearLayout.LayoutParams(w, h).apply { topMargin = d(top) })

    private fun section(): LinearLayout = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL; background = Ui.glass(svc, 22f)
        setPadding(d(10), d(8), d(10), d(10))
    }

    private fun header(res: Int, title: String): View = LinearLayout(svc).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(d(4), d(2), d(4), d(6))
        addView(glyphView(res, 20))
        addView(tv(title, 14f, true).apply { setPadding(d(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(glyphView(R.drawable.g_chev, 14))
    }

    // =========================================================
    init {
        setBackgroundColor(Color.BLACK)
        isFocusableInTouchMode = true
        setOnClickListener { onClose() }

        val artW = (dm.widthPixels * (if (land) 0.34f else 0.40f)).toInt()
        val art = ArtView(svc)
        var downY = 0f
        art.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = e.rawY
                MotionEvent.ACTION_UP -> if (downY - e.rawY > d(60) || abs(downY - e.rawY) < d(10)) onClose()
            }
            true
        }
        addView(art, FrameLayout.LayoutParams(artW + d(30), -1, Gravity.START))

        val panelW = dm.widthPixels - artW - d(12)
        val scroll = ScrollView(svc).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            background = Ui.glass(svc, 30f, true); isClickable = true
        }
        addView(scroll, FrameLayout.LayoutParams(panelW, -1, Gravity.END).apply { setMargins(0, d(26), d(12), d(14)) })

        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), d(10), d(14), d(64)) }
        scroll.addView(col)

        val logo = ImageView(svc).apply { setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.FIT_CENTER }
        col.put(logo, 0, -1, d(68))

        if (land) {
            val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
            val a = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
            val b = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
            row.addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = d(6) })
            row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d(6) })
            col.put(row, 6)
            a.put(tilesSection(), 0); a.put(slidersSection(), 10)
            b.put(statsRow(), 0); b.put(memoryRow(), 8); b.put(perfSection(), 10)
            b.put(refreshSection(), 8); b.put(gameSection(), 8); b.put(mediaSection(), 10)
        } else {
            col.put(tilesSection(), 6); col.put(slidersSection(), 10); col.put(statsRow(), 10)
            col.put(memoryRow(), 8); col.put(perfSection(), 10); col.put(refreshSection(), 8)
            col.put(gameSection(), 8); col.put(mediaSection(), 10)
        }
        fun ornament(res: Int, height: Int, gravity: Int) {
            val v = ImageView(svc).apply {
                setImageResource(res); scaleType = ImageView.ScaleType.FIT_XY
                isClickable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(v, FrameLayout.LayoutParams(panelW, d(height), gravity or Gravity.END).apply { marginEnd = d(12) })
        }
        ornament(R.drawable.ornament_top, 28, Gravity.TOP)
        ornament(R.drawable.ornament_bottom, 52, Gravity.BOTTOM)
        art.bringToFront()
        refreshAll()
        if (animateIn) {
            alpha = 0f; translationY = -d(24).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }

    // ================= الأزرار =================
    private fun tilesSection(): View {
        class T(val name: String, val res: Int, val key: String?, val click: () -> Unit)
        val fb: (Intent) -> Unit = { openIntent(it) }
        fun sysTile(n: String, r: Int, k: String) = T(n, r, k) { dev.toggle(k, fb) }
        fun act(n: String, r: Int, f: () -> Unit) = T(n, r, null, f)
        val items = listOf(
            sysTile("Wi-Fi", R.drawable.g_wifi, "wifi"), sysTile("Bluetooth", R.drawable.g_bluetooth, "bluetooth"),
            sysTile("Airplane Mode", R.drawable.g_airplane, "airplane"), sysTile("Auto Rotate", R.drawable.g_rotate, "rotate"),
            sysTile("Do Not Disturb", R.drawable.g_dnd, "dnd"),
            sysTile("Hotspot", R.drawable.g_hotspot, "hotspot"), sysTile("Location", R.drawable.g_location, "location"),
            sysTile("NFC", R.drawable.g_nfc, "nfc"), sysTile("Battery Saver", R.drawable.g_battery_saver, "battery_saver"),
            sysTile("Dark Mode", R.drawable.g_dark, "dark"),
            sysTile("Eye Protection", R.drawable.g_eye, "eye"),
            act("Screenshot", R.drawable.g_screenshot) { onClose(); dev.globalAction("screenshot") },
            sysTile("Screen Record", R.drawable.g_record, "record"), sysTile("Screen Cast", R.drawable.g_cast, "cast"),
            act("Split Screen", R.drawable.g_split) { onClose(); dev.globalAction("split_screen") }
        )
        val box = section()
        items.chunked(5).forEachIndexed { ri, rowItems ->
            val row = LinearLayout(svc)
            rowItems.forEach { t ->
                val btn = GlassButton(svc, GlassButton.Shape.ORB, Glyphs.get(svc, t.res), t.name)
                btn.setOnClickListener { t.click(); postDelayed({ refreshAll() }, 700) }
                if (t.key != null) refreshers += { dev.state(t.key)?.let { btn.on = it } }
                row.addView(btn, LinearLayout.LayoutParams(0, -2, 1f))
            }
            box.put(row, if (ri == 0) 0 else 2)
        }
        return box
    }

    // ================= السلايدرز =================
    private fun sliderRow(icon: Int, s: GlowSlider, trailing: View): View = LinearLayout(svc).apply {
        gravity = Gravity.CENTER_VERTICAL; background = Ui.glass(svc, 24f)
        setPadding(d(12), d(4), d(12), d(4))
        addView(glyphView(icon, 24, Color.WHITE))
        addView(s, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d(4); marginEnd = d(4) })
        addView(trailing)
    }

    private fun slidersSection(): View {
        val box = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val bs = GlowSlider(svc).apply { max = 255; progress = dev.brightness(); onChange = { dev.setBrightness(it) } }
        val autoGlyph = glyphView(R.drawable.g_auto, 22)
        val autoRow = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(autoGlyph); addView(tv("Auto", 12f, true).apply { setPadding(d(6), 0, 0, 0) })
            setOnClickListener { dev.setAutoBrightness(!dev.autoBrightness()); postDelayed({ refreshAll() }, 200) }
        }
        box.put(sliderRow(R.drawable.g_sun, bs, autoRow))
        refreshers += {
            if (!bs.isDragging) bs.progress = dev.brightness()
            autoGlyph.setColorFilter(if (dev.autoBrightness()) Ui.RED_SOFT else Ui.GLYPH_OFF, PorterDuff.Mode.SRC_IN)
        }
        val vs = GlowSlider(svc).apply { max = dev.maxVolume(); progress = dev.volume(); onChange = { dev.setVolume(it) } }
        val mixer = glyphView(R.drawable.g_mixer, 26, Color.WHITE).apply {
            setOnClickListener {
                if (android.os.Build.VERSION.SDK_INT >= 29) openIntent(Intent(Settings.Panel.ACTION_VOLUME))
                else openIntent(Intent(Settings.ACTION_SOUND_SETTINGS))
            }
        }
        box.put(sliderRow(R.drawable.g_volume, vs, mixer), 8)
        refreshers += { if (!vs.isDragging) vs.progress = dev.volume() }
        return box
    }

    // ================= الإحصائيات =================
    private fun statCard(res: Int, big: TextView, small: TextView, w: Int = 0): View = LinearLayout(svc).apply {
        gravity = Gravity.CENTER_VERTICAL; background = Ui.glass(svc, 20f)
        setPadding(d(10), d(8), d(10), d(8))
        addView(glyphView(res, 30, Ui.RED_SOFT))
        addView(LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(8), 0, 0, 0); addView(big); addView(small)
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun statsRow(): View {
        val b1 = tv("", 17f, true); val b2 = tv("", 11f, false, Ui.TEXT_DIM)
        val t1 = tv("", 17f, true); val t2 = tv("System", 11f, false, Ui.TEXT_DIM)
        val h1 = tv("", 17f, true); val h2 = tv("Display", 11f, false, Ui.TEXT_DIM)
        val row = LinearLayout(svc)
        row.addView(statCard(R.drawable.g_battery, b1, b2), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = d(5) })
        row.addView(statCard(R.drawable.g_thermo, t1, t2), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = d(5) })
        row.addView(statCard(R.drawable.g_monitor, h1, h2), LinearLayout.LayoutParams(0, -2, 1f))
        refreshers += {
            val i = svc.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
            val chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            b1.text = "$lvl%${if (chg) " ⚡" else ""}"; b2.text = if (chg) "Charging" else "Battery"
            t1.text = "%.0f°C".format((i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
            h1.text = "%.0f Hz".format(dev.currentHz())
        }
        return row
    }

    private fun memCard(res: Int, title: String): Triple<View, TextView, MiniBar> {
        val v = tv("", 12f, false, Color.WHITE); val bar = MiniBar(svc)
        val card = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL; background = Ui.glass(svc, 20f); setPadding(d(10), d(8), d(12), d(8))
            addView(glyphView(res, 28, Ui.RED_SOFT))
            addView(LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL; setPadding(d(8), 0, 0, 0)
                addView(tv(title, 13f, true)); addView(v)
                addView(bar, LinearLayout.LayoutParams(-1, d(5)).apply { topMargin = d(5) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return Triple(card, v, bar)
    }

    private fun memoryRow(): View {
        val sto = memCard(R.drawable.g_storage, "Storage"); val ram = memCard(R.drawable.g_ram, "RAM")
        val row = LinearLayout(svc)
        row.addView(sto.first, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = d(5) })
        row.addView(ram.first, LinearLayout.LayoutParams(0, -2, 1f))
        refreshers += {
            val s = StatFs(Environment.getDataDirectory().path)
            val tot = s.totalBytes / 1e9; val used = tot - s.availableBytes / 1e9
            sto.second.text = "%.1f / %.0f GB".format(used, tot); sto.third.fraction = (used / tot).toFloat()
            val mi = ActivityManager.MemoryInfo()
            (svc.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            val u = (mi.totalMem - mi.availMem) / 1e9
            ram.second.text = "%.1f / %.1f GB".format(u, mi.totalMem / 1e9); ram.third.fraction = (u / (mi.totalMem / 1e9)).toFloat()
        }
        return row
    }

    // ================= الأداء / معدل التحديث / الألعاب =================
    private fun btnRow(btns: List<View>, gap: Int = 3): LinearLayout = LinearLayout(svc).apply {
        btns.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(d(gap), 0, d(gap), 0) }) }
    }

    private fun perfSection(): View {
        val box = section(); box.addView(header(R.drawable.g_gamepad, "Performance Mode"))
        val names = listOf("Power Saving", "Balanced", "Performance", "Extreme")
        val res = listOf(R.drawable.g_leaf, R.drawable.g_gauge, R.drawable.g_rocket, R.drawable.g_tornado)
        val sel = prefs.getInt("perf", 1)
        names.forEachIndexed { i, n ->
            val b = GlassButton(svc, GlassButton.Shape.CARD, Glyphs.get(svc, res[i]), n)
            b.on = i == sel
            b.setOnClickListener {
                perfBtns.forEachIndexed { j, x -> x.on = j == i }
                dev.setPerformanceMode(i) { openIntent(it) }
                if (i >= 2) selectHz(3, apply = false)
            }
            perfBtns += b
        }
        box.addView(btnRow(perfBtns))
        return box
    }

    private fun selectHz(i: Int, apply: Boolean = true) {
        prefs.edit().putInt("hz", i).apply()
        hzBtns.forEachIndexed { j, b -> b.on = j == i }
        if (apply && !dev.setRefresh(hzValues[i].toFloat())) openIntent(Intent(Settings.ACTION_DISPLAY_SETTINGS))
    }

    private fun refreshSection(): View {
        val box = section(); box.addView(header(R.drawable.g_monitor, "Display & Refresh Rate"))
        val cur = dev.currentHz()
        val def = hzValues.indices.minByOrNull { abs(hzValues[it] - cur) } ?: 2
        val sel = prefs.getInt("hz", def)
        hzValues.forEachIndexed { i, v ->
            val b = GlassButton(svc, GlassButton.Shape.PILL, null, "$v Hz")
            b.on = i == sel
            b.setOnClickListener { selectHz(i) }
            hzBtns += b
        }
        box.addView(btnRow(hzBtns, 3))
        return box
    }

    private fun gameSection(): View {
        val box = section(); box.addView(header(R.drawable.g_gamepad, "Game Tools"))
        val monitorBtn = GlassButton(svc, GlassButton.Shape.CARD, Glyphs.get(svc, R.drawable.g_perfmon), "Performance Monitor")
        monitorBtn.setOnClickListener { svc.toggleMonitor(); postDelayed({ refreshAll() }, 200) }
        refreshers += { monitorBtn.on = svc.isMonitorOn() }
        val raven = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = Ui.glass(svc, 16f)
            val avatar = ImageView(svc).apply {
                setImageResource(R.drawable.avatar); scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(avatar, LinearLayout.LayoutParams(d(48), d(48)))
            addView(tv("Raven", 11.5f, true))
            contentDescription = "Raven AI"
            setOnClickListener { svc.openRaven() }
        }
        fun tool(label: String, res: Int, mode: String) =
            GlassButton(svc, GlassButton.Shape.CARD, Glyphs.get(svc, res), label).apply {
                setOnClickListener { svc.openGameTool(mode) }
            }
        box.addView(btnRow(listOf(raven, monitorBtn,
            tool("Floating Window", R.drawable.g_floating, "floating"),
            tool("Key Mapping", R.drawable.g_keymap, "keys"),
            tool("Macros", R.drawable.g_macros, "macros")), 2))
        return box
    }

    // ================= الميديا =================
    private fun controller(): MediaController? = try {
        val msm = svc.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val l = msm.getActiveSessions(ComponentName(svc, MediaListener::class.java))
        l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l.firstOrNull()
    } catch (e: SecurityException) { null } catch (e: Exception) { null }

    private fun listenerOn() =
        Settings.Secure.getString(svc.contentResolver, "enabled_notification_listeners")?.contains(svc.packageName) == true

    private fun mediaSection(): View {
        val box = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL; background = Ui.glass(svc, 24f); setPadding(d(10), d(10), d(10), d(10))
        }
        thumb.apply {
            setImageResource(R.drawable.media_thumb); scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, d(12).toFloat()) }
            }
        }
        box.addView(thumb, LinearLayout.LayoutParams(d(58), d(58)))
        box.addView(LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(12), 0, d(8), 0)
            addView(mediaTitle.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(mediaSub.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(mediaBar, LinearLayout.LayoutParams(-1, d(4)).apply { topMargin = d(8) })
        }, LinearLayout.LayoutParams(0, -2, 1f))

        fun key(k: Int, fn: (MediaController) -> Unit) {
            val c = controller()
            if (c != null) fn(c) else dev.mediaKey(k)
            postDelayed({ refreshAll() }, 400)
        }
        val prev = GlassButton(svc, GlassButton.Shape.FLAT, Glyphs.get(svc, R.drawable.g_prev)).apply {
            setOnClickListener { key(KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.transportControls.skipToPrevious() } }
        }
        val next = GlassButton(svc, GlassButton.Shape.FLAT, Glyphs.get(svc, R.drawable.g_next)).apply {
            setOnClickListener { key(KeyEvent.KEYCODE_MEDIA_NEXT) { it.transportControls.skipToNext() } }
        }
        playBtn = GlassButton(svc, GlassButton.Shape.ORB, Glyphs.play, "", 50f).apply {
            on = true
            setOnClickListener {
                key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { c ->
                    if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
                }
            }
        }
        box.addView(prev, LinearLayout.LayoutParams(d(40), d(40)))
        box.addView(playBtn, LinearLayout.LayoutParams(d(62), -2).apply { marginStart = d(4); marginEnd = d(4) })
        box.addView(next, LinearLayout.LayoutParams(d(40), d(40)))

        refreshers += {
            val c = controller()
            if (c == null) {
                mediaTitle.text = "Media"
                mediaSub.text = if (listenerOn()) "Not Playing" else "فعّل \"الوصول للإشعارات\" من التطبيق"
                mediaBar.fraction = 0f; playBtn.setGlyph(Glyphs.play)
                if (!artIsDefault) { thumb.setImageResource(R.drawable.media_thumb); artIsDefault = true; lastArt = null }
            } else {
                val md = c.metadata; val st = c.playbackState
                mediaTitle.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: c.packageName
                mediaSub.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                val playing = st?.state == PlaybackState.STATE_PLAYING
                playBtn.setGlyph(if (playing) Glyphs.pause else Glyphs.play)
                val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (st != null && dur > 0) {
                    var pos = st.position
                    if (playing) pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
                    mediaBar.fraction = pos.toFloat() / dur
                } else mediaBar.fraction = 0f
                val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                if (art !== lastArt) {
                    lastArt = art
                    if (art != null) { thumb.setImageBitmap(art); artIsDefault = false }
                    else { thumb.setImageResource(R.drawable.media_thumb); artIsDefault = true }
                }
            }
        }
        return box
    }

    // ================= دورة الحياة =================
    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestFocus(); postDelayed(ticker, 1000) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_UP) onClose(); return true }
        return super.dispatchKeyEvent(e)
    }
}
