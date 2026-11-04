package com.legion.controlcenter

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/** سلايدر أحمر متوهّج للسطوع والصوت. */
class GlowSlider(ctx: Context) : View(ctx) {
    var max = 100
    var progress = 0
        set(v) { field = v.coerceIn(0, max); invalidate() }
    var onChange: ((Int) -> Unit)? = null
    var isDragging = false
        private set

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(40))
    }

    override fun onDraw(c: Canvas) {
        val pad = context.dpf(14f)
        val th = context.dpf(12f)
        val cy = height / 2f
        val f = if (max == 0) 0f else progress.toFloat() / max
        val tx = pad + (width - 2 * pad) * f

        rect.set(pad, cy - th / 2, width - pad, cy + th / 2)
        p.shader = null; p.style = Paint.Style.FILL; p.color = Color.parseColor("#FF0E0304")
        c.drawRoundRect(rect, th / 2, th / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1f); p.color = Color.parseColor("#88FF3A48")
        c.drawRoundRect(rect, th / 2, th / 2, p)

        rect.set(pad, cy - th / 2, max(tx, pad + th), cy + th / 2)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(pad, 0f, max(tx, pad + th), 0f, Color.parseColor("#FF5E000E"), Color.parseColor("#FFFF2A3A"), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, th / 2, th / 2, p)
        p.shader = null

        // توهّج وإبهام السلايدر
        p.style = Paint.Style.FILL
        p.color = Color.argb(70, 255, 30, 45)
        c.drawCircle(tx, cy, context.dpf(17f), p)
        p.shader = RadialGradient(tx - context.dpf(3f), cy - context.dpf(3f), context.dpf(13f),
            intArrayOf(Color.WHITE, Color.parseColor("#FFF0B4BA")), null, Shader.TileMode.CLAMP)
        c.drawCircle(tx, cy, context.dpf(11f), p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1.5f); p.color = Color.parseColor("#FFFF4A58")
        c.drawCircle(tx, cy, context.dpf(11f), p)
    }

    private fun update(x: Float) {
        val pad = context.dpf(14f)
        val f = ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        val v = (f * max).roundToInt()
        if (v != progress) { progress = v; onChange?.invoke(v) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); isDragging = true; update(e.x) }
            MotionEvent.ACTION_MOVE -> update(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
        }
        return true
    }
}

/** شريط تقدّم رفيع (التخزين / الرام / الميديا). */
class MiniBar(ctx: Context) : View(ctx) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        p.shader = null; p.style = Paint.Style.FILL; p.color = Color.parseColor("#FF1A0507")
        c.drawRoundRect(r, h / 2, h / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = Color.parseColor("#66FF3A48")
        c.drawRoundRect(r, h / 2, h / 2, p)
        val w = max(width * fraction, if (fraction > 0f) h else 0f)
        if (w > 0f) {
            r.set(0f, 0f, w, h)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, 0f, w, 0f, Color.parseColor("#FF8A0012"), Color.parseColor("#FFFF2A3A"), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, h / 2, h / 2, p)
            p.shader = null
        }
    }
}

/** صورة الخلفية على الشمال: بتتغطي المساحة وبتتلاشى ناحية اللوحة. */
class ArtView(ctx: Context) : View(ctx) {
    private val bmp = Glyphs.get(ctx, R.drawable.art_left)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fade = Paint()
    private val m = Matrix()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        fade.shader = LinearGradient(w * 0.55f, 0f, w.toFloat(), 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val dx = (width - bmp.width * s) / 2f
        val dy = (height - bmp.height * s) / 2f
        m.setScale(s, s); m.postTranslate(dx, dy)
        c.drawBitmap(bmp, m, paint)
        // Keep the full visible reference art without a fading edge.
    }
}
