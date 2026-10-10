package dev.mokouliszt.githubpatches

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import dev.mokouliszt.githubpatches.patch.PatchFormatException
import dev.mokouliszt.githubpatches.patch.PatchParser
import dev.mokouliszt.githubpatches.patch.PushException
import dev.mokouliszt.githubpatches.patch.PushTarget
import dev.mokouliszt.githubpatches.patch.Pusher
import dev.mokouliszt.githubpatches.patch.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var store: Store
    private lateinit var github: GitHub

    companion object {
        /** Work outlives the Activity (backgrounding, low-memory recreation). */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val jobs = ConcurrentHashMap<String, Job>()
        @Volatile private var sink: ((String, JSONObject) -> Unit)? = null
        /** The page has asked for its state at least once, so events reach a mounted UI. */
        @Volatile private var pageReady = false
        /** A patch imported before the page was ready; opened by the next state() call. */
        @Volatile private var pendingOpen: String? = null
        private const val REPOS_FRESH_MS = 10 * 60 * 1000L

        fun cancelAll() {
            val ids = jobs.keys.toList()
            ids.forEach { jobs.remove(it)?.cancel() }
        }
    }

    private var pickReq: String? = null
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val req = pickReq ?: "pick"
        pickReq = null
        if (uri == null) emit("done", req(req)) else importUri(req, uri, "file")
    }

    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBars()

        store = Store(this)
        github = GitHub(this)
        I18n.lang = store.lang()
        pageReady = false

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(if (dark) 0xFF0A0A0A.toInt() else 0xFFFAFAFA.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.textZoom = 100
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
            }
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(Bridge(), "Native")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(req.url)

                override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                    val u = req.url
                    if (u.host == "appassets.androidplatform.net") return false
                    openExternal(u)
                    return true
                }

                override fun onPageFinished(v: WebView, url: String) {
                    pushInsets(); pushTheme()
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/webui/index.html")
        }
        setContentView(web)
        ViewCompat.setOnApplyWindowInsetsListener(web) { _, insets -> pushInsets(insets); insets }
        sink = { type, payload -> deliver(type, payload) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__back?String(window.__back()):'false'") { r ->
                    if (r?.trim('"') != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
        web.setBackgroundColor(if (dark) 0xFF0A0A0A.toInt() else 0xFFFAFAFA.toInt())
        pushTheme()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) sink = null
        super.onDestroy()
    }

    private fun applySystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }

    // ---------------------------------------------------------------- to JS

    private fun pushInsets(insets: WindowInsetsCompat? = null) {
        val i = insets ?: ViewCompat.getRootWindowInsets(web) ?: return
        val bars = i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = i.getInsets(WindowInsetsCompat.Type.ime())
        val d = resources.displayMetrics.density
        emit("insets", JSONObject()
            .put("top", bars.top / d)
            .put("bottom", bars.bottom / d)
            .put("ime", (if (ime.bottom > 0) ime.bottom - bars.bottom else 0) / d))
    }

    private fun pushTheme() = emit("theme", JSONObject().put("dark", dark))

    private fun emit(type: String, payload: JSONObject) {
        (sink ?: return).invoke(type, payload)
    }

    private fun deliver(type: String, payload: JSONObject) {
        if (isDestroyed || isFinishing) return
        val json = payload.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        runOnUiThread {
            runCatching { web.evaluateJavascript("window.__native&&window.__native(\"$type\",$json)", null) }
        }
    }

    private fun req(id: String) = JSONObject().put("reqId", id)

    /** Error text the user can act on; [kind] lets the UI react (sign-in, conflicts, ...). */
    private fun fail(reqId: String, t: Throwable) {
        if (t is CancellationException) return
        val (kind, message) = when (t) {
            is GitHubException -> (if (t.needLogin) "login" else "github") to (t.message ?: "")
            is PatchFormatException -> "format" to I18n.t("パッチとして読み込めませんでした", "This is not a patch")
            is PushException -> when (t.kind) {
                PushException.Kind.BRANCH_MISSING -> "branchMissing" to I18n.t("ブランチ ${t.detail} が見つかりません", "Branch ${t.detail} not found")
                PushException.Kind.BRANCH_EXISTS -> "branchExists" to I18n.t("ブランチ ${t.detail} はすでにあります", "Branch ${t.detail} already exists")
                PushException.Kind.DOES_NOT_APPLY -> "conflict" to I18n.t("適用できないファイルがあります", "Some files do not apply")
                PushException.Kind.INTEGRITY -> "integrity" to I18n.t("アップロードした内容が一致しませんでした", "Uploaded content did not match")
            }
            is RemoteException -> t.kind.name.lowercase() to remoteMessage(t)
            else -> "error" to (t.message ?: t.javaClass.simpleName)
        }
        val payload = req(reqId).put("message", message).put("kind", kind)
        (t as? PushException)?.report?.let { payload.put("report", PatchJson.check(it)) }
        emit("error", payload)
    }

    private fun remoteMessage(e: RemoteException): String = when (e.kind) {
        RemoteException.Kind.NOT_FOUND -> I18n.t("リポジトリまたはブランチが見つかりません", "Repository or branch not found")
        RemoteException.Kind.NOT_FAST_FORWARD -> I18n.t("ブランチが更新されました。もう一度pushしてください", "The branch moved. Push again.")
        RemoteException.Kind.ALREADY_EXISTS -> I18n.t("同じ名前のブランチがあります", "A branch with that name exists")
        RemoteException.Kind.FORBIDDEN -> I18n.t("拒否されました: ", "Rejected: ") + e.message
        RemoteException.Kind.WORKFLOW_SCOPE -> I18n.t("ワークフローの変更には workflow 権限が必要です。ログインし直してください",
            "Changing workflows needs the workflow scope. Sign in again.")
        RemoteException.Kind.EMPTY_REPO -> I18n.t("空のリポジトリにはpushできません", "Cannot push to an empty repository")
        RemoteException.Kind.AUTH -> I18n.t("GitHubにログインし直してください", "Sign in to GitHub again")
        RemoteException.Kind.RATE_LIMIT -> I18n.t("GitHub APIの利用上限に達しました", "GitHub API rate limit reached")
        RemoteException.Kind.NETWORK -> e.message ?: I18n.t("GitHubに接続できませんでした", "Could not reach GitHub")
        RemoteException.Kind.OTHER -> "GitHub: ${e.message}"
    }

    private fun launch(reqId: String, label: String? = null, block: suspend () -> Unit) {
        val app = applicationContext
        jobs.remove(reqId)?.cancel()
        if (label != null) RequestService.begin(app, reqId, label)
        jobs[reqId] = appScope.launch {
            try {
                block()
            } catch (t: Throwable) {
                fail(reqId, t)
            } finally {
                jobs.remove(reqId)
                if (label != null) RequestService.end(app, reqId)
                emit("done", req(reqId))
            }
        }
    }

    private fun openExternal(u: Uri) {
        runCatching {
            if (u.scheme == "https" || u.scheme == "http") {
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, u)
            } else startActivity(Intent(Intent.ACTION_VIEW, u))
        }
    }

    private fun bringToFront() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    // ---------------------------------------------------------------- importing

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val reqId = "share-${System.currentTimeMillis().toString(36)}"
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> importUri(reqId, stream, "share")
                    !text.isNullOrBlank() -> importText(reqId, text, intent.getStringExtra(Intent.EXTRA_SUBJECT), "share")
                }
            }
            Intent.ACTION_VIEW -> intent.data?.let { importUri(reqId, it, "share") }
        }
    }

    private fun isUrl(s: String) = s.trim().let { (it.startsWith("https://") || it.startsWith("http://")) && !it.contains('\n') && !it.contains(' ') }

    private fun importText(reqId: String, text: String, subject: String?, source: String) {
        if (isUrl(text)) { importFromUrl(reqId, text.trim()); return }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        val name = subject?.takeIf { it.isNotBlank() }?.let { safeName(it) } ?: "$source-$stamp.patch"
        launch(reqId) { importBytes(reqId, text.toByteArray(Charsets.UTF_8), name, source, null, null) }
    }

    private fun importUri(reqId: String, uri: Uri, source: String) = launch(reqId) {
        val (bytes, name) = withContext(Dispatchers.IO) {
            val name = runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "patch.patch"
            val input = contentResolver.openInputStream(uri) ?: throw GitHubException(I18n.t("ファイルを開けませんでした", "Could not open the file"))
            val bytes = input.use { s ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(1 shl 16)
                while (true) {
                    val n = s.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > GitHub.MAX_PATCH_BYTES) throw GitHubException(I18n.t("ファイルが大きすぎます", "The file is too large"))
                }
                buf.toByteArray()
            }
            bytes to name
        }
        importBytes(reqId, bytes, name, source, null, null)
    }

    private fun importFromUrl(reqId: String, url: String) = launch(reqId) {
        val f = github.fetchPatch(url)
        importBytes(reqId, f.bytes, safeName(f.name), "url", url, f.repo)
    }

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").take(120)

    private suspend fun importBytes(reqId: String, bytes: ByteArray, name: String, source: String, url: String?, repo: String?) {
        val (entry, isNew) = withContext(Dispatchers.Default) {
            val patch = PatchParser.parse(bytes)
            val existing = store.findBySha(store.sha1(bytes))
            if (existing != null) existing to false
            else {
                val summary = PatchJson.summary(patch, name).put("source", source).put("url", url ?: "").put("repo", repo ?: "")
                store.add(bytes, summary) to true
            }
        }
        val id = entry.optString("id")
        if (!pageReady) pendingOpen = id
        emit("imported", req(reqId).put("entry", entry).put("isNew", isNew).put("patches", store.index()))
    }

    // ---------------------------------------------------------------- state

    private fun clientId(): String = store.settings().optString("githubClientId").trim()

    private fun stateJson(): JSONObject = JSONObject()
        .put("github", JSONObject().put("loggedIn", github.isLoggedIn).put("user", github.user()))
        .put("clientIdSet", clientId().isNotBlank())
        .put("settings", store.settings())
        .put("patches", store.index())
        .put("recent", store.recent())
        .put("repos", store.cachedRepos())
        .put("open", pendingOpen ?: "")
        .put("running", JSONArray(jobs.keys.toList()))
        .put("version", BuildConfig.VERSION_NAME)

    private fun target(json: String): Pair<String, PushTarget> {
        val o = JSONObject(json)
        return o.getString("repo") to PushTarget(
            branch = o.getString("branch"),
            create = o.optBoolean("create"),
            base = o.optString("base").ifBlank { null },
        )
    }

    private fun webUrl(path: String) = "https://github.com/$path"

    // ---------------------------------------------------------------- bridge

    inner class Bridge {

        @JavascriptInterface
        fun state(): String {
            val s = stateJson().toString()
            pageReady = true
            pendingOpen = null
            return s
        }

        @JavascriptInterface
        fun saveSettings(json: String): String {
            val s = store.saveSettings(runCatching { JSONObject(json) }.getOrElse { JSONObject() })
            I18n.lang = s.optString("lang")
            return s.toString()
        }

        @JavascriptInterface
        fun isDark(): Boolean = dark

        // ---- GitHub
        @JavascriptInterface
        fun githubLogin(reqId: String) = launch(reqId, I18n.t("GitHubの認可を待っています", "Waiting for GitHub authorization")) {
            val id = clientId()
            val dc = github.startDeviceFlow(id)
            emit("githubCode", req(reqId)
                .put("userCode", dc.userCode)
                .put("verificationUri", dc.verificationUri)
                .put("expiresIn", dc.expiresIn))
            val user = github.awaitDeviceToken(id, dc)
            bringToFront()
            emit("githubLoggedIn", req(reqId).put("user", user))
        }

        @JavascriptInterface
        fun githubLogout() {
            github.logout()
            store.clearAccountData()
        }

        // ---- patches
        @JavascriptInterface
        fun pickFile(reqId: String) = runOnUiThread {
            pickReq = reqId
            runCatching { openDocument.launch(arrayOf("*/*")) }.onFailure { fail(reqId, it); emit("done", req(reqId)) }
        }

        @JavascriptInterface
        fun importClipboard(reqId: String) = runOnUiThread {
            val cm = getSystemService(ClipboardManager::class.java)
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
            if (text.isNullOrBlank()) {
                emit("error", req(reqId).put("kind", "empty").put("message", I18n.t("クリップボードが空です", "The clipboard is empty")))
                emit("done", req(reqId))
            } else {
                importText(reqId, text, null, "clipboard")
            }
        }

        @JavascriptInterface
        fun importUrl(reqId: String, url: String) = importFromUrl(reqId, url)

        /** Parsed patch for display, or "" when it is gone. */
        @JavascriptInterface
        fun patch(id: String): String {
            val entry = store.entry(id) ?: return ""
            val raw = store.raw(id) ?: return ""
            return runCatching { PatchJson.detail(entry, PatchParser.parse(raw)).toString() }.getOrElse { "" }
        }

        @JavascriptInterface
        fun deletePatch(id: String) = store.delete(id)

        // ---- repositories
        @JavascriptInterface
        fun repos(reqId: String, force: Boolean) = launch(reqId) {
            val cached = store.cachedRepos()
            if (!force && System.currentTimeMillis() - cached.optLong("at") < REPOS_FRESH_MS && (cached.optJSONArray("items")?.length() ?: 0) > 0) {
                emit("repos", req(reqId).put("repos", cached)); return@launch
            }
            val items = github.repos()
            withContext(Dispatchers.IO) { store.saveRepos(items) }
            emit("repos", req(reqId).put("repos", store.cachedRepos()))
        }

        @JavascriptInterface
        fun branches(reqId: String, repo: String) = launch(reqId) {
            val info = runCatching { github.repo(repo) }.getOrNull()
            val items = github.branches(repo)
            emit("branches", req(reqId).put("repo", repo).put("items", items)
                .put("defaultBranch", info?.optString("defaultBranch") ?: ""))
        }

        // ---- check / push
        @JavascriptInterface
        fun check(reqId: String, id: String, targetJson: String) = launch(reqId) {
            val raw = store.raw(id) ?: throw GitHubException(I18n.t("パッチが見つかりません", "The patch is gone"))
            val (repo, t) = target(targetJson)
            val patch = withContext(Dispatchers.Default) { PatchParser.parse(raw) }
            // Hunk matching is CPU work; the network calls inside switch to IO themselves.
            val report = withContext(Dispatchers.Default) { Pusher(github.remote(repo)).check(patch, t) }
            emit("check", req(reqId).put("report", PatchJson.check(report)))
        }

        @JavascriptInterface
        fun push(reqId: String, id: String, targetJson: String, messagesJson: String, squash: Boolean) =
            launch(reqId, I18n.t("pushしています", "Pushing")) {
                val raw = store.raw(id) ?: throw GitHubException(I18n.t("パッチが見つかりません", "The patch is gone"))
                val (repo, t) = target(targetJson)
                val messages = runCatching {
                    val a = JSONArray(messagesJson); (0 until a.length()).map { a.optString(it) }
                }.getOrElse { emptyList() }
                val patch = withContext(Dispatchers.Default) { PatchParser.parse(raw) }
                val keepAuthor = store.settings().optBoolean("keepAuthor", true)
                val r = withContext(Dispatchers.Default) { Pusher(github.remote(repo)).push(patch, t, messages, squash, keepAuthor) }
                val url = when {
                    r.created -> webUrl("$repo/compare/${GitHub.branchPath(r.base ?: "")}...${GitHub.branchPath(r.branch)}?expand=1")
                    r.commits.size == 1 -> webUrl("$repo/commit/${r.head}")
                    else -> webUrl("$repo/compare/${r.parent}...${r.head}")
                }
                val push = JSONObject()
                    .put("repo", repo)
                    .put("branch", r.branch)
                    .put("head", r.head)
                    .put("commits", JSONArray(r.commits))
                    .put("created", r.created)
                    .put("base", r.base ?: "")
                    .put("url", url)
                    .put("commitUrl", webUrl("$repo/commit/${r.head}"))
                    .put("at", System.currentTimeMillis())
                val entry = withContext(Dispatchers.IO) {
                    store.addRecent(repo, r.branch)
                    store.addPush(id, push)
                }
                emit("pushed", req(reqId).put("push", push).put("entry", entry ?: JSONObject())
                    .put("patches", store.index()).put("recent", store.recent()))
            }

        @JavascriptInterface
        fun cancel(reqId: String) {
            jobs.remove(reqId)?.cancel()
        }

        // ---- misc
        @JavascriptInterface
        fun openUrl(url: String) = runOnUiThread { openExternal(Uri.parse(url)) }

        @JavascriptInterface
        fun copy(text: String) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("text", text))
        }

        @JavascriptInterface
        fun share(text: String) = runOnUiThread {
            runCatching {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }
        }

        @JavascriptInterface
        fun haptic(kind: String) = runOnUiThread {
            web.performHapticFeedback(when (kind) {
                "confirm" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                "reject" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
                else -> HapticFeedbackConstants.CLOCK_TICK
            })
        }
    }
}
