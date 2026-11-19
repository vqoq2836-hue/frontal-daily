package com.bumki.frontal

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
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
import java.time.format.TextStyle
import java.util.Locale
import java.util.Random

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
            list.joinToString("|") { it.optString("text") + it.optBoolean("done") + it.optBoolean("top") } + "|v1"
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

    private const val INK = 0xFF2A2140.toInt()
    private const val MUTED = 0xFF8A7F92.toInt()
    private const val DIM = 0xFFAFA4AE.toInt()
    private const val ORANGE = 0xFFE0662F.toInt()
    private const val GREEN = 0xFF3E8E5E.toInt()
    private const val CREAM = 0xF5F7EEDC.toInt()

    fun render(w: Int, h: Int, list: List<JSONObject>): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = w / 100f
        val W = w.toFloat()
        val H = h.toFloat()

        // 하늘: 깊은 밤 → 노을
        val bg = Paint()
        bg.shader = LinearGradient(
            0f, 0f, 0f, H,
            intArrayOf(0xFF151331.toInt(), 0xFF2B2250.toInt(), 0xFF5E3760.toInt(), 0xFFC4664B.toInt()),
            floatArrayOf(0f, 0.42f, 0.76f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, W, H, bg)
        val star = Paint(Paint.ANTI_ALIAS_FLAG)
        val rs = Random(7)
        repeat(46) {
            star.color = (((40 + rs.nextInt(110)) shl 24) or 0xFFFFFF)
            c.drawCircle(rs.nextFloat() * W, rs.nextFloat() * H * 0.48f, (0.15f + rs.nextFloat() * 0.35f) * u, star)
        }
        // 낮은 건물 실루엣과 창문 불빛
        val city = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1630.toInt() }
        val win = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFD58A }
        val rc = Random(3)
        var x = -2 * u
        while (x < W) {
            val bw = (9 + rc.nextInt(13)) * u
            val bh = (7 + rc.nextInt(15)) * u
            c.drawRect(x, H - bh, x + bw - 0.7f * u, H, city)
            var wy = H - bh + 2.2f * u
            while (wy < H - 2 * u) {
                var wx = x + 1.6f * u
                while (wx < x + bw - 2.4f * u) {
                    if (rc.nextInt(5) == 0) c.drawRect(wx, wy, wx + 1.1f * u, wy + 1.4f * u, win)
                    wx += 2.6f * u
                }
                wy += 3.2f * u
            }
            x += bw
        }

        // 할 일 카드
        val left = list.filter { !it.optBoolean("done") }.sortedBy { if (it.optBoolean("top")) 0 else 1 }
        val done = list.filter { it.optBoolean("done") }
        val rows = (left + done).take(8)
        val more = list.size - rows.size
        val pad = 6 * u
        val cx = 7 * u
        val cw = W - 14 * u
        val lineH = 9.4f * u
        val headH = 16 * u
        val bodyH = if (list.isEmpty()) 15 * u else rows.size * lineH + (if (more > 0) 6 * u else 0f)
        val cardH = pad + headH + bodyH + pad * 0.6f
        var top = H * 0.50f
        if (top + cardH > H * 0.86f) top = maxOf(H * 0.30f, H * 0.86f - cardH)

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
        c.drawRoundRect(RectF(cx, top + 1.2f * u, cx + cw, top + cardH + 1.2f * u), 6 * u, 6 * u, shadow)
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CREAM }
        c.drawRoundRect(RectF(cx, top, cx + cw, top + cardH), 6 * u, 6 * u, card)

        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = 6.2f * u; color = INK }
        c.drawText("오늘 할 일", cx + pad, top + pad + 5.4f * u, head)

        val d = LocalDate.now()
        val dateText = "${d.monthValue}월 ${d.dayOfMonth}일 ${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}"
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = 3.5f * u; color = MUTED }
        c.drawText(dateText, cx + pad, top + pad + 11.6f * u, small)

        if (list.isNotEmpty()) {
            val badge = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = bold; textSize = 4.2f * u
                color = if (left.isEmpty()) GREEN else ORANGE
                textAlign = Paint.Align.RIGHT
            }
            c.drawText(if (left.isEmpty()) "모두 끝 ✓" else "${left.size}개 남음", cx + cw - pad, top + pad + 5f * u, badge)
        }

        val y0 = top + pad + headH
        if (list.isEmpty()) {
            val t1 = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = 4.6f * u; color = INK }
            c.drawText("아직 오늘 할 일을 안 적었어요", cx + pad, y0 + 5f * u, t1)
            c.drawText("앱을 열어 아침 미션에서 적어 보세요", cx + pad, y0 + 11f * u, small)
            return bmp
        }

        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.5f * u; color = INK }
        val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DIM }
        val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 0.7f * u; color = 0xFFFFFFFF.toInt()
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val item = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = 4.7f * u }
        val starP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = 4.7f * u; color = ORANGE }
        val bs = 5f * u
        rows.forEachIndexed { i, t ->
            val mid = y0 + i * lineH + lineH * 0.42f
            val isDone = t.optBoolean("done")
            val r = RectF(cx + pad, mid - bs / 2, cx + pad + bs, mid + bs / 2)
            if (isDone) {
                c.drawRoundRect(r, 1.2f * u, 1.2f * u, boxFill)
                val p = Path()
                p.moveTo(r.left + bs * 0.24f, r.top + bs * 0.52f)
                p.lineTo(r.left + bs * 0.43f, r.top + bs * 0.72f)
                p.lineTo(r.left + bs * 0.78f, r.top + bs * 0.30f)
                c.drawPath(p, check)
            } else {
                c.drawRoundRect(r, 1.2f * u, 1.2f * u, box)
            }
            var tx = cx + pad + bs + 3f * u
            val base = mid + 1.65f * u
            if (t.optBoolean("top") && !isDone) {
                c.drawText("★", tx, base, starP)
                tx += starP.measureText("★ ")
            }
            item.color = if (isDone) DIM else INK
            item.isStrikeThruText = isDone
            val avail = cx + cw - pad - tx
            val s = TextUtils.ellipsize(t.optString("text"), item, avail, TextUtils.TruncateAt.END).toString()
            c.drawText(s, tx, base, item)
        }
        if (more > 0) c.drawText("외 ${more}개", cx + pad, y0 + rows.size * lineH + 3.5f * u, small)
        return bmp
    }
}
