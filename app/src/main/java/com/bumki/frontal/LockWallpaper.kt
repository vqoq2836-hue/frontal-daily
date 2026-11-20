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
            list.joinToString("|") { it.optString("text") + it.optBoolean("done") + it.optBoolean("top") } + "|v4"
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

    // 어두운 바탕, 흰 굵은 글씨, 보라 포인트
    private const val BG = 0xFF1B1C22.toInt()
    private const val WHITE = 0xFFF1F1F5.toInt()
    private const val SOFT = 0xFFB4B5C2.toInt()
    private const val DIMW = 0xFF6C6D79.toInt()
    private const val ACCENT = 0xFF8E95F2.toInt()

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

    fun render(w: Int, h: Int, list: List<JSONObject>, advice: String): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = w / 100f
        val W = w.toFloat()
        val H = h.toFloat()
        val cx = W / 2
        c.drawColor(BG)

        val left = list.filter { !it.optBoolean("done") }.sortedBy { if (it.optBoolean("top")) 0 else 1 }
        val done = list.filter { it.optBoolean("done") }

        // ---- 오늘 할 일 (시계 바로 아래)
        val labelY = H * 0.292f
        var listEnd = labelY + 18f * u   // 할 일 영역이 끝나는 높이 (그 아래에 '오늘의 한 줄')
        when {
            list.isEmpty() -> {
                c.drawText("오늘 할 일", cx, labelY, tp(3.9f * u, ACCENT))
                c.drawText("아직 안 적었어요", cx, labelY + 10f * u, tp(5.4f * u, WHITE))
                c.drawText("아침에 앱에서 적으면 여기에 떠요", cx, labelY + 17f * u, tp(3.4f * u, DIMW, bold = false))
            }
            else -> {
                c.drawText(if (left.isEmpty()) "오늘 할 일 모두 끝 ✓" else "오늘 할 일 · ${left.size}개 남음", cx, labelY, tp(3.9f * u, ACCENT))
                val rows = (left + done).take(5)
                val more = list.size - rows.size
                val item = tp(5.4f * u, WHITE, align = Paint.Align.LEFT)
                val starP = tp(5.4f * u, ACCENT, align = Paint.Align.LEFT)
                val bs = 5.0f * u
                val gapX = 3.0f * u
                val texts = rows.map { TextUtils.ellipsize(it.optString("text"), item, 70f * u, TextUtils.TruncateAt.END).toString() }
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
                var y = labelY + 13.5f * u
                val step = minOf(11.6f * u, (H * 0.55f - y) / rows.size)
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
                listEnd = y - step + 2f * u
                if (more > 0) { c.drawText("외 ${more}개", cx, y - step * 0.25f, tp(3.4f * u, DIMW, bold = false)); listEnd = y }
            }
        }

        // ---- 오늘의 한 줄 (아래쪽, 매일 바뀜)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        // 화면 지문 센서(아래쪽 ~75% 이하)를 피해서, 할 일 바로 아래에 둠
        val top = minOf(listEnd + 9f * u, H * 0.585f)
        c.drawRoundRect(RectF(cx - 4f * u, top, cx + 4f * u, top + 0.5f * u), 0.25f * u, 0.25f * u, bar)
        c.drawText("오늘의 한 줄", cx, top + 6f * u, tp(3.3f * u, ACCENT))
        val qp = tp(4.8f * u, WHITE, align = Paint.Align.LEFT)
        val layout = StaticLayout.Builder.obtain(advice, 0, advice.length, qp, (78f * u).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(1.2f * u, 1f)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        c.save()
        c.translate(cx - 39f * u, top + 9.5f * u)
        layout.draw(c)
        c.restore()
        return bmp
    }
}
