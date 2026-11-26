package com.bumki.frontal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.widget.RemoteViews
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 2×2 홈 화면 위젯: 위 띠에 '이번 달 쓸 돈'(남을수록 민트 → 노랑 → 빨강), 아래 남색 칸에 오늘의 영어.
 * 띠를 누르면 가계부, 영어를 누르면 지금 화면, 스피커를 누르면 앱을 열지 않고 바로 읽어 줘요.
 */
class MoneyEngWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { draw(ctx, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle?) {
        draw(ctx, manager, id)
    }

    companion object {
        private const val PREFS = "frontal"
        private const val KEY_MONEY = "w_money"

        private const val CARD = 0xFFFFFDF8.toInt()
        private const val INK = 0xFF1F2A3A.toInt()
        private const val MUTED = 0xFF5E6573.toInt()
        private const val NAVY = 0xFF2B3A55.toInt()
        private const val YELLOW = 0xFFFFD36E.toInt()
        private const val MINT = 0xFFCDEBDB.toInt()
        private const val GREEN = 0xFF43B489.toInt()
        private const val ORANGE = 0xFFFF963C.toInt()
        private const val ORANGE_SH = 0xFFE68C32.toInt()
        private const val RED = 0xFFE23A2E.toInt()
        private const val KO_SOFT = 0xFFC8D2E1.toInt()

        fun updateAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, MoneyEngWidget::class.java))
            rememberMoney(ctx)
            if (ids.isEmpty()) return
            ids.forEach { draw(ctx, manager, it) }
        }

        /** 앱이 보낸 돈 요약을 따로 보관 (가계부를 아직 못 불러온 순간에 빈 값으로 덮지 않도록) */
        private fun rememberMoney(ctx: Context) {
            val m = Reminders.stateObj(ctx).optJSONObject("money") ?: return
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MONEY, m.toString()).apply()
        }

        private fun money(ctx: Context): JSONObject? {
            val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MONEY, null) ?: return null
            return runCatching { JSONObject(s) }.getOrNull()
        }

        private fun draw(ctx: Context, manager: AppWidgetManager, id: Int) {
            runCatching {
                val opt = manager.getAppWidgetOptions(id)
                val dens = ctx.resources.displayMetrics.density
                val wDp = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 180
                val hDp = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 180
                var w = wDp * dens; var h = hDp * dens
                val cap = 720f / max(w, h); if (cap < 1f) { w *= cap; h *= cap }

                val st = Reminders.stateObj(ctx)
                val eng = st.optJSONObject("lockEng")
                val en = eng?.optString("en").orEmpty()
                val ko = eng?.optString("ko").orEmpty()
                val bmp = render(w.toInt().coerceAtLeast(120), h.toInt().coerceAtLeast(120), money(ctx), en, ko)

                val rv = RemoteViews(ctx.packageName, R.layout.widget_money_eng)
                rv.setImageViewBitmap(R.id.w2_img, bmp)
                rv.setOnClickPendingIntent(R.id.w2_money, openIntent(ctx, "money", 9101))
                rv.setOnClickPendingIntent(R.id.w2_eng, openIntent(ctx, "eng", 9102))
                if (en.isNotEmpty()) {
                    val speak = Intent(ctx, WidgetSpeakReceiver::class.java).putExtra("text", en)
                    rv.setOnClickPendingIntent(
                        R.id.w2_speak,
                        PendingIntent.getBroadcast(ctx, 9103, speak, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    )
                } else rv.setOnClickPendingIntent(R.id.w2_speak, openIntent(ctx, "eng", 9104))
                manager.updateAppWidget(id, rv)
            }
        }

        private fun openIntent(ctx: Context, where: String, code: Int): PendingIntent =
            PendingIntent.getActivity(
                ctx, code,
                Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, where)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /** 원 단위를 짧게: 1,423,000 → 142만원 · 92,000 → 9만 2천원 · 8,300 → 8,300원 */
        fun won(v: Long): String {
            val a = abs(v)
            return when {
                a >= 100_000 -> "${a / 10_000}만원"
                a >= 10_000 -> { val ch = (a % 10_000) / 1_000; if (ch > 0) "${a / 10_000}만 ${ch}천원" else "${a / 10_000}만원" }
                else -> "%,d원".format(a)
            }
        }

        private fun tp(size: Float, color: Int, bold: Boolean = true, align: Paint.Align = Paint.Align.LEFT) =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
                textSize = size; this.color = color; textAlign = align
            }

        private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = width
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        private fun layout(text: String, p: TextPaint, width: Int, lines: Int, extra: Float) =
            StaticLayout.Builder.obtain(text, 0, text.length, p, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(extra, 1f)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()

        /** 아주 옅은 판화 점 (눈에 거슬리지 않게) */
        private fun grain(c: Canvas, r: RectF, color: Int, alpha: Int, u: Float, seed: Long) {
            val rnd = Random(seed)
            val p = fill(color).apply { this.alpha = alpha }
            val n = (r.width() * r.height() / (2400f * u * u)).toInt()
            repeat(n) {
                val x = r.left + rnd.nextFloat() * r.width()
                val y = r.top + rnd.nextFloat() * r.height()
                c.drawCircle(x, y, (0.6f + rnd.nextFloat() * 0.5f) * u, p)
            }
        }

        fun render(W: Int, H: Int, m: JSONObject?, en: String, ko: String): Bitmap {
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val u = min(W, H) / 470f
            val off = 9f * u
            val line = 4f * u

            // 돈 상태
            val budget = m?.optLong("budget", 0L) ?: 0L
            val sameMonth = m?.optString("ym") == LocalDate.now().toString().substring(0, 7)
            val used = if (sameMonth) m?.optLong("used", 0L) ?: 0L else 0L
            val left = budget - used
            val ratio = if (budget > 0) left.toFloat() / budget else 1f
            val state = when { budget <= 0 -> 0; ratio > 0.5f -> 0; ratio > 0.2f -> 1; else -> 2 }
            val face = intArrayOf(MINT, YELLOW, RED)[state]
            val bar = intArrayOf(GREEN, ORANGE, INK)[state]
            val shadow = intArrayOf(GREEN, ORANGE_SH, RED)[state]
            val txt = if (state == 2) CARD else INK
            val sub = if (state == 2) CARD else MUTED

            // 바깥 판 (그림자 색도 상태를 따라감)
            val outer = RectF(line / 2, line / 2, W - off - line / 2, H - off - line / 2)
            val rad = 40f * u
            c.drawRoundRect(RectF(outer.left + off, outer.top + off, outer.right + off, outer.bottom + off), rad, rad, fill(shadow))
            c.drawRoundRect(outer, rad, rad, fill(CARD))

            // 위 띠
            val bandH = outer.top + (outer.height() * 0.272f)
            val clip = Path().apply { addRoundRect(outer, rad, rad, Path.Direction.CW) }
            c.save(); c.clipPath(clip)
            c.drawRect(outer.left, outer.top, outer.right, bandH, fill(face))
            grain(c, RectF(outer.left, outer.top, outer.right, bandH), if (state == 2) CARD else INK, 26, u, 11)
            c.restore()
            c.drawLine(outer.left, bandH, outer.right, bandH, stroke(INK, line).apply { strokeCap = Paint.Cap.BUTT })
            c.drawRoundRect(outer, rad, rad, stroke(INK, line))

            val v = (bandH - outer.top) / 128f
            val x0 = outer.left + 28f * u
            val x1 = outer.right - 28f * u
            c.drawText("이번 달 쓸 돈", x0, outer.top + 46f * v, tp(19f * u, sub, bold = true))
            val amt = when {
                budget <= 0 -> "앱에서 설정"
                left < 0 -> won(-left) + " 초과"
                else -> won(left)
            }
            val amtP = tp(if (budget <= 0) 24f * u else 38f * u, txt, align = Paint.Align.RIGHT)
            val labW = tp(19f * u, sub).measureText("이번 달 쓸 돈") + 14f * u
            while (amtP.measureText(amt) > (x1 - x0 - labW) && amtP.textSize > 18f * u) amtP.textSize -= 2f * u
            c.drawText(amt, x1, outer.top + 54f * v, amtP)
            val by = outer.top + 76f * v
            val bh = 12f * u
            val barR = RectF(x0, by, x1, by + bh)
            c.drawRoundRect(barR, bh / 2, bh / 2, fill(CARD))
            if (budget > 0) {
                val p = (used.toFloat() / budget).coerceIn(0f, 1f)
                if (p > 0f) c.drawRoundRect(RectF(x0, by, x0 + max(bh, (x1 - x0) * p), by + bh), bh / 2, bh / 2, fill(bar))
            }
            c.drawRoundRect(barR, bh / 2, bh / 2, stroke(INK, 3f * u))
            val pctTxt = if (budget > 0) "${Math.round(used * 100f / budget)}% 사용 · 한 달 ${won(budget)}" else "가계부에서 한 달 생활비를 정해 주세요"
            c.drawText(pctTxt, x0, by + bh + 24f * v, tp(16f * u, sub, bold = false))

            // 아래 남색 영어 칸
            val e = RectF(outer.left + 18f * u, bandH + 18f * u, outer.right - 18f * u, outer.bottom - 18f * u)
            c.drawRoundRect(e, 26f * u, 26f * u, fill(NAVY))
            c.save(); c.clipPath(Path().apply { addRoundRect(e, 26f * u, 26f * u, Path.Direction.CW) })
            grain(c, e, CARD, 22, u, 23)
            c.restore()
            val ex0 = e.left + 24f * u
            c.drawText("오늘의 영어", ex0, e.top + 40f * u, tp(18f * u, YELLOW))
            // 스피커
            val sx = e.right - 40f * u; val sy = e.top + 36f * u; val sr = 20f * u
            c.drawCircle(sx, sy, sr, fill(YELLOW)); c.drawCircle(sx, sy, sr, stroke(INK, 3f * u))
            val sp = Path().apply {
                moveTo(sx - 9f * u, sy - 5f * u); lineTo(sx - 3f * u, sy - 5f * u); lineTo(sx + 5f * u, sy - 11f * u)
                lineTo(sx + 5f * u, sy + 11f * u); lineTo(sx - 3f * u, sy + 5f * u); lineTo(sx - 9f * u, sy + 5f * u); close()
            }
            c.drawPath(sp, fill(INK))

            val tw = (e.width() - 48f * u).toInt()
            val top = e.top + 64f * u
            val avail = e.bottom - 16f * u - top
            val enText = en.ifEmpty { "앱을 한 번 열어 주세요" }
            var size = 34f * u
            var enL: StaticLayout
            var koL: StaticLayout
            while (true) {
                enL = layout(enText, tp(size, CARD), tw, 4, 4f * u)
                koL = layout(ko, tp(max(16f * u, size * 0.6f), KO_SOFT, bold = false), tw, 2, 2f * u)
                val need = enL.height + (if (ko.isEmpty()) 0f else 10f * u + koL.height)
                if (need <= avail || size <= 20f * u) break
                size -= 2f * u
            }
            // 남는 공간이 있으면 문장을 살짝 아래로 내려 칸 가운데쯤에 오게
            val need = enL.height + (if (ko.isEmpty()) 0f else 10f * u + koL.height)
            val y0 = top + max(0f, avail - need) * 0.4f
            c.save(); c.translate(ex0, y0); enL.draw(c); c.restore()
            if (ko.isNotEmpty()) { c.save(); c.translate(ex0, y0 + enL.height + 10f * u); koL.draw(c); c.restore() }
            return bmp
        }
    }
}

