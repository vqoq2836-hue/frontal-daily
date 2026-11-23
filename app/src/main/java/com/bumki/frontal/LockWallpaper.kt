package com.bumki.frontal

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.text.Layout
import android.text.StaticLayout
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

    fun enabled(ctx: Context) = Reminders.stateObj(ctx).optBoolean("lockWallpaper", true)

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
        val advice = adviceFor(ctx, LocalDate.now())
        val sig = LocalDate.now().toString() + "|" + advice + "|" +
            list.joinToString("|") { it.optString("text") + it.optBoolean("done") + it.optBoolean("top") } + "|v5"
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && prefs.getString(KEY_SIG, null) == sig) return "same"
        return try {
            val (w, h) = screenSize(ctx)
            val bmp = render(w, h, list, advice)
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
        val full = render(w, h, todos(ctx), adviceFor(ctx, LocalDate.now()))
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

    // 리소 판화: 크림 종이 + 남색 잉크 + 별색(그린·코랄·노랑). 앱 화면과 같은 색
    private const val BG = 0xFFF3EFE6.toInt()
    private const val CARD = 0xFFFFFDF8.toInt()
    private const val INK = 0xFF1F2A3A.toInt()
    private const val MUTED = 0xFF5E6573.toInt()
    private const val FAINT = 0xFF8E93A0.toInt()
    private const val GREEN = 0xFF43B489.toInt()
    private const val MINT = 0xFFCDEBDB.toInt()
    private const val MINT2 = 0xFFA9DDC3.toInt()
    private const val PEACH = 0xFFFFD6C9.toInt()
    private const val CORAL = 0xFFFF6F59.toInt()
    private const val YELLOW = 0xFFFFD36E.toInt()

    /** 앱이 넘겨준 조언 목록에서 날짜마다 하나 (자정이 지나면 앱을 안 열어도 바뀜) */
    fun adviceFor(ctx: Context, date: LocalDate): String {
        val st = Reminders.stateObj(ctx)
        st.optJSONObject("adviceByDate")?.optString(date.toString())?.takeIf { it.isNotBlank() }?.let { return it }
        val a = st.optJSONArray("advice")
        if (a == null || a.length() == 0) return "오늘도 충분히 잘하고 있다"
        val n = a.length()
        return a.optString((((date.toEpochDay() % n) + n) % n).toInt())
    }

    private fun tp(size: Float, color: Int, bold: Boolean = true, align: Paint.Align = Paint.Align.CENTER) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            textSize = size; this.color = color; textAlign = align
        }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = width
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    /** 잉크 테두리 + 뒤로 어긋난 별색 판(그림자) */
    private fun plate(c: Canvas, r: RectF, rad: Float, face: Int, shadow: Int, off: Float, line: Float) {
        c.drawRoundRect(RectF(r.left + off, r.top + off, r.right + off, r.bottom + off), rad, rad, fill(shadow))
        c.drawRoundRect(r, rad, rad, fill(face))
        c.drawRoundRect(r, rad, rad, stroke(INK, line))
    }

    fun render(w: Int, h: Int, list: List<JSONObject>, advice: String): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = w / 100f
        val W = w.toFloat()
        val H = h.toFloat()
        val cx = W / 2
        val line = 0.42f * u
        c.drawColor(BG)

        // ---- 종이 입자 (옅게)
        val rnd = java.util.Random(7)
        val dot = Paint().apply { color = INK }
        repeat((w * h / 900).coerceAtMost(4000)) {
            dot.alpha = 10 + rnd.nextInt(18)
            val x = rnd.nextFloat() * W; val y = rnd.nextFloat() * H; val s = 1f + rnd.nextFloat() * 1.6f
            c.drawRect(x, y, x + s, y + s, dot)
        }

        // ---- 별색 면: 시계 뒤 복숭아 해, 왼쪽 노란 점, 아래 초록 언덕 (잉크 선은 살짝 어긋나게)
        val sunX = W * 0.86f; val sunY = H * 0.12f; val sunR = W * 0.36f
        c.drawCircle(sunX, sunY, sunR, fill(PEACH))
        c.drawCircle(sunX - 1.2f * u, sunY - 1f * u, sunR, stroke(INK, line * 0.8f))
        c.drawCircle(W * 0.11f, H * 0.235f, 3.6f * u, fill(YELLOW))
        c.drawCircle(W * 0.11f - 0.7f * u, H * 0.235f - 0.6f * u, 3.6f * u, stroke(INK, line * 0.8f))
        val hill = Path().apply {
            moveTo(0f, H * 0.905f); cubicTo(W * 0.3f, H * 0.865f, W * 0.62f, H * 0.915f, W, H * 0.872f)
            lineTo(W, H); lineTo(0f, H); close()
        }
        c.drawPath(hill, fill(MINT))
        val hill2 = Path().apply {
            moveTo(0f, H * 0.955f); cubicTo(W * 0.35f, H * 0.93f, W * 0.7f, H * 0.965f, W, H * 0.94f)
            lineTo(W, H); lineTo(0f, H); close()
        }
        c.drawPath(hill2, fill(MINT2))
        val ridge = Path().apply { moveTo(0f, H * 0.905f); cubicTo(W * 0.3f, H * 0.865f, W * 0.62f, H * 0.915f, W, H * 0.872f) }
        c.save(); c.translate(-1f * u, -0.8f * u); c.drawPath(ridge, stroke(INK, line * 0.8f)); c.restore()

        val left = list.filter { !it.optBoolean("done") }.sortedBy { if (it.optBoolean("top")) 0 else 1 }
        val done = list.filter { it.optBoolean("done") }

        // ---- 오늘 할 일 카드 (시계 아래)
        val cardL = 7f * u; val cardR = W - 7f * u
        val cardTop = H * 0.285f
        val item = tp(4.9f * u, INK, align = Paint.Align.LEFT)
        val starP = tp(4.9f * u, CORAL, align = Paint.Align.LEFT)
        val rows = (left + done).take(5)
        val more = list.size - rows.size
        val step = 9.4f * u
        val firstY = cardTop + 12.5f * u
        val cardBottom = if (list.isEmpty()) cardTop + 22f * u
            else firstY + step * (rows.size - 1) + (if (more > 0) 7.5f * u else 4.5f * u)
        plate(c, RectF(cardL, cardTop, cardR, cardBottom), 4f * u, CARD, GREEN, 1.5f * u, line)

        // 머리 딱지
        val chipText = when {
            list.isEmpty() -> "오늘 할 일"
            left.isEmpty() -> "오늘 할 일 모두 끝 ✓"
            else -> "오늘 할 일 · ${left.size}개 남음"
        }
        val chipP = tp(3.5f * u, INK, align = Paint.Align.LEFT)
        val chipW = chipP.measureText(chipText) + 5f * u
        val chip = RectF(cardL + 4f * u, cardTop - 3.4f * u, cardL + 4f * u + chipW, cardTop + 3.4f * u)
        plate(c, chip, 1.8f * u, GREEN, INK, 0.8f * u, line)
        c.drawText(chipText, chip.left + 2.5f * u, chip.centerY() + 1.25f * u, chipP)

        if (list.isEmpty()) {
            c.drawText("아직 안 적었어요", cx, cardTop + 12f * u, tp(5f * u, INK))
            c.drawText("아침에 앱에서 적으면 여기에 떠요", cx, cardTop + 18f * u, tp(3.3f * u, MUTED, bold = false))
        } else {
            val bs = 4.6f * u
            val x0 = cardL + 5f * u
            val textX = x0 + bs + 3f * u
            val maxW = cardR - 4f * u - textX
            val dash = stroke(INK, 0.22f * u).apply { alpha = 70; pathEffect = DashPathEffect(floatArrayOf(1.2f * u, 1.2f * u), 0f) }
            var y = firstY
            rows.forEachIndexed { i, t ->
                val isDone = t.optBoolean("done")
                val star = t.optBoolean("top") && !isDone
                val mid = y - 1.75f * u
                val r = RectF(x0, mid - bs / 2, x0 + bs, mid + bs / 2)
                if (isDone) {
                    c.drawRoundRect(r, 1.1f * u, 1.1f * u, fill(GREEN))
                    val p = Path()
                    p.moveTo(r.left + bs * 0.24f, r.top + bs * 0.52f)
                    p.lineTo(r.left + bs * 0.43f, r.top + bs * 0.72f)
                    p.lineTo(r.left + bs * 0.78f, r.top + bs * 0.30f)
                    c.drawPath(p, stroke(INK, 0.6f * u))
                } else c.drawRoundRect(r, 1.1f * u, 1.1f * u, fill(CARD))
                c.drawRoundRect(r, 1.1f * u, 1.1f * u, stroke(INK, line))
                var tx = textX
                val starW = if (star) starP.measureText("★ ") else 0f
                if (star) { c.drawText("★", tx, y, starP); tx += starW }
                item.color = if (isDone) FAINT else INK
                item.isStrikeThruText = isDone
                val txt = TextUtils.ellipsize(t.optString("text"), item, maxW - starW, TextUtils.TruncateAt.END).toString()
                c.drawText(txt, tx, y, item)
                if (i < rows.size - 1) c.drawLine(x0, y + 3.6f * u, cardR - 5f * u, y + 3.6f * u, dash)
                y += step
            }
            if (more > 0) c.drawText("외 ${more}개", cx, y - step + 6.2f * u, tp(3.2f * u, MUTED, bold = false))
        }

        // ---- 오늘의 한 줄: 복숭아 딱지 (화면 지문 센서보다 위)
        val qp = tp(4.5f * u, INK, align = Paint.Align.LEFT)
        val boxL = 11f * u; val boxR = W - 11f * u
        val layout = StaticLayout.Builder.obtain(advice, 0, advice.length, qp, (boxR - boxL - 8f * u).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(1.1f * u, 1f)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val top = minOf(cardBottom + 9f * u, H * 0.6f)
        val box = RectF(boxL, top, boxR, top + layout.height + 10f * u)
        plate(c, box, 4f * u, PEACH, CORAL, 1.3f * u, line)
        val labP = tp(3f * u, CARD)
        val labW = labP.measureText("오늘의 한 줄") + 4.5f * u
        val lab = RectF(cx - labW / 2, top - 2.9f * u, cx + labW / 2, top + 2.9f * u)
        c.drawRoundRect(lab, 1.6f * u, 1.6f * u, fill(INK))
        c.drawText("오늘의 한 줄", cx, lab.centerY() + 1.05f * u, labP)
        c.save()
        c.translate(boxL + 4f * u, top + 5.6f * u)
        layout.draw(c)
        c.restore()
        return bmp
    }
}
