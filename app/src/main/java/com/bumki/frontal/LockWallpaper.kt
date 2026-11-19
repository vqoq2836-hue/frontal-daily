package com.bumki.frontal

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * 잠금화면 '배경 그림'에 오늘 할 일을 그려 넣습니다.
 * 알림과 달리 폰을 켜면 시계 아래에 늘 그대로 보이고, 할 일이 바뀔 때만 새로 그립니다.
 */
object LockWallpaper {
    private const val PREFS = "frontal"
    private const val KEY_SIG = "lockwp_sig"
    private val lock = Any()

    fun enabled(ctx: Context) = Reminders.stateObj(ctx).optBoolean("lockWallpaper", false)

    private fun todos(ctx: Context): List<JSONObject> {
        val s = Reminders.stateObj(ctx)
        if (s.optString("date") != LocalDate.now().toString()) return emptyList()
        val a = s.optJSONArray("todos") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    /** 켜져 있으면 뒤에서 조용히 갱신 (내용이 같으면 건너뜀) */
    fun update(ctx: Context) {
        val app = ctx.applicationContext
        if (!enabled(app)) return
        Thread { runCatching { apply(app, false) } }.start()
    }

    /** 바로 적용: "ok" · "same" · "error:..." */
    fun apply(ctx: Context, force: Boolean): String = synchronized(lock) {
        val list = todos(ctx)
        val sig = LocalDate.now().toString() + "|" +
            list.joinToString("|") { it.optString("text") + it.optBoolean("done") + it.optBoolean("top") } + "|v2"
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && prefs.getString(KEY_SIG, null) == sig) return "same"
        return try {
            val (w, h) = screenSize(ctx)
            val bmp = render(w, h, list)
            WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
            bmp.recycle()
            prefs.edit().putString(KEY_SIG, sig).apply()
            "ok"
        } catch (e: Exception) {
            "error:" + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun preview(ctx: Context): String {
        val (w, h) = screenSize(ctx)
        val full = render(w, h, todos(ctx))
        val small = Bitmap.createScaledBitmap(full, w / 3, h / 3, true)
        full.recycle()
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 85, out)
        small.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun screenSize(ctx: Context): Pair<Int, Int> {
        val dm = DisplayMetrics()
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        d.getRealMetrics(dm)
        val w = minOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(720)
        val h = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1280)
        return w to h
    }

    // 지금 쓰는 잠금화면('폰의 주인은 나다')과 같은 결: 어두운 바탕, 흰 굵은 글씨, 보라 포인트
    private const val BG = 0xFF1B1C22.toInt()
    private const val WHITE = 0xFFF1F1F5.toInt()
    private const val SOFT = 0xFFB4B5C2.toInt()
    private const val DIMW = 0xFF6C6D79.toInt()
    private const val ACCENT = 0xFF8E95F2.toInt()

    private val DEFAULT_RULES = listOf(
        "잘 땐 폰을 거실에 둔다", "통화 끝나면 폰 감옥행", "운전 중엔 폰이 없는 셈",
        "근무 중엔 시세를 안 본다", "충동은 파도다, 5분이면 지나간다", "빈 시간은 비워둔다", "미끄러져도 자책하지 않는다"
    )

    private fun tp(size: Float, color: Int, bold: Boolean = true, align: Paint.Align = Paint.Align.CENTER) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            textSize = size; this.color = color; textAlign = align
        }

    private fun drawTitle(c: Canvas, text: String, cx: Float, y: Float, size: Float, u: Float) {
        c.drawText(text, cx, y, tp(size, WHITE))
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        c.drawRoundRect(RectF(cx - 5.2f * u, y + 1.6f * u, cx + 5.2f * u, y + 2.2f * u), 0.3f * u, 0.3f * u, bar)
    }

    private fun drawQuote(c: Canvas, cx: Float, y: Float, u: Float, scale: Float) {
        c.drawText("자극이 아니라 통제감에서", cx, y, tp(4.0f * u * scale, SOFT, bold = false))
        c.drawText("나의 행복이 나온다", cx, y + 6.0f * u * scale, tp(4.4f * u * scale, ACCENT))
    }

    fun render(w: Int, h: Int, list: List<JSONObject>): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = w / 100f
        val W = w.toFloat()
        val H = h.toFloat()
        val cx = W / 2
        c.drawColor(BG)

