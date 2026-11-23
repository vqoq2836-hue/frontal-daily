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

    /** 잠금화면에 함께 그릴 오늘 정보 */
    data class Extra(
        val nextLabel: String, val nextText: String, val score100: Int, val goal100: Int,
        val steps: Long?, val engEn: String, val engKo: String, val period: String
    )

    fun periodOf(hour: Int) = when (hour) { in 5..10 -> "dawn"; in 11..16 -> "day"; in 17..19 -> "dusk"; else -> "night" }

    fun extra(ctx: Context): Extra {
        val st = Reminders.stateObj(ctx)
        val info = runCatching { Reminders.widgetInfo(ctx) }.getOrNull()
        val max = st.optInt("maxPts", 0)
        val sc = if (info != null && max > 0) Math.round(info.score * 100f / max) else 0
        val gl = if (info != null && max > 0) Math.round(info.goal * 100f / max) else 72
        val cur = info?.current
        val (lab, txt) = when {
            info == null || info.empty -> "오늘" to "루틴을 앱에서 확인해요"
            info.state == "done" || cur == null -> "오늘" to "루틴 끝 · 푹 쉬어요"
            else -> (if (info.state == "now") "지금" else "다음") to (cur.optString("time") + "  " + cur.optString("title"))
        }
        val eng = st.optJSONObject("lockEng")
        return Extra(lab, txt, sc.coerceIn(0, 100), gl, info?.steps,
            eng?.optString("en") ?: "", eng?.optString("ko") ?: "", periodOf(java.time.LocalTime.now().hour))
    }

    /** 바로 적용: "ok" · "same" · "error:..." */
    fun apply(ctx: Context, force: Boolean): String = synchronized(lock) {
        val list = todos(ctx)
        val advice = adviceFor(ctx, LocalDate.now())
        val ex = extra(ctx)
        val sig = LocalDate.now().toString() + "|" + advice + "|" +
            list.joinToString("|") { it.optString("text") + it.optBoolean("done") + it.optBoolean("top") } +
            "|" + ex.nextText + "|" + ex.score100 + "|" + ((ex.steps ?: -1L) / 100) + "|" + ex.engEn + "|" + ex.period + "|v6"
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && prefs.getString(KEY_SIG, null) == sig) return "same"
        return try {
            val (w, h) = screenSize(ctx)
            val bmp = render(w, h, list, advice, ex)
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
        val full = render(w, h, todos(ctx), adviceFor(ctx, LocalDate.now()), extra(ctx))
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
    private const val SKY = 0xFFD3E8F8.toInt()
    private const val BLUE = 0xFF8EC5F0.toInt()
    private const val LAV = 0xFFD9DDF2.toInt()
    private const val NAVY = 0xFF2B3A55.toInt()
    private const val MINTL = 0xFF7FCBA8.toInt()

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

    private fun layoutOf(text: String, p: TextPaint, width: Int, lines: Int, u: Float) =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0.9f * u, 1f)
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()

    /** 이름표 붙은 딱지 (오늘의 한 줄 · 오늘의 영어) */
    private fun labeledBox(c: Canvas, u: Float, cx: Float, top: Float, bottom: Float, label: String, face: Int, shadow: Int, line: Float) {
        plate(c, RectF(11f * u, top, c.width - 11f * u, bottom), 4f * u, face, shadow, 1.3f * u, line)
        val labP = tp(2.9f * u, CARD)
        val labW = labP.measureText(label) + 4.5f * u
        val lab = RectF(cx - labW / 2, top - 2.8f * u, cx + labW / 2, top + 2.8f * u)
        c.drawRoundRect(lab, 1.6f * u, 1.6f * u, fill(INK))
        c.drawText(label, cx, lab.centerY() + 1.0f * u, labP)
    }

    private fun plus(c: Canvas, x: Float, y: Float, r: Float, p: Paint) { c.drawLine(x - r, y, x + r, y, p); c.drawLine(x, y - r, x, y + r, p) }

    /** 시간대 그림: 시계 뒤 큰 별색 원 + 해/달 */
    private fun scene(c: Canvas, W: Float, H: Float, u: Float, line: Float, period: String) {
        val big = when (period) { "day" -> SKY; "night" -> LAV; else -> PEACH }
        val bx = W * 0.86f; val by = H * 0.115f; val br = W * 0.36f
        c.drawCircle(bx, by, br, fill(big))
        c.drawCircle(bx - 1.2f * u, by - 1f * u, br, stroke(INK, line * 0.8f))
        val ink = stroke(INK, line)
        val sx = W * 0.79f; val sy = H * 0.072f; val sr = 6.5f * u
        when (period) {
            "dawn" -> {
                c.drawCircle(sx, sy, sr, fill(CORAL)); c.drawCircle(sx - 0.8f * u, sy - 0.7f * u, sr, ink)
                for (k in 0 until 8) { val a = Math.toRadians(k * 45.0); val r1 = sr + 2f * u; val r2 = sr + 4.2f * u
                    c.drawLine(sx + (r1 * Math.cos(a)).toFloat(), sy + (r1 * Math.sin(a)).toFloat(), sx + (r2 * Math.cos(a)).toFloat(), sy + (r2 * Math.sin(a)).toFloat(), ink) }
            }
            "day" -> {
                c.drawCircle(sx, sy, sr, fill(YELLOW)); c.drawCircle(sx - 0.8f * u, sy - 0.7f * u, sr, ink)
                val cl = Path().apply { addRoundRect(RectF(sx - 15f * u, sy + 2f * u, sx - 1f * u, sy + 8f * u), 3f * u, 3f * u, Path.Direction.CW)
                    addCircle(sx - 10f * u, sy + 2.5f * u, 3.6f * u, Path.Direction.CW); addCircle(sx - 5.5f * u, sy + 1.5f * u, 4.2f * u, Path.Direction.CW) }
                c.drawPath(cl, fill(CARD)); c.save(); c.translate(-0.7f * u, -0.6f * u); c.drawPath(cl, ink); c.restore()
            }
            "dusk" -> {
                c.drawCircle(sx, sy + 2f * u, sr * 1.2f, fill(CORAL))
                c.drawLine(sx - 14f * u, sy + 3f * u, sx + 9f * u, sy + 3f * u, ink)
                c.drawLine(sx - 10f * u, sy + 6f * u, sx + 5f * u, sy + 6f * u, ink)
            }
            else -> {
                c.drawCircle(sx, sy, sr, fill(YELLOW)); c.drawCircle(sx + 3f * u, sy - 1.8f * u, sr * 0.85f, fill(LAV))
                plus(c, W * 0.6f, H * 0.045f, 1.3f * u, ink); plus(c, W * 0.93f, H * 0.17f, 1.3f * u, ink); plus(c, W * 0.68f, H * 0.19f, 1f * u, ink)
            }
        }
    }

    /** 아래 풍경: 언덕 두 겹 + 집 + 나무 + 점선 길 (지문 센서 아래쪽) */
    private fun landscape(c: Canvas, W: Float, H: Float, u: Float, line: Float, night: Boolean) {
        val ridge = Path().apply { moveTo(0f, H * 0.905f); cubicTo(W * 0.3f, H * 0.865f, W * 0.62f, H * 0.915f, W, H * 0.872f) }
        c.drawPath(Path(ridge).apply { lineTo(W, H); lineTo(0f, H); close() }, fill(MINT))
        val r2 = Path().apply { moveTo(0f, H * 0.955f); cubicTo(W * 0.35f, H * 0.93f, W * 0.7f, H * 0.965f, W, H * 0.94f); lineTo(W, H); lineTo(0f, H); close() }
        c.drawPath(r2, fill(MINT2))
        c.save(); c.translate(-1f * u, -0.8f * u); c.drawPath(ridge, stroke(INK, line * 0.8f)); c.restore()
        val ink = stroke(INK, line * 0.9f)
        // 집
        val hx = W * 0.12f; val hy = H * 0.893f
        val house = Path().apply { moveTo(hx, hy); lineTo(hx + 8f * u, hy - 6.5f * u); lineTo(hx + 16f * u, hy); lineTo(hx + 16f * u, hy + 10f * u); lineTo(hx, hy + 10f * u); close() }
        c.drawPath(house, fill(PEACH)); c.drawPath(house, ink)
        val win = RectF(hx + 5.8f * u, hy + 3.5f * u, hx + 10.2f * u, hy + 10f * u)
        c.drawRect(win, fill(if (night) YELLOW else CARD)); c.drawRect(win, ink)
        // 나무
        val tx = W * 0.85f; val ty = H * 0.865f
        c.drawRect(tx - 0.8f * u, ty, tx + 0.8f * u, ty + 8.5f * u, fill(CORAL)); c.drawRect(tx - 0.8f * u, ty, tx + 0.8f * u, ty + 8.5f * u, ink)
        c.drawCircle(tx, ty - 2.8f * u, 5.6f * u, fill(GREEN)); c.drawCircle(tx, ty - 2.8f * u, 5.6f * u, ink)
        c.drawCircle(tx - 4.3f * u, ty + 1f * u, 3.4f * u, fill(MINTL)); c.drawCircle(tx - 4.3f * u, ty + 1f * u, 3.4f * u, ink)
        // 점선 길
        val path = Path().apply { moveTo(W * 0.42f, H); cubicTo(W * 0.47f, H * 0.95f, W * 0.6f, H * 0.92f, W * 0.66f, H * 0.886f) }
        c.drawPath(path, stroke(INK, line * 0.8f).apply { pathEffect = DashPathEffect(floatArrayOf(1.2f * u, 1.6f * u), 0f) })
    }

    fun render(w: Int, h: Int, list: List<JSONObject>, advice: String, ex: Extra): Bitmap {
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
        scene(c, W, H, u, line, ex.period)
        landscape(c, W, H, u, line, ex.period == "night")

        val left = list.filter { !it.optBoolean("done") }.sortedBy { if (it.optBoolean("top")) 0 else 1 }
        val done = list.filter { it.optBoolean("done") }
        val all = left + done

        // ---- 자리 계산: 영어 딱지 아래 끝이 지문 센서(화면 약 78% 아래) 위에 오도록, 넘치면 할 일 줄 수를 줄임
        val bandTop = H * 0.283f; val bandH = 10f * u
        val cardTop = bandTop + bandH + 7.5f * u
        val step = 8.6f * u
        val boxW = (W - 22f * u - 8f * u).toInt()
        val advLayout = layoutOf(advice.replace("\n", " "), tp(4.1f * u, INK, align = Paint.Align.LEFT), boxW, 2, u)
        val hasEng = ex.engEn.isNotBlank()
        val enLayout = layoutOf(ex.engEn, tp(3.6f * u, INK, align = Paint.Align.LEFT), boxW, 2, u)
        val koLayout = layoutOf(ex.engKo, tp(3.0f * u, MUTED, bold = false, align = Paint.Align.LEFT), boxW, 1, u)
        val limit = H * 0.775f
        var n = minOf(4, all.size)
        fun cardBottom(k: Int, more: Int) = if (list.isEmpty()) cardTop + 20f * u else cardTop + 12f * u + step * (k - 1) + (if (more > 0) 7f * u else 4.5f * u)
        fun endOf(k: Int): Float {
            val cb = cardBottom(k, all.size - k)
            val advB = cb + 8f * u + advLayout.height + 9f * u
            return if (hasEng) advB + 7f * u + enLayout.height + koLayout.height + 10f * u else advB
        }
        while (n > 1 && endOf(n) > limit) n--
        val rows = all.take(n)
        val more = all.size - rows.size
        val cardB = cardBottom(n, more)

        // ---- 다음 할 일 띠 (남색)
        val band = RectF(7f * u, bandTop, W - 7f * u, bandTop + bandH)
        plate(c, band, 3.5f * u, NAVY, YELLOW, 1.2f * u, line)
        val labP = tp(3.1f * u, YELLOW, align = Paint.Align.LEFT)
        c.drawText(ex.nextLabel, band.left + 4.5f * u, band.centerY() + 1.1f * u, labP)
        val nxP = tp(4.2f * u, CARD, align = Paint.Align.LEFT)
        val nxX = band.left + 5.5f * u + labP.measureText(ex.nextLabel) + 2.5f * u
        val nx = TextUtils.ellipsize(ex.nextText, nxP, band.right - 4f * u - nxX, TextUtils.TruncateAt.END).toString()
        c.drawText(nx, nxX, band.centerY() + 1.5f * u, nxP)

        // ---- 오늘 할 일 카드
        val cardL = 7f * u; val cardR = W - 7f * u
        plate(c, RectF(cardL, cardTop, cardR, cardB), 4f * u, CARD, GREEN, 1.5f * u, line)
        val chipText = when {
            list.isEmpty() -> "오늘 할 일"
            left.isEmpty() -> "오늘 할 일 모두 끝 ✓"
            else -> "오늘 할 일 · ${left.size}개 남음"
        }
        val chipP = tp(3.4f * u, INK, align = Paint.Align.LEFT)
        val chip = RectF(cardL + 4f * u, cardTop - 3.3f * u, cardL + 4f * u + chipP.measureText(chipText) + 5f * u, cardTop + 3.3f * u)
        plate(c, chip, 1.8f * u, GREEN, INK, 0.8f * u, line)
        c.drawText(chipText, chip.left + 2.5f * u, chip.centerY() + 1.2f * u, chipP)
        // 걸음 딱지 (카드 오른쪽 위)
        ex.steps?.let { st ->
            val t = "걸음 " + String.format("%,d", st)
            val sp = tp(3.2f * u, INK, align = Paint.Align.LEFT)
            val sw = sp.measureText(t) + 5f * u
            val sr = RectF(cardR - 4f * u - sw, cardTop - 3.3f * u, cardR - 4f * u, cardTop + 3.3f * u)
            plate(c, sr, 1.8f * u, SKY, INK, 0.8f * u, line)
            c.drawText(t, sr.left + 2.5f * u, sr.centerY() + 1.15f * u, sp)
        }
        val stampX = W - 15.5f * u; val stampY = cardB - 6f * u; val stampR = 7.6f * u
        if (list.isEmpty()) {
            c.drawText("아직 안 적었어요", cx, cardTop + 11.5f * u, tp(4.8f * u, INK))
            c.drawText("아침에 앱에서 적으면 여기에 떠요", cx, cardTop + 17f * u, tp(3.2f * u, MUTED, bold = false))
        } else {
            val item = tp(4.7f * u, INK, align = Paint.Align.LEFT)
            val starP = tp(4.7f * u, CORAL, align = Paint.Align.LEFT)
            val bs = 4.4f * u
            val x0 = cardL + 5f * u
            val textX = x0 + bs + 3f * u
            val dash = stroke(INK, 0.22f * u).apply { alpha = 70; pathEffect = DashPathEffect(floatArrayOf(1.2f * u, 1.2f * u), 0f) }
            var y = cardTop + 12f * u
            rows.forEachIndexed { i, t ->
                val isDone = t.optBoolean("done")
                val star = t.optBoolean("top") && !isDone
                val mid = y - 1.7f * u
                val r = RectF(x0, mid - bs / 2, x0 + bs, mid + bs / 2)
                if (isDone) {
                    c.drawRoundRect(r, 1.1f * u, 1.1f * u, fill(GREEN))
                    val p = Path()
                    p.moveTo(r.left + bs * 0.24f, r.top + bs * 0.52f); p.lineTo(r.left + bs * 0.43f, r.top + bs * 0.72f); p.lineTo(r.left + bs * 0.78f, r.top + bs * 0.30f)
                    c.drawPath(p, stroke(INK, 0.6f * u))
                } else c.drawRoundRect(r, 1.1f * u, 1.1f * u, fill(CARD))
                c.drawRoundRect(r, 1.1f * u, 1.1f * u, stroke(INK, line))
                var tx = textX
                val starW = if (star) starP.measureText("★ ") else 0f
                if (star) { c.drawText("★", tx, y, starP); tx += starW }
                item.color = if (isDone) FAINT else INK
                item.isStrikeThruText = isDone
                // 도장과 겹치는 아래쪽 줄은 글자 폭을 줄임
                val rightEdge = if (y > stampY - stampR - 3f * u) stampX - stampR - 2f * u else cardR - 4f * u
                val txt = TextUtils.ellipsize(t.optString("text"), item, rightEdge - tx, TextUtils.TruncateAt.END).toString()
                c.drawText(txt, tx, y, item)
                if (i < rows.size - 1) c.drawLine(x0, y + 3.3f * u, cardR - 5f * u, y + 3.3f * u, dash)
                y += step
            }
            if (more > 0) c.drawText("외 ${more}개", x0 + 22f * u, y - step + 5.8f * u, tp(3.1f * u, MUTED, bold = false))
        }

        // ---- 점수 도장 (카드 오른쪽 아래, 살짝 기울게)
        c.save()
        c.rotate(-12f, stampX, stampY)
        c.drawCircle(stampX + 0.6f * u, stampY + 0.6f * u, stampR, fill(INK))
        c.drawCircle(stampX, stampY, stampR, fill(CORAL))
        c.drawCircle(stampX, stampY, stampR, stroke(INK, line))
        c.drawCircle(stampX, stampY, stampR - 1.2f * u, stroke(CARD, 0.25f * u))
        c.drawText("오늘", stampX, stampY - 2.5f * u, tp(1.9f * u, CARD))
        c.drawText("${ex.score100}점", stampX, stampY + 1.4f * u, tp(3.7f * u, CARD))
        c.drawText("목표 ${ex.goal100}", stampX, stampY + 4.5f * u, tp(1.7f * u, CARD))
        c.restore()

        // ---- 오늘의 한 줄 (복숭아)
        val advTop = cardB + 8f * u
        val advB = advTop + advLayout.height + 9f * u
        labeledBox(c, u, cx, advTop, advB, "오늘의 한 줄", PEACH, CORAL, line)
        c.save(); c.translate(15f * u, advTop + 4.8f * u); advLayout.draw(c); c.restore()

        // ---- 오늘의 영어 (하늘색)
        if (hasEng) {
            val eTop = advB + 7f * u
            val eB = eTop + enLayout.height + koLayout.height + 10f * u
            labeledBox(c, u, cx, eTop, eB, "오늘의 영어", SKY, BLUE, line)
            c.save(); c.translate(15f * u, eTop + 4.8f * u); enLayout.draw(c); c.translate(0f, enLayout.height + 0.8f * u); koLayout.draw(c); c.restore()
        }
        return bmp
    }
}
