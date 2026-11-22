package cockpit.ui

import android.app.Activity
import android.os.Bundle
import android.webkit.*
import androidx.webkit.WebViewAssetLoader
import cockpit.*
import com.qwenaudio.cockpit.LocalCapabilities
import com.qwenaudio.cockpit.RuntimeVoiceBridge
import android.Manifest
import android.content.pm.PackageManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

abstract class RuntimeCockpitActivity : Activity() {
    protected abstract fun adapter(): RuntimeAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var web: WebView
    private lateinit var local: LocalCapabilities
    private lateinit var controller: CockpitController
    private lateinit var voice: RuntimeVoiceBridge
    private var permissionRequest: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val directory = filesDir
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(directory, "last-crash.txt").writeText(error.stackTraceToString()) }
            previous?.uncaughtException(thread, error)
        }
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.mediaPlaybackRequiresUserGesture = true
        WebView.setWebContentsDebuggingEnabled(true)
        voice = RuntimeVoiceBridge(this, web) { id -> runOnUiThread {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.startMicrophone(id)
            else { permissionRequest = id; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101) }
        } }
        web.addJavascriptInterface(voice, "AndroidCockpit")
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), "External resources blocked".byteInputStream())
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        local = LocalCapabilities(this) { event -> runOnUiThread { controller.navigation(event) } }
        controller = CockpitController(this, scope, adapter(), local) { value -> event("cockpit-state", value) }
        web.addJavascriptInterface(object {
            @JavascriptInterface fun request(raw: String) {
                runOnUiThread {
                    try {
                        val message = JSONObject(raw)
                        val id = message.getString("id")
                        scope.launch {
                            try {
                                val params = message.optJSONObject("params") ?: JSONObject()
                                if (message.getString("method") in setOf("local", "voiceLocal") && params.optString("action") in setOf("media_play", "media_next", "media_previous")) voice.yieldFocusToLocalMedia()
                                val result = controller.request(message.getString("method"), params)
                                event("cockpit-reply", JSONObject().put("id", id).put("result", result))
                            } catch (e: Exception) {
                                event("cockpit-reply", JSONObject().put("id", id).put("error", e.message ?: "操作失败"))
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        }, "CockpitRuntime")
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        if (intent.getBooleanExtra("runValidation", false)) scope.launch(Dispatchers.Default) {
            val results = Verification.runWithRecovery(adapter(), File(filesDir, "verification"))
            File(filesDir, "last-result.json").writeText(Json.encodeToString(results))
            File(filesDir, "last-result.txt").writeText("${results.count { it.passed }}/${results.size}\n" + results.joinToString("\n"))
        }
    }
    private fun event(type: String, value: JSONObject) {
        web.evaluateJavascript("window.dispatchEvent(new CustomEvent(${JSONObject.quote(type)},{detail:$value}));", null)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 101) {
            val id = permissionRequest; permissionRequest = null
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) voice.startMicrophone(id)
            else { voice.fail(id, "麦克风权限未获允许"); voice.voiceOff("permission_denied") }
        }
    }
    override fun onResume() { super.onResume(); if (::voice.isInitialized) voice.foreground = true }
    override fun onPause() { if (::voice.isInitialized) { voice.foreground = false; voice.voiceOff("background") }; super.onPause() }
    override fun onDestroy() {
        voice.destroy(); scope.cancel(); local.close(); web.removeJavascriptInterface("AndroidCockpit"); web.removeJavascriptInterface("CockpitRuntime"); web.destroy()
        super.onDestroy()
    }
}

private class CockpitController(
    private val activity: Activity, private val scope: CoroutineScope, private val runtime: RuntimeAdapter,
    private val local: LocalCapabilities, private val publish: (JSONObject) -> Unit,
) {
    private val file = File(activity.filesDir, "cockpit-tasks.json")
    private val preferences = activity.getSharedPreferences("cockpit", 0)
    private val tasks = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
    private var media = JSONObject().put("connected", false)
    private val nav = JSONObject()
    private var active: Job? = null
    private var currentTask: JSONObject? = null
    private val navigationTasks = mutableMapOf<String, JSONObject>()
    init {
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            if (task.optString("status") in setOf("running", "accepted")) task.put("status", "unknown").put("summary", "应用已重启，请核对实际执行状态")
        }
    }
    private fun state() = JSONObject().put("framework", runtime.name).put("tasks", tasks).put("media", media).put("navigation", nav)
        .put("commuteEnabled", preferences.getBoolean("commuteEnabled", true)).put("home", preferences.getString("home", ""))
    private fun changed() {
        file.writeText(tasks.toString())
        publish(state())
    }
    suspend fun request(method: String, args: JSONObject): JSONObject = when(method) {
        "state" -> state()
        "ready" -> { refreshMedia(); state() }
        "mediaRefresh" -> { media = localCall("media_status"); publish(state()); state() }
        "settings" -> {
            preferences.edit().putString("home", args.optString("home").trim().take(100))
                .putBoolean("commuteEnabled", args.optBoolean("commuteEnabled", true))
                .putInt("generation", preferences.getInt("generation", 1) + 1).apply()
            changed(); state()
        }
        "submit" -> {
            val text = args.optString("text").trim().take(200)
            val calls = route(text)
            submit(text, calls); state()
        }
        "local", "voiceLocal" -> {
            val action = args.optString("action")
            require(action in ACTIONS) { "未开放该本地能力" }
            val arguments = args.optJSONObject("arguments") ?: JSONObject()
            submit(LABELS[action] ?: "本地操作", listOf(Call(UUID.randomUUID().toString(), action, arguments.toString())))
            if (method == "voiceLocal") {
                val task = currentTask
                active?.join()
                if (action.startsWith("media_")) { delay(700); media = localCall("media_status"); publish(state()) }
                JSONObject().put("task", task).put("media", media).put("navigation", nav)
            } else state()
        }
        "cancel" -> {
            currentTask?.put("status", "unknown")?.put("summary", "已停止后续步骤；已发送的操作需核对实际状态")
            active?.cancel(); active = null; changed(); state()
        }
        else -> error("不支持的交互请求")
    }
    private fun route(text: String): List<Call> {
        fun call(action: String, args: JSONObject = JSONObject()) = Call(UUID.randomUUID().toString(), action, args.toString())
        return when {
            text in setOf("播放音乐", "继续播放", "播放") -> listOf(call("media_play"))
            text in setOf("暂停音乐", "暂停", "停止音乐") -> listOf(call("media_pause"))
            text in setOf("下一首", "切歌") -> listOf(call("media_next"))
            text == "上一首" -> listOf(call("media_previous"))
            text in setOf("查询媒体状态", "媒体状态") -> listOf(call("media_status"))
            text in setOf("打开地图", "打开高德", "打开车机地图") -> listOf(call("map"))
            text in setOf("开启下班模式", "下班模式") -> {
                check(preferences.getBoolean("commuteEnabled", true)) { "下班模式已停用" }
                val home = preferences.getString("home", "").orEmpty()
                check(home.isNotBlank()) { "请先在我的能力中设置回家目的地" }
                listOf(call("navigationCandidates", JSONObject().put("destination", home)), call("media_play"))
            }
            text.matches(Regex("^(导航去|导航到|带我去|搜索).+")) -> listOf(call("navigationCandidates", JSONObject().put("destination", text.replace(Regex("^(导航去|导航到|带我去|搜索)"), "").trim())))
            else -> error("当前支持播放、暂停、切歌、搜索目的地和下班模式；自由问答尚未连接")
        }
    }
    private fun submit(title: String, calls: List<Call>) {
        check(active?.isActive != true) { "当前指令正在执行，请稍候或取消" }
        if (calls.any { it.action == "navigationCandidates" }) check(nav.optJSONObject("search")?.optString("type") != "searching") { "正在等待高德搜索结果" }
        val task = JSONObject().put("id", UUID.randomUUID().toString()).put("title", title).put("time", System.currentTimeMillis())
            .put("status", "running").put("summary", "正在执行本地指令").put("steps", JSONArray())
        tasks.put(task)
        while (tasks.length() > 60) tasks.remove(0)
        currentTask = task
        calls.forEach { call -> when(call.action) {
            "navigationCandidates" -> navigationTasks["results"] = task
            "navigationPlan" -> navigationTasks["routes"] = task
            "navigationStart" -> navigationTasks["status"] = task
        } }
        val configurationGeneration = preferences.getInt("generation", 1)
        changed()
        active = scope.launch {
            try {
                val root = File(activity.filesDir, "commands/${task.getString("id")}")
                val device = object : Device(File(root, "host")) {
                    override suspend fun invoke(call: Call): Receipt {
                        currentCoroutineContext().ensureActive()
                        if (title.contains("下班模式")) check(preferences.getBoolean("commuteEnabled", true) && preferences.getInt("generation", 1) == configurationGeneration) { "下班模式配置已变化，后续步骤已阻止" }
                        val reply = execute(call)
                        val receipt = Receipt(call.invocationId, call.action, reply.first, reply.second)
                        receipts += receipt
                        withContext(Dispatchers.Main) {
                            task.getJSONArray("steps").put(JSONObject().put("action", LABELS[call.action]).put("status", receipt.status).put("summary", receipt.evidence))
                            changed()
                        }
                        return receipt
                    }
                }
                withContext(Dispatchers.Default) { runtime.run(calls, device, File(root, "runtime")) }
                settle(task)
            } catch (_: CancellationException) { }
            catch (e: Exception) { task.put("status", "failed").put("summary", e.message ?: "执行失败") }
            finally { if (currentTask === task) currentTask = null; changed() }
        }
    }
    private suspend fun execute(call: Call): Pair<String, String> {
        return try {
            var result = localCall(call.action, JSONObject(call.argument.ifBlank { "{}" }))
            if (call.action in setOf("media_play", "media_pause")) {
                val shouldPlay = call.action == "media_play"
                for (attempt in 0 until 6) {
                    if (result.optBoolean("playing") == shouldPlay) break
                    delay(250)
                    result = localCall("media_status")
                }
            }
            if (call.action.startsWith("media_")) {
                withContext(Dispatchers.Main) { media = result; changed() }
                when {
                    call.action == "media_status" -> "succeeded" to (if (result.optBoolean("playing")) "车机媒体正在播放" else "车机媒体未播放")
                    call.action == "media_play" && result.optBoolean("playing") -> "succeeded" to "车机媒体已播放"
                    call.action == "media_pause" && !result.optBoolean("playing") -> "succeeded" to "车机媒体已暂停"
                    else -> "accepted" to "车机已接收媒体指令，等待状态确认"
                }
            } else "accepted" to when(call.action) {
                "map" -> "已向高德发送打开请求"
                "navigationCandidates" -> "已请求高德搜索目的地，等待结果"
                "navigationPlan" -> "已请求高德规划路线，等待结果"
                "navigationStart" -> "已发送导航请求，等待高德确认"
                else -> "已请求打开空调面板，未修改设定"
            }
        } catch (e: Exception) { if (e is CancellationException) throw e; "failed" to (e.message ?: "车机调用失败") }
    }
    private suspend fun localCall(action: String, arguments: JSONObject = JSONObject()): JSONObject = suspendCancellableCoroutine { continuation ->
        local.call(action, arguments) { value, error ->
            if (continuation.isActive) {
                if (error != null) continuation.resumeWith(Result.failure(IllegalStateException(error)))
                else continuation.resumeWith(Result.success(value ?: JSONObject()))
            }
        }
    }
    private fun refreshMedia() { scope.launch { runCatching { media = localCall("media_status") }; changed() } }
    private fun settle(task: JSONObject) {
        val steps = task.getJSONArray("steps")
        val resolved = task.optJSONObject("resolved")
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            resolved?.optJSONObject(step.optString("action"))?.let { result ->
                step.put("status", result.getString("status")).put("summary", result.getString("summary"))
            }
        }
        val states = (0 until steps.length()).map { steps.getJSONObject(it).optString("status") }
        if (states.isNotEmpty()) {
            task.put("status", when { states.all { it == "succeeded" } -> "succeeded"; states.all { it == "failed" } -> "failed"; "failed" in states -> "partial"; else -> "accepted" })
            task.put("summary", (0 until steps.length()).joinToString("；") { steps.getJSONObject(it).optString("summary") })
        }
    }
    fun navigation(event: JSONObject) {
        when(event.optString("type")) {
            "searching", "results" -> nav.put("search", event)
            "routes" -> nav.put("routes", event)
            "status" -> nav.put("status", event)
            "guidance" -> nav.put("guidance", event)
        }
        val finalEvent = event.optString("type") == "results" || (event.optString("type") == "routes" && !event.optBoolean("pending")) || (event.optString("type") == "status" && event.optInt("state") == 8)
        if (finalEvent) navigationTasks.remove(event.optString("type"))?.let { task ->
            val summary = when(event.optString("type")) {
                "results" -> "高德返回 ${event.optJSONArray("candidates")?.length() ?: 0} 个候选地点，请选择后规划"
                "routes" -> event.optString("message").ifBlank { "高德已返回路线方案" }
                else -> "高德确认导航已启动"
            }
            val failed = event.optString("type") == "results" && (event.optJSONArray("candidates")?.length() ?: 0) == 0 || event.optString("type") == "routes" && !event.has("paths")
            val action = when(event.optString("type")) { "results" -> "搜索目的地"; "routes" -> "规划路线"; else -> "开始导航" }
            val resolved = task.optJSONObject("resolved") ?: JSONObject().also { task.put("resolved", it) }
            resolved.put(action, JSONObject().put("status", if (failed) "failed" else "succeeded").put("summary", summary))
            settle(task)
        }
        changed()
    }
    companion object {
        private val LABELS = mapOf("media_status" to "媒体状态", "media_play" to "播放音乐", "media_pause" to "暂停音乐", "media_next" to "下一首", "media_previous" to "上一首", "map" to "打开高德", "navigationCandidates" to "搜索目的地", "navigationPlan" to "规划路线", "navigationStart" to "开始导航", "aircon" to "空调面板")
        private val ACTIONS = LABELS.keys
    }
}
