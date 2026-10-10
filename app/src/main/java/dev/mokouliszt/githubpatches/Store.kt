package dev.mokouliszt.githubpatches

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Small JSON-file store: settings, the imported patches (raw files + an index), recent push
 * targets and a cache of pushable repositories.
 *
 * patches/index.json: [{ id, name, title, importedAt, source, url, repo, sha1, size,
 *                        commits, files, additions, deletions, pushes: [{repo, branch, head, ...}] }]
 */
class Store(context: Context) {

    private val dir = context.filesDir
    private val patchDir = File(dir, "patches").apply { mkdirs() }
    private val indexFile = File(patchDir, "index.json")
    private val settingsFile = File(dir, "settings.json")
    private val reposFile = File(dir, "repos.json")
    private val recentFile = File(dir, "recent.json")

    companion object {
        private val lock = Any()

        val DEFAULTS: JSONObject
            get() = JSONObject()
                .put("lang", I18n.defaultLang())
                .put("githubClientId", "")
                .put("keepAuthor", true)   // commit author = the patch's author (like git am)
                .put("wrap", true)         // wrap long lines in the diff
    }

    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    // ------------------------------------------------------------------ settings

    fun settings(): JSONObject = synchronized(lock) {
        val s = runCatching { if (settingsFile.isFile) JSONObject(settingsFile.readText()) else JSONObject() }.getOrElse { JSONObject() }
        val d = DEFAULTS
        d.keys().forEach { k -> if (!s.has(k)) s.put(k, d.get(k)) }
        s.put("lang", I18n.normalize(s.optString("lang")))
        s
    }

    fun saveSettings(patch: JSONObject): JSONObject = synchronized(lock) {
        val s = settings()
        patch.keys().forEach { k -> if (DEFAULTS.has(k)) s.put(k, patch.get(k)) }
        s.put("lang", I18n.normalize(s.optString("lang")))
        write(settingsFile, s.toString())
        s
    }

    fun lang(): String = settings().optString("lang")

    // ------------------------------------------------------------------ patches

    fun index(): JSONArray = synchronized(lock) {
        runCatching { if (indexFile.isFile) JSONArray(indexFile.readText()) else JSONArray() }.getOrElse {
            indexFile.renameTo(File(patchDir, "index.corrupt-${System.currentTimeMillis()}.json"))
            JSONArray()
        }
    }

    private fun saveIndex(a: JSONArray) = write(indexFile, a.toString())

    fun entry(id: String): JSONObject? = synchronized(lock) {
        val a = index()
        (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
    }

    fun raw(id: String): ByteArray? = File(patchDir, "$id.patch").takeIf { it.isFile }?.readBytes()

    fun findBySha(sha1: String): JSONObject? = synchronized(lock) {
        val a = index()
        (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("sha1") == sha1 }
    }

    /** Adds a patch (newest first). [summary] holds title and counts from the parser. */
    fun add(bytes: ByteArray, summary: JSONObject): JSONObject = synchronized(lock) {
        val id = java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 1e6).toLong(), 36)
        File(patchDir, "$id.patch").writeBytes(bytes)
        val e = JSONObject(summary.toString())
            .put("id", id)
            .put("importedAt", System.currentTimeMillis())
            .put("size", bytes.size)
            .put("sha1", sha1(bytes))
            .put("pushes", JSONArray())
        val a = index()
        val out = JSONArray().put(e)
        for (i in 0 until a.length()) out.put(a.get(i))
        saveIndex(out)
        e
    }

    fun delete(id: String) = synchronized(lock) {
        val a = index()
        val out = JSONArray()
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            if (e.optString("id") != id) out.put(e)
        }
        saveIndex(out)
        File(patchDir, "$id.patch").delete()
    }

    fun addPush(id: String, push: JSONObject): JSONObject? = synchronized(lock) {
        val a = index()
        var updated: JSONObject? = null
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            if (e.optString("id") == id) {
                val pushes = e.optJSONArray("pushes") ?: JSONArray()
                pushes.put(push)
                e.put("pushes", pushes)
                updated = e
            }
        }
        saveIndex(a)
        updated
    }

    fun sha1(b: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ targets / repositories

    fun recent(): JSONArray = synchronized(lock) {
        runCatching { if (recentFile.isFile) JSONArray(recentFile.readText()) else JSONArray() }.getOrElse { JSONArray() }
    }

    /** Most recent first, one entry per repository, at most 8. */
    fun addRecent(repo: String, branch: String) = synchronized(lock) {
        val a = recent()
        val out = JSONArray().put(JSONObject().put("repo", repo).put("branch", branch))
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            if (e.optString("repo") != repo && out.length() < 8) out.put(e)
        }
        write(recentFile, out.toString())
    }

    fun cachedRepos(): JSONObject = synchronized(lock) {
        runCatching { if (reposFile.isFile) JSONObject(reposFile.readText()) else null }.getOrNull()
            ?: JSONObject().put("at", 0).put("items", JSONArray())
    }

    fun saveRepos(items: JSONArray) = synchronized(lock) {
        write(reposFile, JSONObject().put("at", System.currentTimeMillis()).put("items", items).toString())
    }

    fun clearAccountData() = synchronized(lock) {
        reposFile.delete()
        recentFile.delete()
    }
}
