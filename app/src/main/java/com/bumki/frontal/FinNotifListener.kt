package com.bumki.frontal

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * 카드사·은행·페이 앱 알림(푸시)과 카카오톡 알림톡에서 결제처럼 보이는 것만 모아 둡니다.
 * 앱을 열면 화면이 가져가서 가계부에 넣거나, 애매하면 '확인 필요'로 물어봅니다.
 * 내용은 폰 안에만 저장됩니다.
 */
class FinNotifListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { capture(applicationContext, sbn) }
    }

    companion object {
        private const val PREFS = "frontal"
        private const val KEY = "fin_notifs"
        private val FIN = Regex("[0-9,]+\\s*원|입금|출금")
        private val FIN2 = Regex("승인|결제|입금|출금|이체|취소|체크|지급|매출|사용")
        // 문자는 따로 읽으므로 메시지 앱 알림은 건너뜀(중복 방지)
        private val SKIP = setOf(
            "com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.mms"
        )

        fun enabled(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

        fun component(ctx: Context) = ComponentName(ctx, FinNotifListener::class.java)

        private fun capture(ctx: Context, sbn: StatusBarNotification) {
            val pkg = sbn.packageName ?: return
            if (pkg == ctx.packageName || pkg in SKIP) return
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val ex = n.extras ?: return
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val text = big ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val body = (title + "\n" + text).trim()
            if (!(FIN.containsMatchIn(body) && FIN2.containsMatchIn(body))) return
            val label = runCatching {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val id = "noti:" + pkg + ":" + (body.hashCode().toLong() and 0xffffffffL)
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            synchronized(this) {
                val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
                for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") == id) return
                arr.put(JSONObject().put("id", id).put("pkg", pkg).put("app", label).put("addr", label)
                    .put("body", body).put("date", sbn.postTime))
                // 너무 쌓이지 않게 최근 500건만
                val trimmed = JSONArray()
                val from = maxOf(0, arr.length() - 500)
                for (i in from until arr.length()) trimmed.put(arr.get(i))
                prefs.edit().putString(KEY, trimmed.toString()).apply()
            }
        }

        /** 모아 둔 알림을 넘기고 비움 */
        fun take(ctx: Context): String {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            synchronized(this) {
                val s = prefs.getString(KEY, "[]") ?: "[]"
                prefs.edit().putString(KEY, "[]").apply()
                return s
            }
        }
    }
}
