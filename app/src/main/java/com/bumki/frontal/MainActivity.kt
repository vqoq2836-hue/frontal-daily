package com.bumki.frontal

import android.Manifest
import android.content.Intent
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
            Reminders.scheduleAll(this@MainActivity)
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
