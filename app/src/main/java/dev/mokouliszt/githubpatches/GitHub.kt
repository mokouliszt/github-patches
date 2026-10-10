package dev.mokouliszt.githubpatches

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.mokouliszt.githubpatches.patch.Author
import dev.mokouliszt.githubpatches.patch.GitRemote
import dev.mokouliszt.githubpatches.patch.RemoteException
import dev.mokouliszt.githubpatches.patch.RemoteException.Kind
import dev.mokouliszt.githubpatches.patch.TreeChange
import dev.mokouliszt.githubpatches.patch.TreeItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64

class GitHubException(message: String, val needLogin: Boolean = false) : Exception(message)

/**
 * GitHub sign-in (OAuth Device Flow, the user's own OAuth App or GitHub App) and the REST calls the
 * app needs. The token is kept in EncryptedSharedPreferences.
 *
 * OAuth App tokens do not expire. GitHub App user tokens expire after 8 hours and come with a
 * refresh token; they are refreshed before expiry and once more on a 401.
 */
class GitHub(context: Context) {

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int,
    )

    companion object {
        /** repo: push to public and private repositories; workflow: patches that touch .github/workflows. */
        const val SCOPES = "repo workflow"
        private const val API = "https://api.github.com"
        private const val JSON = "application/vnd.github+json"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_PATCH_BYTES = 20L * 1024 * 1024

        fun segment(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        fun branchPath(branch: String): String = branch.split('/').joinToString("/") { segment(it) }
    }

    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "github_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { context.getSharedPreferences("github_plain", Context.MODE_PRIVATE) }

    val token: String? get() = prefs.getString("token", null)
    val isLoggedIn: Boolean get() = !token.isNullOrBlank()

    private val refreshLock = Mutex()
    private val expiresAt: Long get() = prefs.getLong("expiresAt", 0L)
    private val refreshToken: String? get() = prefs.getString("refresh", null)

    private fun saveTokens(body: JSONObject, clientId: String) {
        val now = System.currentTimeMillis()
        val expiresIn = body.optLong("expires_in", 0L)
        val refresh = body.optString("refresh_token").takeUnless { it.isBlank() || it == "null" }
        val e = prefs.edit()
        e.putString("token", body.getString("access_token"))
        e.putString("tokenClientId", clientId)
        e.putString("scopes", body.optString("scope"))
        e.putLong("expiresAt", if (expiresIn > 0) now + expiresIn * 1000 else 0L)
        if (refresh != null) e.putString("refresh", refresh) else e.remove("refresh")
        e.commit()
    }

    private suspend fun ensureFresh() {
        val exp = expiresAt
        if (exp > 0 && System.currentTimeMillis() > exp - 5 * 60_000 && refreshToken != null) refresh(stale = null)
    }

    private suspend fun refresh(stale: String?): Boolean = refreshLock.withLock {
        if (stale != null && token != stale) return@withLock true
        if (stale == null && expiresAt > System.currentTimeMillis() + 5 * 60_000) return@withLock true
        val rt = refreshToken ?: return@withLock false
        val cid = prefs.getString("tokenClientId", null) ?: return@withLock false
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .header("User-Agent", Net.USER_AGENT)
                .post(FormBody.Builder()
                    .add("client_id", cid)
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", rt)
                    .build())
                .build()
            val body = try {
                Net.http.newCall(req).execute().use { JSONObject(it.body?.string().orEmpty().ifBlank { "{}" }) }
            } catch (e: IOException) {
                throw GitHubException(I18n.t("GitHubに接続できませんでした", "Could not reach GitHub"))
            }
            if (body.optString("access_token").isNotBlank()) {
                saveTokens(body, cid)
                true
            } else false
        }
    }

    private fun signInAgain() = GitHubException(
        I18n.t("GitHubのログインが無効になりました。ログインし直してください", "Your GitHub sign-in is no longer valid. Please sign in again."),
        needLogin = true,
    )

    fun user(): JSONObject = JSONObject()
        .put("login", prefs.getString("login", "") ?: "")
        .put("avatar", prefs.getString("avatar", "") ?: "")
        .put("name", prefs.getString("name", "") ?: "")

    fun logout() {
        prefs.edit().clear().apply()
    }

    // ---------------------------------------------------------------- Device Flow

    suspend fun startDeviceFlow(clientId: String): DeviceCode = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) throw GitHubException(I18n.t("Client IDが未設定です", "No client ID yet"))
        val req = Request.Builder().url("https://github.com/login/device/code")
            .header("Accept", "application/json")
            .header("User-Agent", Net.USER_AGENT)
            .post(FormBody.Builder().add("client_id", clientId).add("scope", SCOPES).build())
            .build()
        val (ok, body) = try {
            Net.http.newCall(req).execute().use { res -> res.isSuccessful to JSONObject(res.body?.string().orEmpty().ifBlank { "{}" }) }
        } catch (e: IOException) {
            throw GitHubException(I18n.t("GitHubに接続できませんでした", "Could not reach GitHub"))
        }
        if (!ok || body.has("error")) throw GitHubException(deviceError(body.optString("error"), body.optString("error_description")))
        DeviceCode(
            body.getString("device_code"),
            body.getString("user_code"),
            body.optString("verification_uri", "https://github.com/login/device"),
            body.optInt("expires_in", 900),
            body.optInt("interval", 5),
        )
    }

    suspend fun awaitDeviceToken(clientId: String, dc: DeviceCode): JSONObject {
        var interval = dc.interval.coerceAtLeast(5)
        val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val body = withContext(Dispatchers.IO) {
                val req = Request.Builder().url("https://github.com/login/oauth/access_token")
                    .header("Accept", "application/json")
                    .header("User-Agent", Net.USER_AGENT)
                    .post(FormBody.Builder()
                        .add("client_id", clientId)
                        .add("device_code", dc.deviceCode)
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                        .build())
                    .build()
                runCatching {
                    Net.http.newCall(req).execute().use { JSONObject(it.body?.string().orEmpty().ifBlank { "{}" }) }
                }.getOrElse { JSONObject().put("error", "network") }
            }
            if (body.optString("access_token").isNotBlank()) {
                saveTokens(body, clientId)
                return refreshProfile()
            }
            when (val err = body.optString("error")) {
                "authorization_pending", "network" -> Unit
                "slow_down" -> interval = body.optInt("interval", interval + 5)
                else -> throw GitHubException(deviceError(err, body.optString("error_description")))
            }
        }
        throw GitHubException(I18n.t("コードの有効期限が切れました", "The code expired"))
    }

    private fun deviceError(code: String, desc: String): String = when (code) {
        "expired_token" -> I18n.t("コードの有効期限が切れました", "The code expired")
        "access_denied" -> I18n.t("GitHubで認可がキャンセルされました", "Authorization was cancelled on GitHub")
        "incorrect_client_credentials" -> I18n.t("Client IDが正しくありません", "The client ID is not valid")
        "device_flow_disabled" -> I18n.t("このアプリでDevice Flowが有効になっていません", "Device Flow is not enabled for this app")
        else -> desc.ifBlank { I18n.t("GitHubへのログインに失敗しました", "GitHub sign-in failed") + if (code.isNotBlank()) " ($code)" else "" }
    }

    // ---------------------------------------------------------------- REST plumbing

    private fun request(method: String, url: String, accept: String, t: String?, body: JSONObject?): Request {
        val b = Request.Builder().url(url)
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", Net.USER_AGENT)
        if (t != null) b.header("Authorization", "Bearer $t")
        val rb = body?.toString()?.toRequestBody(JSON_TYPE)
        return when (method) {
            "GET" -> b.get()
            else -> b.method(method, rb ?: "{}".toRequestBody(JSON_TYPE))
        }.build()
    }

    /** Authenticated call with token upkeep: refresh before expiry, and once more on a 401. */
    private suspend fun <T> call(
        method: String, pathOrUrl: String, body: JSONObject? = null, accept: String = JSON, read: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        if (token.isNullOrBlank()) throw GitHubException(I18n.t("GitHubにログインしてください", "Sign in to GitHub first"), needLogin = true)
        ensureFresh()
        val url = if (pathOrUrl.startsWith("https://")) pathOrUrl else API + pathOrUrl
        var t = token ?: throw signInAgain()
        try {
            var res = Net.http.newCall(request(method, url, accept, t, body)).execute()
            if (res.code == 401) {
                res.close()
                if (refreshToken == null || !refresh(stale = t)) throw signInAgain()
                t = token ?: throw signInAgain()
                res = Net.http.newCall(request(method, url, accept, t, body)).execute()
            }
            res.use(read)
        } catch (e: IOException) {
            throw RemoteException(Kind.NETWORK, I18n.t("GitHubに接続できませんでした", "Could not reach GitHub"))
        }
    }

    private fun failure(res: Response, text: String): Exception {
        val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
        val lower = msg.lowercase()
        if (res.code == 401) return signInAgain()
        val kind = when {
            lower.contains("workflow") && lower.contains("scope") -> Kind.WORKFLOW_SCOPE
            res.code == 429 || (res.code == 403 && res.header("X-RateLimit-Remaining") == "0") -> Kind.RATE_LIMIT
            res.code == 404 -> Kind.NOT_FOUND
            res.code == 409 && lower.contains("empty") -> Kind.EMPTY_REPO
            lower.contains("fast forward") || lower.contains("fast-forward") -> Kind.NOT_FAST_FORWARD
            lower.contains("already exists") -> Kind.ALREADY_EXISTS
            res.code == 403 || lower.contains("protected") -> Kind.FORBIDDEN
            else -> Kind.OTHER
        }
        return RemoteException(kind, msg.ifBlank { "HTTP ${res.code}" }, res.code)
    }

    private fun json(res: Response): JSONObject {
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw failure(res, text)
        return JSONObject(text.ifBlank { "{}" })
    }

    private fun jsonArray(res: Response): Pair<JSONArray, Boolean> {
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw failure(res, text)
        return JSONArray(text.ifBlank { "[]" }) to (res.header("Link")?.contains("rel=\"next\"") == true)
    }

    suspend fun refreshProfile(): JSONObject {
        val u = call("GET", "/user") { json(it) }
        prefs.edit()
            .putString("login", u.optString("login"))
            .putString("avatar", u.optString("avatar_url"))
            .putString("name", u.optString("name").takeUnless { it == "null" } ?: "")
            .apply()
        return user()
    }

    // ---------------------------------------------------------------- repositories

    /** Repositories the user can push to, most recently pushed first. */
    suspend fun repos(): JSONArray {
        val out = JSONArray()
        var page = 1
        while (page <= 10) {
            val (items, next) = call("GET", "/user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member&page=$page") { jsonArray(it) }
            for (i in 0 until items.length()) {
                val r = items.optJSONObject(i) ?: continue
                val canPush = r.optJSONObject("permissions")?.optBoolean("push") ?: false
                if (!canPush || r.optBoolean("archived")) continue
                out.put(JSONObject()
                    .put("fullName", r.optString("full_name"))
                    .put("owner", r.optJSONObject("owner")?.optString("login").orEmpty())
                    .put("name", r.optString("name"))
                    .put("private", r.optBoolean("private"))
                    .put("fork", r.optBoolean("fork"))
                    .put("defaultBranch", r.optString("default_branch"))
                    .put("pushedAt", r.optString("pushed_at").takeUnless { it == "null" } ?: ""))
            }
            if (!next || items.length() == 0) break
            page++
        }
        return out
    }

    suspend fun repo(fullName: String): JSONObject {
        val r = call("GET", "/repos/${repoPath(fullName)}") { json(it) }
        return JSONObject()
            .put("fullName", r.optString("full_name"))
            .put("owner", r.optJSONObject("owner")?.optString("login").orEmpty())
            .put("name", r.optString("name"))
            .put("private", r.optBoolean("private"))
            .put("fork", r.optBoolean("fork"))
            .put("defaultBranch", r.optString("default_branch"))
            .put("pushedAt", r.optString("pushed_at").takeUnless { it == "null" } ?: "")
    }

    suspend fun branches(fullName: String): JSONArray {
        val out = JSONArray()
        var page = 1
        while (page <= 10) {
            val (items, next) = call("GET", "/repos/${repoPath(fullName)}/branches?per_page=100&page=$page") { jsonArray(it) }
            for (i in 0 until items.length()) {
                val b = items.optJSONObject(i) ?: continue
                out.put(JSONObject().put("name", b.optString("name")).put("protected", b.optBoolean("protected")))
            }
            if (!next || items.length() == 0) break
            page++
        }
        return out
    }

    private fun repoPath(fullName: String): String = fullName.split('/').joinToString("/") { segment(it) }

    // ---------------------------------------------------------------- patches from URLs

    class Fetched(val bytes: ByteArray, val name: String, val repo: String?)

    /**
     * Pull request / commit / compare pages on github.com are fetched through the API as patches,
     * so private repositories work too. Anything else is downloaded as-is.
     */
    suspend fun fetchPatch(url: String): Fetched {
        val u = url.trim()
        val m = Regex("^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/(pull|commit|compare)/([^?#]+)").find(u)
        if (m != null) {
            val (owner, repo, kind, restRaw) = m.destructured
            val rest = restRaw.removeSuffix(".patch").removeSuffix(".diff").trimEnd('/')
            val fullName = "$owner/$repo"
            val (path, name) = when (kind) {
                "pull" -> {
                    val n = rest.substringBefore('/')
                    "/repos/${repoPath(fullName)}/pulls/$n" to "$repo-pr-$n.patch"
                }
                "commit" -> {
                    val sha = rest.substringBefore('/')
                    "/repos/${repoPath(fullName)}/commits/${segment(sha)}" to "$repo-${sha.take(7)}.patch"
                }
                else -> "/repos/${repoPath(fullName)}/compare/${rest.split('/').joinToString("/") { segment(it) }}" to "$repo-compare.patch"
            }
            val bytes = call("GET", path, accept = "application/vnd.github.patch") { res -> readLimited(res) }
            return Fetched(bytes, name, fullName)
        }
        if (!u.startsWith("https://") && !u.startsWith("http://")) throw GitHubException(I18n.t("URLが正しくありません", "Not a valid URL"))
        val host = runCatching { java.net.URI(u).host.orEmpty() }.getOrDefault("")
        val sendToken = host == "raw.githubusercontent.com" || host == "api.github.com" || host == "gist.githubusercontent.com"
        val bytes = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(u).header("User-Agent", Net.USER_AGENT).apply {
                if (sendToken) token?.let { header("Authorization", "Bearer $it") }
            }.build()
            try {
                Net.http.newCall(req).execute().use { res -> readLimited(res) }
            } catch (e: IOException) {
                throw RemoteException(Kind.NETWORK, I18n.t("ダウンロードできませんでした", "Download failed"))
            }
        }
        val name = u.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/').ifBlank { "download.patch" }
        val repo = Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/").find(u)?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
        return Fetched(bytes, name, repo)
    }

    private fun readLimited(res: Response): ByteArray {
        if (!res.isSuccessful) throw failure(res, res.body?.string().orEmpty())
        val body = res.body ?: return ByteArray(0)
        if (body.contentLength() > MAX_PATCH_BYTES) throw GitHubException(I18n.t("ファイルが大きすぎます", "The file is too large"))
        val bytes = body.byteStream().use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_PATCH_BYTES) throw GitHubException(I18n.t("ファイルが大きすぎます", "The file is too large"))
            }
            buf.toByteArray()
        }
        return bytes
    }

    // ---------------------------------------------------------------- Git Data API

    fun remote(fullName: String): GitRemote = Remote(repoPath(fullName))

    private inner class Remote(private val repo: String) : GitRemote {
        private val base get() = "/repos/$repo"

        override suspend fun branchHead(branch: String): String? = try {
            val o = call("GET", "$base/git/ref/heads/${branchPath(branch)}") { json(it) }
            val obj = o.optJSONObject("object")
            // A prefix match returns an array instead; only an exact ref counts.
            obj?.optString("sha")?.takeIf { it.isNotBlank() && o.optString("ref") == "refs/heads/$branch" }
        } catch (e: RemoteException) {
            if (e.kind == Kind.NOT_FOUND) null else throw e
        } catch (e: org.json.JSONException) {
            null
        }

        override suspend fun commitTree(commit: String): String =
            call("GET", "$base/git/commits/$commit") { json(it) }.getJSONObject("tree").getString("sha")

        override suspend fun tree(sha: String): List<TreeItem> {
            val o = call("GET", "$base/git/trees/$sha") { json(it) }
            val arr = o.optJSONArray("tree") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                TreeItem(e.optString("path"), e.optString("mode"), e.optString("type"), e.optString("sha"))
            }
        }

        override suspend fun blob(sha: String): ByteArray =
            call("GET", "$base/git/blobs/$sha", accept = "application/vnd.github.raw+json") { res ->
                if (!res.isSuccessful) throw failure(res, res.body?.string().orEmpty())
                res.body?.bytes() ?: ByteArray(0)
            }

        override suspend fun createBlob(content: ByteArray): String {
            val body = JSONObject()
                .put("content", Base64.getEncoder().encodeToString(content))
                .put("encoding", "base64")
            return call("POST", "$base/git/blobs", body) { json(it) }.getString("sha")
        }

        override suspend fun createTree(baseTree: String, changes: List<TreeChange>): String {
            val items = JSONArray()
            for (c in changes) {
                items.put(JSONObject()
                    .put("path", c.path)
                    .put("mode", c.mode)
                    .put("type", "blob")
                    .put("sha", c.sha ?: JSONObject.NULL))
            }
            val body = JSONObject().put("base_tree", baseTree).put("tree", items)
            return call("POST", "$base/git/trees", body) { json(it) }.getString("sha")
        }

        override suspend fun createCommit(message: String, tree: String, parents: List<String>, author: Author?): String {
            val body = JSONObject()
                .put("message", message)
                .put("tree", tree)
                .put("parents", JSONArray(parents))
            if (author != null) {
                val a = JSONObject().put("name", author.name).put("email", author.email)
                author.date?.let { a.put("date", it) }
                body.put("author", a)
            }
            return call("POST", "$base/git/commits", body) { json(it) }.getString("sha")
        }

        override suspend fun updateBranch(branch: String, commit: String) {
            call("PATCH", "$base/git/refs/heads/${branchPath(branch)}", JSONObject().put("sha", commit).put("force", false)) { json(it) }
        }

        override suspend fun createBranch(branch: String, commit: String) {
            call("POST", "$base/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", commit)) { json(it) }
        }
    }
}
