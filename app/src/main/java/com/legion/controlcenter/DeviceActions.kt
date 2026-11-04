package com.legion.controlcenter

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager

/** كل أفعال الجهاز في مكان واحد، بتستخدمها اللوحة والمساعد الذكي. */
class DeviceActions(private val svc: ControlAccessibilityService) {

    val prefs = svc.getSharedPreferences("legion", Context.MODE_PRIVATE)
    val audio = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private class Tile(val labels: List<String>, val fallback: () -> Intent)

    private val tiles: Map<String, Tile> = mapOf(
        "wifi" to Tile(listOf("Wi-Fi", "Internet", "WLAN", "واي فاي", "الإنترنت")) {
            if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIFI_SETTINGS)
        },
        "bluetooth" to Tile(listOf("Bluetooth", "بلوتوث")) { Intent(Settings.ACTION_BLUETOOTH_SETTINGS) },
        "airplane" to Tile(listOf("Airplane", "Flight mode", "Flight", "وضع الطيران", "الطيران")) { Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS) },
        "hotspot" to Tile(listOf("Hotspot", "Mobile Hotspot", "نقطة اتصال", "نقطة الاتصال")) { Intent(Settings.ACTION_WIRELESS_SETTINGS) },
        "nfc" to Tile(listOf("NFC")) { Intent(Settings.ACTION_NFC_SETTINGS) },
        "location" to Tile(listOf("Location", "الموقع")) { Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
        "battery_saver" to Tile(listOf("Battery Saver", "Power saving", "توفير الطاقة", "توفير")) { Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS) },
        "dark" to Tile(listOf("Dark theme", "Dark mode", "Dark", "الوضع الداكن", "المظهر الداكن")) { Intent(Settings.ACTION_DISPLAY_SETTINGS) },
        "eye" to Tile(listOf("Eye comfort", "Reading mode", "Night Light", "Eye protection", "Eye", "حماية العين", "الإضاءة الليلية")) { Intent(Settings.ACTION_DISPLAY_SETTINGS) },
        "record" to Tile(listOf("Screen record", "Screen recorder", "Record screen", "تسجيل الشاشة", "مسجل الشاشة")) { Intent(Settings.ACTION_SETTINGS) },
        "cast" to Tile(listOf("Cast", "Screen cast", "Smart View", "Screen mirroring", "إرسال", "بث")) { Intent(Settings.ACTION_CAST_SETTINGS) }
    )

    fun toast(t: String) = android.widget.Toast.makeText(svc, t, android.widget.Toast.LENGTH_SHORT).show()

    fun hasSecure() = svc.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    fun canWrite() = Settings.System.canWrite(svc)
    private val cr get() = svc.contentResolver

    // ---------- الحالات ----------
    fun powerSave() = (svc.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    fun isDark() = svc.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    fun dndOn() = (svc.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

    fun state(key: String): Boolean? = try {
        when (key) {
            "wifi" -> (svc.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
            "bluetooth" -> (svc.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
            "airplane" -> Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            "rotate" -> Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            "dnd" -> dndOn()
            "hotspot" -> WifiManager::class.java.getMethod("isWifiApEnabled")
                .invoke(svc.applicationContext.getSystemService(Context.WIFI_SERVICE)) as Boolean
            "location" -> Settings.Secure.getInt(cr, Settings.Secure.LOCATION_MODE, 0) != 0
            "nfc" -> NfcAdapter.getDefaultAdapter(svc)?.isEnabled == true
            "battery_saver" -> powerSave()
            "dark" -> isDark()
            "eye" -> Settings.Secure.getInt(cr, "night_display_activated", 0) == 1
            else -> null
        }
    } catch (e: Exception) { null }

    // ---------- التبديل ----------
    /** بيدوس على زر النظام الحقيقي، ولو ملقاهوش بيستدعي openFallback بصفحة الإعدادات. */
    fun sys(key: String, openFallback: (Intent) -> Unit) {
        val t = tiles[key] ?: return
        svc.clickSystemTile(t.labels) { openFallback(t.fallback()) }
    }

    private fun secureOr(direct: () -> Unit, key: String, openFallback: (Intent) -> Unit) {
        if (hasSecure()) { try { direct(); return } catch (_: Exception) {} }
        sys(key, openFallback)
    }

    fun toggle(key: String, openFallback: (Intent) -> Unit) {
        when (key) {
            "rotate" -> {
                if (!canWrite()) { toast("فعّل صلاحية تعديل الإعدادات من التطبيق"); return }
                val cur = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1 - cur)
            }
            "dnd" -> {
                val nm = svc.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) { openFallback(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return }
                nm.setInterruptionFilter(if (dndOn()) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }
            "location" -> secureOr({
                Settings.Secure.putInt(cr, Settings.Secure.LOCATION_MODE, if (state("location") == true) 0 else 3)
            }, key, openFallback)
            "battery_saver" -> secureOr({
                Settings.Global.putInt(cr, "low_power", if (powerSave()) 0 else 1)
            }, key, openFallback)
            "dark" -> secureOr({
                Settings.Secure.putInt(cr, "ui_night_mode", if (isDark()) 1 else 2)
            }, key, openFallback)
            else -> sys(key, openFallback)
        }
    }

    /** للمساعد: يضبط الحالة المطلوبة بس لو مختلفة. */
    fun set(key: String, enabled: Boolean): String {
        if (!tiles.containsKey(key) && key !in listOf("rotate", "dnd")) return "إعداد غير معروف: $key"
        val cur = state(key)
        if (cur != null && cur == enabled) return "$key already ${if (enabled) "on" else "off"}"
        toggle(key) { i -> try { svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
        return "tapped $key toggle (state may take a second to change)"
    }

    // ---------- سطوع وصوت ----------
    fun brightness() = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
    fun setBrightness(v: Int): Boolean {
        if (!canWrite()) { toast("فعّل صلاحية تعديل الإعدادات من التطبيق"); return false }
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(1, 255))
        return true
    }
    fun autoBrightness() = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    fun setAutoBrightness(on: Boolean) {
        if (!canWrite()) { toast("فعّل صلاحية تعديل الإعدادات من التطبيق"); return }
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
    }
    fun maxVolume() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    fun setVolume(v: Int) = audio.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, maxVolume()), 0)

    // ---------- معدل التحديث ----------
    fun currentHz(): Float {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        return wm.defaultDisplay.refreshRate
    }
    fun setRefresh(hz: Float): Boolean {
        if (!canWrite()) { toast("فعّل صلاحية تعديل الإعدادات من التطبيق"); return false }
        return try {
            Settings.System.putFloat(cr, "peak_refresh_rate", hz)
            Settings.System.putFloat(cr, "min_refresh_rate", hz)
            true
        } catch (e: Exception) { false }
    }

    // ---------- أوضاع الأداء ----------
    private fun powerSaveSet(on: Boolean, openFallback: (Intent) -> Unit) {
        if (powerSave() == on) return
        if (hasSecure()) { try { Settings.Global.putInt(cr, "low_power", if (on) 1 else 0); return } catch (_: Exception) {} }
        if (on) openFallback(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) else sys("battery_saver", openFallback)
    }

    fun setPerformanceMode(i: Int, openFallback: (Intent) -> Unit) {
        val nm = svc.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (i != 3 && prefs.getBoolean("dnd_by_us", false) && nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            prefs.edit().putBoolean("dnd_by_us", false).apply()
        }
        prefs.edit().putInt("perf", i).apply()
        when (i) {
            0 -> powerSaveSet(true, openFallback)
            1 -> powerSaveSet(false, openFallback)
            2 -> { powerSaveSet(false, openFallback); setRefresh(144f) }
            3 -> {
                powerSaveSet(false, openFallback); setRefresh(144f)
                if (nm.isNotificationPolicyAccessGranted && !dndOn()) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    prefs.edit().putBoolean("dnd_by_us", true).apply()
                }
            }
        }
    }

    // ---------- الميديا ----------
    fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---------- أفعال النظام ----------
    fun global(action: Int, delay: Long = 0) { svc.postDelayed(delay) { svc.performGlobalAction(action) } }

    fun openApp(name: String): String {
        val pm = svc.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(q, 0)
        val n = name.trim().lowercase()
        val hit = list.firstOrNull { it.loadLabel(pm).toString().lowercase() == n }
            ?: list.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(n) }
            ?: return "لقيتش تطبيق اسمه $name"
        val i = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return "مقدرتش أفتحه"
        svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "opened ${hit.loadLabel(pm)}"
    }

    fun status(): String {
        val i = svc.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
        val chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val mi = ActivityManager.MemoryInfo()
        (svc.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val s = StatFs(Environment.getDataDirectory().path)
        val tot = s.totalBytes / 1e9
        return "battery=$lvl% charging=$chg temp=${temp}C ram_used=%.1f/%.1fGB storage_used=%.1f/%.0fGB brightness=%d%% volume=%d%% refresh=%.0fHz wifi=%s bluetooth=%s".format(
            (mi.totalMem - mi.availMem) / 1e9, mi.totalMem / 1e9, tot - s.availableBytes / 1e9, tot,
            brightness() * 100 / 255, volume() * 100 / maxOf(1, maxVolume()), currentHz(), state("wifi"), state("bluetooth"))
    }

    fun globalAction(name: String): String {
        val a = when (name) {
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return "غير مدعوم"
            "split_screen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return "غير مدعوم"
            else -> return "أمر غير معروف"
        }
        // نستنى شوية عشان لوحة المساعد متظهرش في اللقطة
        global(a, 500)
        return "done $name"
    }
}
