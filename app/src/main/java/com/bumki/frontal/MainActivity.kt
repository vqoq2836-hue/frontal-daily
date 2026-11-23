package com.bumki.frontal

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.util.Locale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var health: HealthReader

    private val healthPermissionLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            val ok = granted.containsAll(HealthReader.PERMISSIONS)
            js("window.onNativeHealthPermission && window.onNativeHealthPermission($ok)")
        }

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            js("window.onNativeSmsPerm && window.onNativeSmsPerm($ok)")
        }

    private val calendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            val ok = r.values.isNotEmpty() && r.values.all { it }
            js("window.onNativeCalPerm && window.onNativeCalPerm($ok)")
        }

    /** 구글 음성 입력 창을 띄우고, 받아 적은 문장을 화면으로 넘김 */
    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            val cb = voiceCb; voiceCb = "onNativeVoice"
            js("window.$cb && window.$cb(" + JSONObject.quote(text) + ")")
        }

    /** 음성 인식 결과를 돌려줄 JS 함수 이름 (일정 넣기 = onNativeVoice, 밤 영어 = onNativeEngVoice) */
    private var voiceCb = "onNativeVoice"

    /** 밤 영어: 휴대폰 TTS로 영어 문장 읽기.
     *  기본 음성 엔진에 영어가 없으면 구글 음성 엔진으로 한 번 더 시도하고, 그래도 안 되면 이유를 화면에 알림 */
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsStarting = false
    private var ttsTriedGoogle = false
    private var ttsPending: Pair<String, Float>? = null

    private fun ttsReport(code: String) = js("window.onNativeTts && window.onNativeTts('$code')")

    private fun ttsInit(engine: String?) {
        ttsStarting = true
        val listener = TextToSpeech.OnInitListener { st ->
            ttsStarting = false
            val t = tts
            if (st == TextToSpeech.SUCCESS && t != null) {
                var r = t.setLanguage(Locale.US)
                if (r < TextToSpeech.LANG_AVAILABLE) r = t.setLanguage(Locale.ENGLISH)
                if (r >= TextToSpeech.LANG_AVAILABLE) {
                    ttsReady = true
                    t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {}
                        @Deprecated("old api") override fun onError(id: String?) { runOnUiThread { ttsReport("error") } }
                    })
                    ttsPending?.let { speakNow(it.first, it.second) }
                    ttsPending = null
                    return@OnInitListener
                }
            }
            // 실패: 구글 음성 엔진이 있으면 그걸로 다시
            runCatching { tts?.shutdown() }; tts = null; ttsReady = false
            val google = "com.google.android.tts"
            val hasGoogle = runCatching { packageManager.getPackageInfo(google, 0); true }.getOrDefault(false)
            if (!ttsTriedGoogle && hasGoogle && engine != google) { ttsTriedGoogle = true; ttsInit(google) }
            else { ttsPending = null; ttsReport(if (st == TextToSpeech.SUCCESS) "nolang" else "noengine") }
        }
        tts = if (engine == null) TextToSpeech(this, listener) else TextToSpeech(this, listener, engine)
    }

    private fun speakNow(text: String, rate: Float) {
        val t = tts ?: return
        val am = getSystemService(android.media.AudioManager::class.java)
        if (am != null && am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) ttsReport("mute")
        t.setSpeechRate(rate)
        val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "eng")
        if (r != TextToSpeech.SUCCESS) ttsReport("error") else ttsReport("ok")
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
    override fun onDestroy() {
        runCatching { tts?.shutdown() }
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun speak(text: String, rate: Double): Boolean {
            runOnUiThread {
                when {
                    ttsReady && tts != null -> speakNow(text, rate.toFloat())
                    ttsStarting -> ttsPending = text to rate.toFloat()
                    else -> { ttsPending = text to rate.toFloat(); ttsTriedGoogle = false; ttsInit(null) }
                }
            }
            return true
        }

        /** 글자 읽어주기(TTS) 설정 화면 */
        @JavascriptInterface
        fun openTtsSettings() {
            runOnUiThread {
                runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
            }
        }

        /** 영어 음성 데이터 받기 화면 */
        @JavascriptInterface
        fun installTtsData() {
            runOnUiThread {
                runCatching { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                    .onFailure { runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } }
            }
        }

        /** 새 문장(조언·영어) 묶음을 GitHub 릴리스에서 받아 window.onNativeFeed 로 넘김 */
        @JavascriptInterface
        fun fetchFeed() {
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        val c = java.net.URL(FEED_URL).openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 10_000; c.readTimeout = 20_000; c.instanceFollowRedirects = true
                        c.setRequestProperty("User-Agent", "FrontalDaily")
                        c.inputStream.bufferedReader().use { it.readText() }
                    }.getOrDefault("")
                }
                if (text.isNotEmpty()) js("window.onNativeFeed && window.onNativeFeed(" + JSONObject.quote(text) + ")")
            }
        }

        @JavascriptInterface
        fun startVoiceLang(prompt: String, lang: String, cb: String): Boolean {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            if (i.resolveActivity(packageManager) == null) return false
            if (!cb.matches(Regex("[A-Za-z]+"))) return false
            runOnUiThread {
                voiceCb = cb
                runCatching { voiceLauncher.launch(i) }
                    .onFailure { voiceCb = "onNativeVoice"; js("window.$cb && window.$cb(\"\")") }
            }
            return true
        }

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
        fun lockWallpaperApply(): String = LockWallpaper.apply(this@MainActivity, true)

        @JavascriptInterface
        fun lockWallpaperPreview(): String = runCatching { LockWallpaper.preview(this@MainActivity) }.getOrDefault("")

        @JavascriptInterface
        fun startVoice(prompt: String): Boolean {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            if (i.resolveActivity(packageManager) == null) return false
            runOnUiThread {
                runCatching { voiceLauncher.launch(i) }
                    .onFailure { js("window.onNativeVoice && window.onNativeVoice(\"\")") }
            }
            return true
        }

        @JavascriptInterface
        fun smsStatus(): String = if (SmsReader.granted(this@MainActivity)) "granted" else "denied"

        @JavascriptInterface
        fun requestSmsPermission() {
            runOnUiThread {
                runCatching { smsPermissionLauncher.launch(SmsReader.PERMISSION) }
                    .onFailure { js("window.onNativeSmsPerm && window.onNativeSmsPerm(false)") }
            }
        }

        /** 문자함 읽기는 오래 걸릴 수 있어 뒤에서 하고, 끝나면 window.onNativeSms 로 넘김 */
        @JavascriptInterface
        fun readSms(since: String) {
            val s = since.toLongOrNull() ?: 0L
            lifecycleScope.launch {
                val json = withContext(Dispatchers.IO) { runCatching { SmsReader.read(this@MainActivity, s) }.getOrDefault("[]") }
                js("window.onNativeSms && window.onNativeSms(" + JSONObject.quote(json) + ")")
            }
        }

        @JavascriptInterface
        fun notiStatus(): String = if (FinNotifListener.enabled(this@MainActivity)) "granted" else "denied"

        /** 카드사 앱 알림 읽기: 설정의 '알림 읽기(기기 및 앱 알림)' 화면을 엶 */
        @JavascriptInterface
        fun openNotiAccess() {
            runOnUiThread {
                val tries = listOf(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, FinNotifListener.component(this@MainActivity).flattenToString()),
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                )
                for (i in tries) if (runCatching { startActivity(i) }.isSuccess) break
            }
        }

        @JavascriptInterface
        fun takeNotis(): String = FinNotifListener.take(this@MainActivity)

        @JavascriptInterface
        fun openAppInfo() {
            runOnUiThread {
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                }
            }
        }

        @JavascriptInterface
        fun calendarStatus(): String = if (CalendarWriter.granted(this@MainActivity)) "granted" else "denied"

        @JavascriptInterface
        fun requestCalendarPermission() {
            runOnUiThread {
                runCatching { calendarPermissionLauncher.launch(CalendarWriter.PERMISSIONS) }
                    .onFailure { js("window.onNativeCalPerm && window.onNativeCalPerm(false)") }
            }
        }

        @JavascriptInterface
        fun listCalendars(): String = CalendarWriter.list(this@MainActivity)

        @JavascriptInterface
        fun addEvent(json: String): String = CalendarWriter.add(this@MainActivity, json)

        @JavascriptInterface
        fun deleteEvent(id: String): Boolean = id.toLongOrNull()?.let { CalendarWriter.delete(this@MainActivity, it) } ?: false

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
        const val FEED_URL = "https://raw.githubusercontent.com/vqoq2836-hue/frontal-daily/content/feed.json"
        const val ASSET_HOST = "appassets.androidplatform.net"
    }
}
