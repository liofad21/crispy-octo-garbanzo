package com.legion.controlcenter

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** عميل Gemini بسيط (REST): محادثة + استدعاء أدوات + تحويل نص لصوت بنت. شغّال على thread خلفي. */
class GeminiClient(private val keyProvider: () -> String, private val actions: (String, JSONObject) -> String) {

    companion object {
        const val CHAT_MODEL = "gemini-2.5-flash"
        const val TTS_MODEL = "gemini-2.5-flash-preview-tts"
        const val VOICE = "Kore"
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

        private const val SYSTEM = "إنتي \"Raven\"، مساعدة ذكية بصوت بنت جوه تابلت Lenovo Legion Y700. " +
                "اتكلمي بالعامية المصرية بأسلوب لطيف وواثق ومختصر (جملة أو اتنين في الغالب) لأن ردك بيتقرأ بصوت. " +
                "لو المستخدم طلب حاجة في التابلت (سطوع، صوت، واي فاي، بلوتوث، تشغيل تطبيق، ميديا، لقطة شاشة، أداء...) نفّذيها بالأدوات المتاحة الأول وبعدين قوليله اللي حصل بجملة قصيرة. " +
                "لو الأداة رجّعت إن الحاجة مش متاحة أو فشلت، قولي كده بصراحة. ماتختلقيش نتايج. " +
                "من غير رموز أو ماركداون أو إيموجي في الرد."

        private fun fn(name: String, desc: String, props: JSONObject? = null, req: List<String> = emptyList()): JSONObject {
            val o = JSONObject().put("name", name).put("description", desc)
            if (props != null) o.put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray(req)))
            return o
        }
        private fun p(type: String, desc: String, choices: List<String>? = null) =
            JSONObject().put("type", type).put("description", desc).apply { if (choices != null) put("enum", JSONArray(choices)) }

        val TOOLS: JSONArray = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
            .put(fn("set_brightness", "يضبط سطوع الشاشة من 1 لـ 100 بالمية", JSONObject().put("percent", p("INTEGER", "النسبة 1-100")), listOf("percent")))
            .put(fn("set_volume", "يضبط صوت الميديا من 0 لـ 100 بالمية", JSONObject().put("percent", p("INTEGER", "النسبة 0-100")), listOf("percent")))
            .put(fn("set_toggle", "يشغّل أو يطفّي إعداد في التابلت",
                JSONObject().put("name", p("STRING", "اسم الإعداد", listOf("wifi", "bluetooth", "airplane", "hotspot", "nfc", "location", "rotate", "dnd", "battery_saver", "dark", "eye")))
                    .put("enabled", p("BOOLEAN", "true للتشغيل و false للإطفاء")), listOf("name", "enabled")))
            .put(fn("set_refresh_rate", "يضبط معدل تحديث الشاشة", JSONObject().put("hz", p("INTEGER", "واحدة من 60 أو 90 أو 120 أو 144", listOf("60", "90", "120", "144"))), listOf("hz")))
            .put(fn("set_performance_mode", "يغيّر وضع الأداء", JSONObject().put("mode", p("STRING", "الوضع", listOf("power_saving", "balanced", "performance", "extreme"))), listOf("mode")))
            .put(fn("media_control", "يتحكم في تشغيل الميديا", JSONObject().put("action", p("STRING", "الأمر", listOf("play_pause", "next", "previous"))), listOf("action")))
            .put(fn("system_action", "أمر نظام", JSONObject().put("action", p("STRING", "الأمر", listOf("home", "back", "recents", "screenshot", "split_screen", "lock"))), listOf("action")))
            .put(fn("open_app", "يفتح تطبيق باسمه", JSONObject().put("name", p("STRING", "اسم التطبيق زي ما بيظهر على التابلت")), listOf("name")))
            .put(fn("open_control_center", "يفتح لوحة Legion Control Center"))
            .put(fn("toggle_performance_monitor", "يظهر أو يخفي عدّاد الأداء العايم"))
            .put(fn("get_status", "يرجّع حالة التابلت: البطارية والحرارة والرام والتخزين والسطوع والصوت"))
        ))
    }

    private val history = JSONArray()

    private fun post(model: String, body: JSONObject): JSONObject {
        val key = keyProvider()
        if (key.isBlank()) throw IllegalStateException("NO_KEY")
        val c = URL(BASE + model + ":generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("x-goog-api-key", key)
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (_: Exception) { text.take(200) }
            throw RuntimeException("HTTP $code: $msg")
        }
        return JSONObject(text)
    }

    /** بيرجّع رد المساعدة النصي بعد ما ينفّذ أي أدوات مطلوبة. */
    fun chat(userText: String): String {
        history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText))))
        while (history.length() > 24) history.remove(0)
        // لازم أول عنصر يكون user
        while (history.length() > 0 && history.getJSONObject(0).getString("role") != "user") history.remove(0)

        repeat(6) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                .put("contents", history)
                .put("tools", TOOLS)
            val res = post(CHAT_MODEL, body)
            val content = res.getJSONArray("candidates").getJSONObject(0).optJSONObject("content")
                ?: return "معرفتش أرد على ده."
            val parts = content.optJSONArray("parts") ?: return "معرفتش أرد على ده."
            history.put(content)
            val calls = ArrayList<JSONObject>()
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                part.optJSONObject("functionCall")?.let { calls.add(it) }
                if (part.has("text") && !part.optBoolean("thought", false)) sb.append(part.getString("text"))
            }
            if (calls.isEmpty()) return sb.toString().trim().ifEmpty { "تمام." }
            val responses = JSONArray()
            for (call in calls) {
                val result = try { actions(call.getString("name"), call.optJSONObject("args") ?: JSONObject()) }
                catch (e: Exception) { "error: ${e.message}" }
                responses.put(JSONObject().put("functionResponse", JSONObject()
                    .put("name", call.getString("name"))
                    .put("response", JSONObject().put("result", result))))
            }
            history.put(JSONObject().put("role", "user").put("parts", responses))
        }
        return "الطلب طلع معقّد شوية، جرّب تقسمه."
    }

    /** بيرجّع PCM 16-bit mono 24kHz بصوت بنت. */
    fun speak(text: String): ByteArray {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "قولي بنبرة دافية وودودة: $text")))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOICE)))))
        val res = post(TTS_MODEL, body)
        val data = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
        return Base64.decode(data, Base64.DEFAULT)
    }
}
