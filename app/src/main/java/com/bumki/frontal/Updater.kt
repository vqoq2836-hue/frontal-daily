package com.bumki.frontal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub의 latest-apk 릴리스에서 새 버전이 있는지 확인하고,
 * 있으면 APK를 받아 안드로이드 설치 창을 띄웁니다.
 */
object Updater {
    private const val BASE = "https://github.com/vqoq2836-hue/frontal-daily/releases/download/latest-apk/"

    fun currentVersionCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "FrontalDaily")
        return c
    }

    suspend fun check(ctx: Context): String = withContext(Dispatchers.IO) {
        val out = JSONObject()
        try {
            val text = open(BASE + "version.json").inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(text)
            val remote = j.optLong("versionCode", 0)
            val cur = currentVersionCode(ctx)
            out.put("available", remote > cur)
                .put("versionName", j.optString("versionName"))
                .put("current", currentVersionName(ctx))
        } catch (e: Exception) {
            out.put("available", false).put("checkError", e.javaClass.simpleName)
        }
        out.toString()
    }

    /** @return "started" | "need_permission" | "error:..." */
    suspend fun downloadAndInstall(activity: Activity): String {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            withContext(Dispatchers.Main) {
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                    )
                }
            }
            return "need_permission"
        }
        val file = withContext(Dispatchers.IO) {
            val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
            val f = File(dir, "frontal-daily.apk")
            open(BASE + "frontal-daily.apk").inputStream.use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
            f
        }
        withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        }
        return "started"
    }
}
