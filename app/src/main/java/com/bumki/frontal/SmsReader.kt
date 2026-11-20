package com.bumki.frontal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset

/**
 * 받은 문자함에서 카드·은행 알림처럼 보이는 문자만 골라 화면으로 넘깁니다.
 * 긴 문자(LMS)는 MMS 저장소에 들어가므로 둘 다 읽습니다. 내용은 폰 밖으로 나가지 않습니다.
 */
object SmsReader {
    const val PERMISSION = Manifest.permission.READ_SMS
    private val FIN = Regex("[0-9,]+\\s*원|입금|출금")
    private val FIN2 = Regex("승인|결제|입금|출금|이체|취소|체크|지급|매출")

    fun granted(ctx: Context) = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun looksFinancial(b: String) = FIN.containsMatchIn(b) && FIN2.containsMatchIn(b)

    fun read(ctx: Context, since: Long, limit: Int = 3000): String {
        val out = JSONArray()
        if (!granted(ctx)) return out.toString()
        val cr = ctx.contentResolver
        runCatching {
            cr.query(
                Uri.parse("content://sms/inbox"), arrayOf("_id", "address", "body", "date"),
                "date > ?", arrayOf(since.toString()), "date DESC"
            )?.use { c ->
                var n = 0
                while (c.moveToNext() && n < limit) {
                    val body = c.getString(2) ?: continue
                    if (!looksFinancial(body)) continue
                    out.put(JSONObject().put("id", "sms:" + c.getLong(0)).put("addr", c.getString(1) ?: "")
                        .put("body", body).put("date", c.getLong(3)))
                    n++
                }
            }
        }
        runCatching {
            cr.query(
                Uri.parse("content://mms/inbox"), arrayOf("_id", "date"),
                "date > ?", arrayOf((since / 1000).toString()), "date DESC"
            )?.use { c ->
                var n = 0
                while (c.moveToNext() && n < limit) {
                    val id = c.getLong(0)
                    val body = mmsText(ctx, id) ?: continue
                    if (!looksFinancial(body)) continue
                    out.put(JSONObject().put("id", "mms:$id").put("addr", mmsAddr(ctx, id))
                        .put("body", body).put("date", c.getLong(1) * 1000))
                    n++
                }
            }
        }
        return out.toString()
    }

    private fun charsetOf(mib: Int): Charset = runCatching {
        when (mib) {
            36, 38 -> Charset.forName("EUC-KR")
            1015 -> Charsets.UTF_16
            else -> Charsets.UTF_8
        }
    }.getOrDefault(Charsets.UTF_8)

    private fun mmsText(ctx: Context, id: Long): String? {
        val sb = StringBuilder()
        ctx.contentResolver.query(
            Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text", "_data", "chset"),
            "mid=?", arrayOf(id.toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) != "text/plain") continue
                val data = c.getString(3)
                val t = if (data != null) {
                    runCatching {
                        ctx.contentResolver.openInputStream(Uri.parse("content://mms/part/" + c.getLong(0)))
                            ?.use { String(it.readBytes(), charsetOf(c.getInt(4))) }
                    }.getOrNull()
                } else c.getString(2)
                if (!t.isNullOrEmpty()) sb.append(t)
            }
        }
        return sb.toString().ifEmpty { null }
    }

    private fun mmsAddr(ctx: Context, id: Long): String = runCatching {
        ctx.contentResolver.query(
            Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), "type=137", null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" } ?: ""
    }.getOrDefault("")
}