        val left = list.filter { !it.optBoolean("done") }.sortedBy { if (it.optBoolean("top")) 0 else 1 }
        val done = list.filter { it.optBoolean("done") }

        if (left.isEmpty()) {
            // 할 일이 없거나 다 끝낸 날: '나의 약속' 전체
            var y = H * 0.312f
            if (list.isNotEmpty()) {
                c.drawText("오늘 적은 일 ${done.size}개 모두 끝 ✓", cx, H * 0.292f, tp(3.8f * u, ACCENT))
                y = H * 0.338f
            }
            drawTitle(c, "폰의 주인은 나다", cx, y, 8.2f * u, u)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
            val rule = tp(4.7f * u, WHITE, align = Paint.Align.LEFT)
            var ry = y + 15f * u
            val gap = minOf(12.4f * u, (H * 0.73f - ry) / DEFAULT_RULES.size)
            for (r in DEFAULT_RULES) {
                val tw = rule.measureText(r)
                val x0 = cx - (tw + 4.2f * u) / 2
                c.drawCircle(x0 + 0.8f * u, ry - 1.6f * u, 0.75f * u, dot)
                c.drawText(r, x0 + 4.2f * u, ry, rule)
                ry += gap
            }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF34353F.toInt(); strokeWidth = 0.15f * u }
            c.drawLine(cx - 22 * u, H * 0.78f, cx + 22 * u, H * 0.78f, line)
            drawQuote(c, cx, H * 0.817f, u, 1f)
            if (list.isEmpty()) c.drawText("아침에 오늘 할 일을 적으면 여기에 떠요", cx, H * 0.868f, tp(3.1f * u, DIMW, bold = false))
            return bmp
        }

        // 할 일이 남은 날: 시계 바로 아래 할 일 목록
        val rows = (left + done).take(7)
        val more = list.size - rows.size
        c.drawText("오늘 할 일 · ${left.size}개 남음", cx, H * 0.292f, tp(3.9f * u, ACCENT))

        val item = tp(5.4f * u, WHITE, align = Paint.Align.LEFT)
        val starP = tp(5.4f * u, ACCENT, align = Paint.Align.LEFT)
        val bs = 5.0f * u
        val gapX = 3.0f * u
        val maxText = 70f * u
        val texts = rows.map { TextUtils.ellipsize(it.optString("text"), item, maxText, TextUtils.TruncateAt.END).toString() }
        val starW = starP.measureText("★ ")
        val blockW = bs + gapX + rows.indices.maxOf { i ->
            item.measureText(texts[i]) + if (rows[i].optBoolean("top") && !rows[i].optBoolean("done")) starW else 0f
        }
        val x0 = maxOf(8f * u, cx - blockW / 2)
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.5f * u; color = SOFT }
        val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4A4C66.toInt() }
        val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 0.7f * u; color = ACCENT
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        var y = H * 0.292f + 13.5f * u
        val step = minOf(11.6f * u, (H * 0.70f - y) / rows.size)
        rows.forEachIndexed { i, t ->
            val isDone = t.optBoolean("done")
            val mid = y - 1.9f * u
            val r = RectF(x0, mid - bs / 2, x0 + bs, mid + bs / 2)
            if (isDone) {
                c.drawRoundRect(r, 1.2f * u, 1.2f * u, boxFill)
                val p = Path()
                p.moveTo(r.left + bs * 0.24f, r.top + bs * 0.52f)
                p.lineTo(r.left + bs * 0.43f, r.top + bs * 0.72f)
                p.lineTo(r.left + bs * 0.78f, r.top + bs * 0.30f)
                c.drawPath(p, check)
            } else c.drawRoundRect(r, 1.2f * u, 1.2f * u, box)
            var tx = x0 + bs + gapX
            if (t.optBoolean("top") && !isDone) { c.drawText("★", tx, y, starP); tx += starW }
            item.color = if (isDone) DIMW else WHITE
            item.isStrikeThruText = isDone
            c.drawText(texts[i], tx, y, item)
            y += step
        }
        if (more > 0) c.drawText("외 ${more}개", cx, y, tp(3.4f * u, DIMW, bold = false))

        drawTitle(c, "폰의 주인은 나다", cx, H * 0.765f, 5.6f * u, u)
        drawQuote(c, cx, H * 0.812f, u, 0.9f)
        return bmp
    }
}