/** 위젯 스피커: 앱을 열지 않고 오늘의 영어를 읽어 줘요. 기본 음성에 영어가 없으면 구글 음성으로 한 번 더. */
class WidgetSpeakReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: return
        val app = ctx.applicationContext
        val pending = goAsync()
        val done = AtomicBoolean(false)
        var tts: TextToSpeech? = null
        val main = Handler(Looper.getMainLooper())
        fun finish() {
            if (!done.compareAndSet(false, true)) return
            runCatching { tts?.shutdown() }
            runCatching { pending.finish() }
        }
        main.postDelayed({ finish() }, 9_000)

        fun start(engine: String?, triedGoogle: Boolean) {
            val listener = TextToSpeech.OnInitListener { st ->
                val t = tts
                if (st == TextToSpeech.SUCCESS && t != null) {
                    var r = t.setLanguage(Locale.US)
                    if (r < TextToSpeech.LANG_AVAILABLE) r = t.setLanguage(Locale.ENGLISH)
                    if (r >= TextToSpeech.LANG_AVAILABLE) {
                        t.setSpeechRate(0.9f)
                        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {}
                            override fun onDone(id: String?) { main.post { finish() } }
                            @Deprecated("old api") override fun onError(id: String?) { main.post { finish() } }
                        })
                        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "widget") != TextToSpeech.SUCCESS) finish()
                        return@OnInitListener
                    }
                }
                runCatching { tts?.shutdown() }; tts = null
                val google = "com.google.android.tts"
                val hasGoogle = runCatching { app.packageManager.getPackageInfo(google, 0); true }.getOrDefault(false)
                if (!triedGoogle && hasGoogle && engine != google) start(google, true) else finish()
            }
            tts = if (engine == null) TextToSpeech(app, listener) else TextToSpeech(app, listener, engine)
        }
        start(null, false)
    }
}
