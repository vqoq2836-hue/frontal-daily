package com.bumki.frontal

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var health: HealthReader

    private val healthPermissionLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            val ok = granted.containsAll(HealthReader.PERMISSIONS)
            js("window.onNativeHealthPermission && window.onNativeHealthPermission($ok)")
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        health = HealthReader(this)
        Reminders.ensureChannel(this)

        web = WebView(this)
        setContentView(web)

        // assets/index.html 을 https 주소로 열어야 localStorage 가 안정적으로 유지됩니다.
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == ASSET_HOST) return false
                // 근거 링크 등 바깥 주소는 브라우저로 엽니다.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "NativeApp")
        web.loadUrl("https://$ASSET_HOST/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Reminders.scheduleAll(this)
    }

    override fun onResume() {
        super.onResume()
        runCatching { Reminders.updateBoard(this) }
        if (::web.isInitialized) js("window.onNativeResume && window.onNativeResume()")
    }

    private fun js(code: String) {
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(code, null) }
    }

    /** 웹 화면(index.html)에서 window.NativeApp 으로 부르는 기능들 */
    inner class Bridge {
        @JavascriptInterface
        fun platform(): String = "android"

        @JavascriptInterface
        fun healthStatus(): String = health.status()

        @JavascriptInterface
        fun requestHealthPermission() {
            runOnUiThread {
                runCatching { healthPermissionLauncher.launch(HealthReader.PERMISSIONS) }
                    .onFailure { js("window.onNativeHealthPermission && window.onNativeHealthPermission(false)") }
            }
        }

        @JavascriptInterface
        fun readHealth(date: String) {
            lifecycleScope.launch {
                val json = try {
                    health.readDay(date)
                } catch (e: Exception) {
                    JSONObject().put("date", date).put("error", e.message ?: "error").toString()
                }
                js("window.onNativeHealth && window.onNativeHealth(" + JSONObject.quote(json) + ")")
            }
        }

        @JavascriptInterface
        fun diagnoseHealth() {
            lifecycleScope.launch {
                val json = try {
                    health.diagnose()
                } catch (e: Exception) {
                    JSONObject().put("error", e.javaClass.simpleName + ": " + (e.message ?: "")).toString()
                }
                js("window.onNativeDiag && window.onNativeDiag(" + JSONObject.quote(json) + ")")
            }
        }

        /** 헬스 커넥트 설정 화면 열기 (안드로이드 14 이상은 시스템 설정 안에 있음) */
        @JavascriptInterface
        fun openHealthConnect() {
            runOnUiThread {
                val actions = listOf(
                    "android.health.connect.action.HEALTH_HOME_SETTINGS",
                    HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS
                )
                for (a in actions) {
                    val ok = runCatching { startActivity(Intent(a)) }.isSuccess
                    if (ok) return@runOnUiThread
                }
                js("window.onNativeDiag && window.onNativeDiag(" + JSONObject.quote("{\"error\":\"open_failed\"}") + ")")
            }
        }

        @JavascriptInterface
        fun syncState(json: String) {
            Reminders.saveState(this@MainActivity, json)
            // 알람 예약에서 문제가 나도 잠금화면 할 일과 위젯은 꼭 갱신되도록 따로 처리
            runCatching { Reminders.scheduleAll(this@MainActivity) }
            runCatching { Reminders.updateBoard(this@MainActivity) }
            runCatching { MissionWidget.updateAll(this@MainActivity) }
        }

        @JavascriptInterface
        fun boardStatus(): String = Reminders.boardStatus(this@MainActivity)

        @JavascriptInterface
        fun showBoard(): String {
            runCatching { Reminders.ensureChannel(this@MainActivity); Reminders.updateBoard(this@MainActivity) }
            return Reminders.boardStatus(this@MainActivity)
        }

        /** which: app(앱 알림) · channel(잠금화면 할 일 채널) · lock(휴대폰 잠금화면 설정) */
        @JavascriptInterface
        fun openNotifySettings(which: String) {
            val pkg = packageName
            val tries = when (which) {
                "channel" -> listOf(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg).putExtra(Settings.EXTRA_CHANNEL_ID, Reminders.BOARD_CHANNEL),
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
                "lock" -> listOf(
                    Intent("android.settings.LOCK_SCREEN_SETTINGS"),
                    Intent().setClassName("com.android.settings", "com.android.settings.Settings\$LockscreenSettingsActivity"),
                    Intent(Settings.ACTION_SETTINGS))
                else -> listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
            }
            runOnUiThread {
                for (i in tries) {
                    if (runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) break
                }
            }
        }

        /** 삼성헬스 앱을 열어 헬스 커넥트로 동기화를 재촉합니다. 돌아오면 onResume에서 다시 읽습니다. */
        @JavascriptInterface
        fun openSamsungHealth(): Boolean {
            val i = packageManager.getLaunchIntentForPackage("com.sec.android.app.shealth") ?: return false
            runOnUiThread { runCatching { startActivity(i) } }
            return true
        }

        @JavascriptInterface
        fun checkUpdate() {
            lifecycleScope.launch {
                val json = Updater.check(this@MainActivity)
                js("window.onNativeUpdate && window.onNativeUpdate(" + JSONObject.quote(json) + ")")
            }
        }

        @JavascriptInterface
        fun startUpdate() {
            lifecycleScope.launch {
                val r = try {
                    Updater.downloadAndInstall(this@MainActivity)
                } catch (e: Exception) {
                    "error:" + e.javaClass.simpleName
                }
                val out = JSONObject()
                when {
                    r == "started" -> out.put("status", "installing")
                    r == "need_permission" -> out.put("status", "need_permission")
                    else -> out.put("status", "error").put("message", r.removePrefix("error:"))
                }
                js("window.onNativeUpdate && window.onNativeUpdate(" + JSONObject.quote(out.toString()) + ")")
            }
        }

        @JavascriptInterface
        fun takePendingChecks(): String = Reminders.takePending(this@MainActivity)

        @JavascriptInterface
        fun testNotification() {
            Reminders.showTest(this@MainActivity)
        }
    }

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
    }
}
