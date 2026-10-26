package dev.mokouliszt.githubpatches

import dev.mokouliszt.githubpatches.patch.BinaryKind
import dev.mokouliszt.githubpatches.patch.CheckReport
import dev.mokouliszt.githubpatches.patch.FileDiff
import dev.mokouliszt.githubpatches.patch.PatchSet
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** What the UI shows. File contents are decoded for display only (UTF-8, else Shift_JIS). */
object PatchJson {

    private const val MAX_LINES = 20_000
    private const val MAX_LINE_CHARS = 2_000
    private val SJIS: Charset? = runCatching { Charset.forName("windows-31j") }.getOrNull()
        ?: runCatching { Charset.forName("Shift_JIS") }.getOrNull()

    fun summary(p: PatchSet, name: String): JSONObject {
        val title = p.commits.firstOrNull { it.subject.isNotBlank() }?.subject
            ?: name.substringBeforeLast('.').ifBlank { p.files.firstOrNull()?.path ?: name }
        return JSONObject()
            .put("name", name)
            .put("title", title)
            .put("commits", p.commits.size)
            .put("files", p.files.map { it.path }.distinct().size)
            .put("additions", p.additions)
            .put("deletions", p.deletions)
            .put("meta", p.hasMetadata)
    }

    /**
     * Files whose changes the entry's last push left out because 1.0.0 could not read some hunk
     * headers (see [FileDiff.droppedBy100]). Pushes since then carry the app version ("v").
     */
    fun missed(entry: JSONObject, p: PatchSet): List<FileDiff> {
        val pushes = entry.optJSONArray("pushes") ?: return emptyList()
        val last = pushes.optJSONObject(pushes.length() - 1) ?: return emptyList()
        if (last.has("v")) return emptyList()
        return p.files.filter { it.droppedBy100 > 0 }
    }

    fun detail(entry: JSONObject, p: PatchSet): JSONObject {
        val missed = missed(entry, p).toSet()
        val commits = JSONArray()
        val files = JSONArray()
        var budget = MAX_LINES
        p.commits.forEachIndexed { ci, c ->
            val idx = JSONArray()
            for (f in c.files) {
                idx.put(files.length())
                val lines = f.hunks.sumOf { it.lines.size }
                val show = lines <= budget
                if (show) budget -= lines
                files.put(file(ci, f, show).apply { if (f in missed) put("missed", f.droppedBy100) })
            }
            commits.put(JSONObject()
                .put("sha", c.sha ?: "")
                .put("subject", c.subject)
                .put("body", c.body)
                .put("message", c.message)
                .put("author", c.authorName ?: "")
                .put("email", c.authorEmail ?: "")
                .put("date", c.date ?: "")
                .put("files", idx)
                .put("additions", c.files.sumOf { it.additions })
                .put("deletions", c.files.sumOf { it.deletions }))
        }
        return JSONObject(entry.toString()).put("detail", JSONObject().put("commits", commits).put("files", files))
    }

    private fun file(commit: Int, f: FileDiff, show: Boolean): JSONObject {
        val o = JSONObject()
            .put("commit", commit)
            .put("path", f.path)
            .put("oldPath", f.oldPath ?: "")
            .put("status", f.status.name.lowercase())
            .put("oldMode", f.oldMode ?: "")
            .put("newMode", f.newMode ?: "")
            .put("additions", f.additions)
            .put("deletions", f.deletions)
            .put("binary", f.isBinary)
            .put("binarySize", if (f.binary?.kind == BinaryKind.LITERAL) f.binary.size else -1)
            .put("submodule", f.submodule)
            .put("truncated", !show)
        if (!show) return o.put("hunks", JSONArray())
        val cs = charsetFor(f)
        val hunks = JSONArray()
        for (h in f.hunks) {
            var oldNo = h.oldStart
            var newNo = h.newStart
            val rows = JSONArray()
            for (l in h.lines) {
                val (text, eol) = decode(l.bytes, cs)
                val row = JSONArray().put(l.kind.toString())
                when (l.kind) {
                    ' ' -> { row.put(oldNo++).put(newNo++) }
                    '-' -> { row.put(oldNo++).put(0) }
                    else -> { row.put(0).put(newNo++) }
                }
                row.put(text)
                if (!eol) row.put(1)
                rows.put(row)
            }
            hunks.put(JSONObject()
                .put("header", "@@ -${h.oldStart},${h.oldCount} +${h.newStart},${h.newCount} @@")
                .put("section", h.section)
                .put("lines", rows))
        }
        return o.put("hunks", hunks).put("encoding", if (cs == SJIS) "Shift_JIS" else "UTF-8")
    }

    private fun charsetFor(f: FileDiff): Charset {
        val all = f.hunks.flatMap { h -> h.lines.map { it.bytes } }
        if (valid(all, Charsets.UTF_8)) return Charsets.UTF_8
        if (SJIS != null && valid(all, SJIS)) return SJIS
        return Charsets.UTF_8
    }

    private fun valid(lines: List<ByteArray>, cs: Charset): Boolean {
        val dec = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return lines.all { runCatching { dec.reset(); dec.decode(ByteBuffer.wrap(it)) }.isSuccess }
    }

    /** Line text without its terminator; the flag is false when the line has no newline. */
    private fun decode(b: ByteArray, cs: Charset): Pair<String, Boolean> {
        var end = b.size
        val eol = end > 0 && b[end - 1] == '\n'.code.toByte()
        if (eol) end--
        if (end > 0 && b[end - 1] == '\r'.code.toByte()) end--
        var s = String(b, 0, end, cs)
        if (s.length > MAX_LINE_CHARS) s = s.substring(0, MAX_LINE_CHARS) + "…"
        return s to eol
    }

    fun check(r: CheckReport): JSONObject {
        val files = JSONArray()
        for (f in r.files) {
            files.put(JSONObject()
                .put("commit", f.commit)
                .put("path", f.path)
                .put("oldPath", f.oldPath ?: "")
                .put("status", f.status.name.lowercase())
                .put("outcome", f.outcome.name.lowercase())
                .put("hunk", f.hunk)
                .put("reason", f.reason?.name?.lowercase() ?: ""))
        }
        return JSONObject().put("ok", r.ok).put("head", r.head).put("files", files)
    }
}
