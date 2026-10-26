package dev.mokouliszt.githubpatches.patch

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.zip.Inflater

/**
 * Parses `git format-patch` mails (one or many in one file), `git diff` output and traditional
 * unified diffs. Everything is byte based: file contents are never decoded, so any encoding
 * (UTF-8, Shift_JIS, CRLF, files without a final newline) survives unchanged.
 *
 * Header lines are handled as Latin-1 text, one char per byte. Regexes over them must not let a bare
 * `.` meet those chars (it stops at U+0085, the middle byte of 公), and trimming stays ASCII
 * (Kotlin's trim() also drops U+00A0, the last byte of だ).
 *
 * Nothing is skipped silently: an unreadable hunk header, lines that do not fit a hunk's counts, or
 * totals that differ from the mail's own diffstat reject the whole patch.
 */
object PatchParser {

    private val MBOX_FROM = Regex("^From ([0-9a-f]{40}|[0-9a-f]{64}) ")
    private val HUNK = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$", RegexOption.DOT_MATCHES_ALL)
    /** The header regex of 1.0.0. It could not read headers holding byte 0x85 or a CR; see [Hunk.droppedBy100]. */
    private val HUNK_100 = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$")
    private val INDEX = Regex("^index ([0-9a-fA-F]+)\\.\\.([0-9a-fA-F]+)(?: ([0-7]{6}))?")
    private val ENCODED_WORD = Regex("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?]*)\\?=")
    private val LEADING_BRACKETS = Regex("^\\s*(?:(?:re|aw|fwd?)\\s*:\\s*|\\[[^\\]]*\\]\\s*)+", RegexOption.IGNORE_CASE)
    private val IN_BODY_HEADER = Regex("^(From|Subject|Date): (.*)$", RegexOption.DOT_MATCHES_ALL)
    private val DIFFSTAT = Regex("^ (\\d+) files? changed(?:, (\\d+) insertions?\\(\\+\\))?(?:, (\\d+) deletions?\\(-\\))?$")
    private val CHARSET = Regex("charset\\s*=\\s*\"?([^\";\\s]+)", RegexOption.IGNORE_CASE)
    /** "[PATCH 0/3]", "[PATCH v2 00/12]": a series' cover letter, which has no diff. */
    private val COVER_LETTER = Regex("^\\s*\\[[^\\]]*\\b0+/\\d+\\]")

    fun parse(input: ByteArray): PatchSet {
        val lines = normalize(splitLines(input))
        val starts = lines.indices.filter { MBOX_FROM.containsMatchIn(text(lines[it])) }
        val commits = ArrayList<PatchCommit>()
        if (starts.isEmpty()) {
            val first = lines.indexOfFirst { text(it).isNotBlank() }
            val firstText = if (first >= 0) text(lines[first]) else ""
            if (firstText.startsWith("From: ") || firstText.startsWith("Subject: ") || firstText.startsWith("Date: ")) {
                commits += parseMail(lines, first, lines.size).commit
            } else {
                val files = parseFiles(lines, 0, lines.size)
                commits += PatchCommit(null, null, null, null, "", "", files)
            }
        } else {
            // Text before the first mail (rare) is parsed as a plain diff if it holds any.
            if (starts.first() > 0) {
                val pre = parseFiles(lines, 0, starts.first())
                if (pre.isNotEmpty()) commits += PatchCommit(null, null, null, null, "", "", pre)
            }
            for ((n, s) in starts.withIndex()) {
                val end = if (n + 1 < starts.size) starts[n + 1] else lines.size
                val mail = parseMail(lines, s, end)
                // git am stops at a cover letter; pushing it would make an empty commit.
                if (mail.files.isEmpty() && starts.size > 1 && COVER_LETTER.containsMatchIn(mail.rawSubject)) continue
                commits += mail.commit
            }
        }
        if (commits.all { it.files.isEmpty() } && commits.none { it.hasMetadata }) {
            throw PatchFormatException("no diff found")
        }
        return PatchSet(commits)
    }

    // ------------------------------------------------------------------ lines

    internal fun splitLines(b: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var start = 0
        for (i in b.indices) {
            if (b[i] == '\n'.code.toByte()) {
                out += b.copyOfRange(start, i + 1)
                start = i + 1
            }
        }
        if (start < b.size) out += b.copyOfRange(start, b.size)
        return out
    }

    /**
     * A patch whose own header lines end in CRLF was converted as a whole (mail clients, Windows
     * editors). Undo that once so that content lines keep only the CRs the files really have.
     */
    private fun normalize(lines: List<ByteArray>): List<ByteArray> {
        val probe = lines.firstOrNull {
            val t = it.latin1()
            t.startsWith("diff --git ") || t.startsWith("--- ") || t.startsWith("+++ ") || t.startsWith("@@ ") ||
                t.startsWith("Subject: ") || MBOX_FROM.containsMatchIn(t)
        } ?: return lines
        if (!endsWithCrLf(probe)) return lines
        return lines.map { if (endsWithCrLf(it)) it.copyOfRange(0, it.size - 2) + byteArrayOf('\n'.code.toByte()) else it }
    }

    private fun endsWithCrLf(b: ByteArray) = b.size >= 2 && b[b.size - 1] == '\n'.code.toByte() && b[b.size - 2] == '\r'.code.toByte()

    private fun ByteArray.latin1(): String = String(this, Charsets.ISO_8859_1)

    /** Header text of a line, without its terminator (bytes kept 1:1 as Latin-1 chars). */
    private fun text(b: ByteArray): String = b.latin1().trimEnd('\n', '\r')

    private fun decode(latin1: String, cs: Charset): String = String(latin1.toByteArray(Charsets.ISO_8859_1), cs)

    private fun utf8(latin1: String): String = decode(latin1, Charsets.UTF_8)

    private fun String.trimBlanks(): String = trim(' ', '\t')

    // ------------------------------------------------------------------ mails

    private class Mail(val commit: PatchCommit, val rawSubject: String) {
        val files get() = commit.files
    }

    private fun parseMail(all: List<ByteArray>, from: Int, to: Int): Mail {
        var i = from
        var sha: String? = null
        MBOX_FROM.find(text(all[i]))?.let { sha = it.groupValues[1]; i++ }

        val headers = LinkedHashMap<String, String>()
        var current: String? = null
        while (i < to) {
            val t = text(all[i])
            if (t.isEmpty()) { i++; break }
            if ((t.startsWith(" ") || t.startsWith("\t")) && current != null) {
                headers[current] = headers[current] + " " + t.trimBlanks()
            } else {
                val colon = t.indexOf(':')
                if (colon <= 0) break // not a header block after all
                current = t.substring(0, colon).trimBlanks().lowercase()
                headers[current] = t.substring(colon + 1).trimBlanks()
            }
            i++
        }
        val cs = headers["content-type"]?.let { CHARSET.find(it)?.groupValues?.get(1) }
            ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8

        // A quoted-printable or base64 body would otherwise be applied in its encoded form.
        val decoded = transferDecoded(all.subList(i, to), headers["content-transfer-encoding"])
        val lines = decoded ?: all
        val lo = if (decoded != null) 0 else i
        val hi = if (decoded != null) decoded.size else to

        // Body ends at the "---" line before the diffstat, or where the diff itself begins.
        val body = ArrayList<String>()
        var diffStart = hi
        var j = lo
        while (j < hi) {
            val t = text(lines[j])
            if (t == "---") { diffStart = j + 1; break }
            if (isDiffStart(lines, j, hi)) { diffStart = j; break }
            body += t
            j++
        }
        // In-body headers ("From: ...", "Subject: ..." followed by a blank line) override the mail's.
        val inBody = HashSet<String>()
        var bodyStart = 0
        while (bodyStart < body.size) {
            val m = IN_BODY_HEADER.find(body[bodyStart]) ?: break
            val key = m.groupValues[1].lowercase()
            headers[key] = m.groupValues[2]
            inBody += key
            bodyStart++
        }
        if (bodyStart > 0 && bodyStart < body.size && body[bodyStart].trimBlanks().isEmpty()) bodyStart++

        fun header(key: String) = headers[key]?.let { decodeHeader(it, if (key in inBody) cs else Charsets.UTF_8) }
        val (name, email) = parseAddress(header("from"))
        val subject = cleanSubject(header("subject") ?: "")
        val message = body.drop(bodyStart).joinToString("\n") { decode(it, cs) }.trim('\n').trimEnd()
        val files = parseFiles(lines, diffStart, hi)
        checkDiffstat(lines, diffStart, hi, files)
        return Mail(PatchCommit(sha, name, email, parseDate(headers["date"]), subject, message, files), headers["subject"] ?: "")
    }

    private fun isDiffStart(lines: List<ByteArray>, i: Int, to: Int): Boolean {
        val t = text(lines[i])
        if (t.startsWith("diff --git ")) return true
        return t.startsWith("--- ") && i + 2 < to && text(lines[i + 1]).startsWith("+++ ") && text(lines[i + 2]).startsWith("@@ ")
    }

    private fun transferDecoded(lines: List<ByteArray>, encoding: String?): List<ByteArray>? {
        val bytes = when (encoding?.trimBlanks()?.lowercase()) {
            "quoted-printable" -> decodeQuotedPrintable(lines)
            "base64" -> runCatching { Base64.getMimeDecoder().decode(lines.fold(ByteArray(0)) { a, b -> a + b }) }
                .getOrElse { throw PatchFormatException("corrupt base64 mail body") }
            else -> return null
        }
        return splitLines(bytes)
    }

    private fun decodeQuotedPrintable(lines: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (raw in lines) {
            var end = raw.size
            while (end > 0 && (raw[end - 1] == '\n'.code.toByte() || raw[end - 1] == '\r'.code.toByte())) end--
            val hadEol = end < raw.size
            while (end > 0 && (raw[end - 1] == ' '.code.toByte() || raw[end - 1] == '\t'.code.toByte())) end--
            val soft = end > 0 && raw[end - 1] == '='.code.toByte()
            if (soft) end--
            var k = 0
            while (k < end) {
                val c = raw[k]
                if (c == '='.code.toByte() && k + 2 < end) {
                    val hi = Character.digit(raw[k + 1].toInt(), 16)
                    val lo = Character.digit(raw[k + 2].toInt(), 16)
                    if (hi >= 0 && lo >= 0) { out.write(hi * 16 + lo); k += 3; continue }
                }
                out.write(c.toInt())
                k++
            }
            if (!soft && hadEol) out.write('\n'.code)
        }
        return out.toByteArray()
    }

    /**
     * git format-patch writes "N files changed, X insertions(+), Y deletions(-)" after "---". The
     * parsed diff must hold exactly that; anything else means lines were lost or added on the way.
     */
    private fun checkDiffstat(lines: List<ByteArray>, from: Int, to: Int, files: List<FileDiff>) {
        var i = from
        while (i < to && !isDiffStart(lines, i, to)) {
            val m = DIFFSTAT.find(text(lines[i]))
            if (m != null) {
                val f = m.groupValues[1].toInt()
                val ins = m.groupValues[2].ifEmpty { "0" }.toInt()
                val del = m.groupValues[3].ifEmpty { "0" }.toInt()
                // A type change (file <-> symlink) is two diffs of one path but one diffstat entry.
                val paths = files.map { it.path }.distinct().size
                if (f != paths || ins != files.sumOf { it.additions } || del != files.sumOf { it.deletions }) {
                    throw PatchCountException(
                        "diffstat says $f files +$ins -$del, the diff holds $paths files " +
                            "+${files.sumOf { it.additions }} -${files.sumOf { it.deletions }}",
                    )
                }
                return
            }
            i++
        }
    }

    internal fun cleanSubject(s: String): String = s.replace(LEADING_BRACKETS, "").trim()

    /**
     * RFC 2047 encoded words; raw 8-bit header bytes are taken as [raw8bit]. Adjacent words are
     * joined before decoding, since a mailer may split one multi-byte character across two.
     */
    internal fun decodeHeader(raw: String, raw8bit: Charset = Charsets.UTF_8): String {
        val out = StringBuilder()
        val pending = ByteArrayOutputStream()
        var pendingCs: Charset? = null
        fun flush() {
            pendingCs?.let { out.append(String(pending.toByteArray(), it)) }
            pending.reset()
            pendingCs = null
        }
        var last = 0
        for (m in ENCODED_WORD.findAll(raw)) {
            val gap = raw.substring(last, m.range.first)
            val cs = runCatching { Charset.forName(m.groupValues[1]) }.getOrDefault(Charsets.UTF_8)
            // Whitespace between two encoded words is not part of the text.
            if (!(pendingCs != null && gap.isBlank())) { flush(); out.append(decode(gap, raw8bit)) }
            if (pendingCs != null && pendingCs != cs) flush()
            val payload = m.groupValues[3]
            val bytes = if (m.groupValues[2].equals("b", true)) {
                runCatching { Base64.getDecoder().decode(payload) }.getOrDefault(ByteArray(0))
            } else {
                decodeQ(payload)
            }
            pending.write(bytes)
            pendingCs = cs
            last = m.range.last + 1
        }
        flush()
        out.append(decode(raw.substring(last), raw8bit))
        return out.toString()
    }

    private fun decodeQ(p: String): ByteArray {
        val bos = ByteArrayOutputStream()
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '_' -> bos.write(' '.code)
                c == '=' && i + 2 < p.length -> {
                    val v = p.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { bos.write(v); i += 2 } else bos.write(c.code)
                }
                else -> bos.write(c.code and 0xff)
            }
            i++
        }
        return bos.toByteArray()
    }

    private fun parseAddress(v: String?): Pair<String?, String?> {
        if (v.isNullOrBlank()) return null to null
        val m = Regex("^(.*?)\\s*<([^>]*)>\\s*$", RegexOption.DOT_MATCHES_ALL).find(v)
        if (m != null) {
            var name = m.groupValues[1].trim()
            if (name.length >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            }
            val email = m.groupValues[2].trim()
            return (name.ifBlank { email }) to email
        }
        val p = Regex("^(\\S+@\\S+)\\s*\\((.*)\\)$", RegexOption.DOT_MATCHES_ALL).find(v.trim())
        if (p != null) return p.groupValues[2] to p.groupValues[1]
        return v.trim() to v.trim()
    }

    private fun parseDate(v: String?): String? {
        if (v.isNullOrBlank()) return null
        val cleaned = v.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
        return runCatching {
            OffsetDateTime.parse(cleaned, DateTimeFormatter.RFC_1123_DATE_TIME).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }.getOrNull()
    }

    // ------------------------------------------------------------------ files

    private fun parseFiles(lines: List<ByteArray>, from: Int, to: Int): List<FileDiff> {
        val out = ArrayList<FileDiff>()
        var i = from
        while (i < to) {
            val t = text(lines[i])
            if (t.startsWith("diff --git ")) {
                val (f, next) = parseGitFile(lines, i, to)
                out += f
                i = next
            } else if (t.startsWith("diff --cc ") || t.startsWith("diff --combined ")) {
                throw PatchFormatException("combined diffs (merges) are not supported", i + 1)
            } else if (t.startsWith("--- ") && i + 1 < to && text(lines[i + 1]).startsWith("+++ ")) {
                val (f, next) = parseTraditionalFile(lines, i, to)
                if (f != null) out += f
                i = next
            } else {
                i++
            }
        }
        return out
    }

    private fun parseGitFile(lines: List<ByteArray>, start: Int, to: Int): Pair<FileDiff, Int> {
        val header = text(lines[start]).removePrefix("diff --git ")
        val (hOld, hNew) = splitGitHeader(header)
        val prefixed = hasPrefix(hOld) && hasPrefix(hNew)
        fun strip(p: String?): String? = when {
            p == null || p == "/dev/null" -> p
            prefixed && hasPrefix(p) -> p.substring(2)
            else -> p
        }

        var oldMode: String? = null
        var newMode: String? = null
        var added = false
        var deleted = false
        var renameFrom: String? = null
        var renameTo: String? = null
        var copyFrom: String? = null
        var copyTo: String? = null
        var similarity: Int? = null
        var oldIndex: String? = null
        var newIndex: String? = null
        var minus: String? = null
        var plus: String? = null
        var binary: BinaryPatch? = null
        var binaryWithoutData = false

        var i = start + 1
        loop@ while (i < to) {
            val t = text(lines[i])
            when {
                t.startsWith("old mode ") -> oldMode = t.substring(9).trimBlanks()
                t.startsWith("new mode ") -> newMode = t.substring(9).trimBlanks()
                t.startsWith("deleted file mode ") -> { deleted = true; oldMode = t.substring(18).trimBlanks() }
                t.startsWith("new file mode ") -> { added = true; newMode = t.substring(14).trimBlanks() }
                t.startsWith("rename from ") -> renameFrom = parsePath(t.substring(12))
                t.startsWith("rename old ") -> renameFrom = parsePath(t.substring(11))
                t.startsWith("rename to ") -> renameTo = parsePath(t.substring(10))
                t.startsWith("rename new ") -> renameTo = parsePath(t.substring(11))
                t.startsWith("copy from ") -> copyFrom = parsePath(t.substring(10))
                t.startsWith("copy to ") -> copyTo = parsePath(t.substring(8))
                t.startsWith("similarity index ") -> similarity = t.substring(17).trimEnd('%').toIntOrNull()
                t.startsWith("dissimilarity index ") -> Unit
                t.startsWith("index ") -> INDEX.find(t)?.let {
                    oldIndex = it.groupValues[1]
                    newIndex = it.groupValues[2]
                    if (it.groupValues[3].isNotEmpty() && oldMode == null && newMode == null) {
                        oldMode = it.groupValues[3]
                    }
                }
                t.startsWith("--- ") -> {
                    minus = parsePath(t.substring(4))
                    if (i + 1 < to && text(lines[i + 1]).startsWith("+++ ")) {
                        plus = parsePath(text(lines[i + 1]).substring(4))
                        i += 2
                    } else {
                        i++
                    }
                    break@loop
                }
                t == "GIT binary patch" -> {
                    val (b, next) = parseBinary(lines, i + 1, to)
                    binary = b
                    i = next
                    break@loop
                }
                t.startsWith("Binary files ") && t.endsWith(" differ") -> { binaryWithoutData = true; i++; break@loop }
                else -> break@loop
            }
            i++
        }

        // git writes exact counts. Only a diff without an index line can be hand-written.
        val lenient = oldIndex == null
        val hunks = ArrayList<Hunk>()
        while (i < to && text(lines[i]).startsWith("@@ ")) {
            val (h, next) = parseHunk(lines, i, to, lenient)
            hunks += h
            i = next
        }
        // The index line says the content changed, but no change follows: the patch was cut short.
        if (hunks.isEmpty() && binary == null && !binaryWithoutData && oldIndex != null && newIndex != null &&
            oldIndex != newIndex && !oldIndex.all { it == '0' } && !newIndex.all { it == '0' }
        ) {
            throw PatchFormatException("no changes for a changed file", start + 1)
        }

        if (minus == "/dev/null") added = true
        if (plus == "/dev/null") deleted = true
        val oldPath = renameFrom ?: copyFrom ?: strip(minus)?.takeIf { it != "/dev/null" } ?: strip(hOld)
        val newPath = renameTo ?: copyTo ?: strip(plus)?.takeIf { it != "/dev/null" } ?: strip(hNew)
        val status = when {
            added -> FileStatus.ADDED
            deleted -> FileStatus.DELETED
            renameFrom != null || renameTo != null -> FileStatus.RENAMED
            copyFrom != null || copyTo != null -> FileStatus.COPIED
            else -> FileStatus.MODIFIED
        }
        val submodule = oldMode == "160000" || newMode == "160000" ||
            hunks.any { h -> h.lines.any { String(it.bytes, Charsets.ISO_8859_1).startsWith("Subproject commit ") } }
        val f = FileDiff(
            oldPath = if (status == FileStatus.ADDED) null else oldPath,
            newPath = if (status == FileStatus.DELETED) null else newPath,
            status = status,
            oldMode = oldMode,
            newMode = newMode,
            similarity = similarity,
            oldIndex = oldIndex,
            newIndex = newIndex,
            hunks = hunks,
            binary = binary,
            binaryWithoutData = binaryWithoutData,
            submodule = submodule,
        )
        return f to i
    }

    private fun parseTraditionalFile(lines: List<ByteArray>, start: Int, to: Int): Pair<FileDiff?, Int> {
        val minus = parsePath(text(lines[start]).substring(4), traditional = true)
        val plus = parsePath(text(lines[start + 1]).substring(4), traditional = true)
        var i = start + 2
        val hunks = ArrayList<Hunk>()
        while (i < to && text(lines[i]).startsWith("@@ ")) {
            val (h, next) = parseHunk(lines, i, to, lenient = true)
            hunks += h
            i = next
        }
        if (hunks.isEmpty()) return null to i
        val prefixed = minus != "/dev/null" && plus != "/dev/null" && hasPrefix(minus) && hasPrefix(plus) ||
            (minus == "/dev/null" && hasPrefix(plus) && plus.startsWith("b/")) ||
            (plus == "/dev/null" && hasPrefix(minus) && minus.startsWith("a/"))
        fun strip(p: String) = if (p != "/dev/null" && prefixed && hasPrefix(p)) p.substring(2) else p
        val o = strip(minus)
        val n = strip(plus)
        val status = when {
            o == "/dev/null" -> FileStatus.ADDED
            n == "/dev/null" -> FileStatus.DELETED
            else -> FileStatus.MODIFIED
        }
        // Traditional diffs cannot rename; "foo.c.orig" vs "foo.c" means foo.c.
        val target = if (status == FileStatus.MODIFIED) n else null
        val f = FileDiff(
            oldPath = when (status) { FileStatus.ADDED -> null; FileStatus.MODIFIED -> target; else -> o },
            newPath = when (status) { FileStatus.DELETED -> null; FileStatus.MODIFIED -> target; else -> n },
            status = status,
            oldMode = null, newMode = null, similarity = null, oldIndex = null, newIndex = null,
            hunks = hunks, binary = null, binaryWithoutData = false, submodule = false,
        )
        return f to i
    }

    /** "a/x" style: a single-character first component ("a/", "b/", or mnemonic "c/", "i/", "w/", "o/"). */
    private fun hasPrefix(p: String) = p.length > 2 && p[1] == '/' && p[0] != '/' && p[0] != '.'

    private fun splitGitHeader(h: String): Pair<String, String> {
        if (h.startsWith("\"")) {
            val end = closingQuote(h, 0)
            if (end > 0) {
                val first = unquote(h.substring(0, end + 1))
                val rest = h.substring(end + 1).trimStart(' ')
                val second = if (rest.startsWith("\"")) unquote(rest) else utf8(rest)
                return first to second
            }
        }
        val q = h.indexOf(" \"")
        if (q > 0) return utf8(h.substring(0, q)) to unquote(h.substring(q + 1))
        // Unquoted: both names are normally identical apart from the prefix.
        if (h.length % 2 == 1) {
            val mid = h.length / 2
            if (h[mid] == ' ') {
                val a = h.substring(0, mid)
                val b = h.substring(mid + 1)
                val sa = if (hasPrefix(a)) a.substring(2) else a
                val sb = if (hasPrefix(b)) b.substring(2) else b
                if (sa == sb) return utf8(a) to utf8(b)
            }
        }
        val sp = h.indexOf(" b/")
        if (sp > 0) return utf8(h.substring(0, sp)) to utf8(h.substring(sp + 1))
        val any = h.indexOf(' ')
        return if (any > 0) utf8(h.substring(0, any)) to utf8(h.substring(any + 1)) else utf8(h) to utf8(h)
    }

    private fun parsePath(raw: String, traditional: Boolean = false): String {
        val s = raw.trimEnd('\n', '\r')
        if (s.startsWith("\"")) return unquote(s)
        // Traditional diffs append a timestamp after a tab; git never writes a bare tab in a name.
        val tab = s.indexOf('\t')
        val name = if (tab >= 0) s.substring(0, tab) else if (traditional) s.trimEnd(' ') else s
        return utf8(name)
    }

    private fun closingQuote(s: String, open: Int): Int {
        var i = open + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

    /** C-style quoted name as written by git (octal escapes are raw UTF-8 bytes). */
    internal fun unquote(q: String): String {
        val end = closingQuote(q, 0)
        val inner = if (q.startsWith("\"") && end > 0) q.substring(1, end) else q
        val bos = ByteArrayOutputStream()
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c != '\\' || i + 1 >= inner.length) { bos.write(c.code and 0xff); i++; continue }
            val n = inner[i + 1]
            when (n) {
                'a' -> bos.write(7); 'b' -> bos.write(8); 't' -> bos.write(9); 'n' -> bos.write(10)
                'v' -> bos.write(11); 'f' -> bos.write(12); 'r' -> bos.write(13)
                '"' -> bos.write('"'.code); '\\' -> bos.write('\\'.code)
                in '0'..'7' -> {
                    var j = i + 1
                    var v = 0
                    while (j < inner.length && j < i + 4 && inner[j] in '0'..'7') { v = v * 8 + (inner[j] - '0'); j++ }
                    bos.write(v and 0xff)
                    i = j
                    continue
                }
                else -> bos.write(n.code and 0xff)
            }
            i += 2
        }
        return String(bos.toByteArray(), Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ hunks

    private fun parseHunk(lines: List<ByteArray>, start: Int, to: Int, lenient: Boolean): Pair<Hunk, Int> {
        val header = text(lines[start])
        // A header that does not parse must not drop its hunk silently: the file would be pushed unchanged.
        val m = HUNK.find(header) ?: throw PatchFormatException("bad hunk header", start + 1)
        val oldStart = m.groupValues[1].toInt()
        val oldCount = m.groupValues[2].ifEmpty { "1" }.toInt()
        val newStart = m.groupValues[3].toInt()
        val newCount = m.groupValues[4].ifEmpty { "1" }.toInt()
        val section = utf8(m.groupValues[5].trimBlanks())

        val (out, end) = readHunk(lines, start + 1, to, oldCount, newCount, strict = true)
            ?: if (lenient) readHunk(lines, start + 1, to, oldCount, newCount, strict = false)!!
            else throw PatchFormatException("hunk does not hold the lines its header counts", start + 1)
        var i = end
        if (i < to && lines[i].isNotEmpty() && lines[i][0] == '\\'.code.toByte()) {
            markNoNewline(out)
            i++
        }
        // Hunk lines right after the counts ran out: the counts are too small. git would ignore them.
        if (i < to && isStray(lines, i, to)) throw PatchFormatException("hunk line counts do not match", i + 1)
        val actualOld = out.count { it.kind != '+' }
        val actualNew = out.count { it.kind != '-' }
        return Hunk(oldStart, actualOld, newStart, actualNew, section, out, droppedBy100 = HUNK_100.find(header) == null) to i
    }

    /**
     * The lines of one hunk. Strict: exactly the header's counts, as git writes them; null when the
     * lines do not fit. Lenient, for hand-written patches whose counts are too large: the hunk may
     * end early where the next hunk, file, mail or signature begins, or at the end of the patch.
     * Anything else that is not a hunk line is corruption, unless only prose follows it.
     */
    private fun readHunk(lines: List<ByteArray>, from: Int, to: Int, oldCount: Int, newCount: Int, strict: Boolean): Pair<MutableList<HunkLine>, Int>? {
        val out = ArrayList<HunkLine>()
        var oldRem = oldCount
        var newRem = newCount
        var emptyRun = 0
        var i = from
        fun endEarly(): Pair<MutableList<HunkLine>, Int> {
            // Blank lines before the end separate the patch from what follows; they are not context.
            repeat(emptyRun) { out.removeAt(out.size - 1) }
            return out to i
        }
        while (oldRem > 0 || newRem > 0) {
            if (i >= to) return if (strict) null else endEarly()
            if (!strict && endsHunk(lines, i, to)) return endEarly()
            val raw = lines[i]
            val empty = (raw.size == 1 && raw[0] == '\n'.code.toByte()) ||
                (raw.size == 2 && raw[0] == '\r'.code.toByte() && raw[1] == '\n'.code.toByte())
            when {
                // An empty line is an empty context line whose leading space was stripped.
                empty -> { out += HunkLine(' ', raw.copyOf()); oldRem--; newRem--; emptyRun++ }
                raw[0] == ' '.code.toByte() -> { out += HunkLine(' ', raw.copyOfRange(1, raw.size)); oldRem--; newRem--; emptyRun = 0 }
                raw[0] == '-'.code.toByte() -> { out += HunkLine('-', raw.copyOfRange(1, raw.size)); oldRem--; emptyRun = 0 }
                raw[0] == '+'.code.toByte() -> { out += HunkLine('+', raw.copyOfRange(1, raw.size)); newRem--; emptyRun = 0 }
                raw[0] == '\\'.code.toByte() -> markNoNewline(out)
                strict -> return null
                // Prose after the patch (a chat message, a mail footer) is fine when no hunk lines follow it.
                hunkLinesFollow(lines, i, to) -> throw PatchFormatException("corrupt patch", i + 1)
                else -> return endEarly()
            }
            i++
        }
        return out to i
    }

    private fun isSignature(t: String) = t == "-- " || t == "--"

    private fun isFileHeader(lines: List<ByteArray>, i: Int, to: Int): Boolean =
        text(lines[i]).startsWith("--- ") && i + 1 < to && text(lines[i + 1]).startsWith("+++ ")

    /** Where a hunk with too large counts may end. */
    private fun endsHunk(lines: List<ByteArray>, i: Int, to: Int): Boolean {
        val t = text(lines[i])
        return t.startsWith("@@ ") || t.startsWith("diff ") || t.startsWith("Index: ") || MBOX_FROM.containsMatchIn(t) ||
            isSignature(t) || isFileHeader(lines, i, to)
    }

    private fun hunkLinesFollow(lines: List<ByteArray>, from: Int, to: Int): Boolean {
        var j = from
        while (j < to && !endsHunk(lines, j, to)) {
            val c = lines[j].firstOrNull()
            if (c == '+'.code.toByte() || c == '-'.code.toByte()) return true
            j++
        }
        return false
    }

    private fun isStray(lines: List<ByteArray>, i: Int, to: Int): Boolean {
        val t = text(lines[i])
        return when (t.firstOrNull()) {
            '+', ' ' -> true
            '-' -> !isSignature(t) && !isFileHeader(lines, i, to)
            else -> false
        }
    }

    private fun markNoNewline(out: MutableList<HunkLine>) {
        val last = out.lastOrNull() ?: return
        val b = last.bytes
        if (b.isNotEmpty() && b[b.size - 1] == '\n'.code.toByte()) {
            out[out.size - 1] = HunkLine(last.kind, b.copyOfRange(0, b.size - 1))
        }
    }

    // ------------------------------------------------------------------ binary

    private fun parseBinary(lines: List<ByteArray>, start: Int, to: Int): Pair<BinaryPatch, Int> {
        var i = start
        val (forward, next) = parseBinaryHunk(lines, i, to)
        i = next
        // The reverse hunk (for `git apply -R`) follows; skip it.
        if (i < to) {
            val t = text(lines[i])
            if (t.startsWith("literal ") || t.startsWith("delta ")) i = parseBinaryHunk(lines, i, to).second
        }
        return forward to i
    }

    private fun parseBinaryHunk(lines: List<ByteArray>, start: Int, to: Int): Pair<BinaryPatch, Int> {
        val head = text(lines[start])
        val kind = when {
            head.startsWith("literal ") -> BinaryKind.LITERAL
            head.startsWith("delta ") -> BinaryKind.DELTA
            else -> throw PatchFormatException("corrupt binary patch", start + 1)
        }
        val size = head.substringAfter(' ').trimBlanks().toLongOrNull() ?: throw PatchFormatException("corrupt binary patch", start + 1)
        val deflated = ByteArrayOutputStream()
        var i = start + 1
        while (i < to) {
            val t = text(lines[i])
            if (t.isEmpty()) { i++; break }
            deflated.write(Base85.decodeLine(t))
            i++
        }
        val data = inflate(deflated.toByteArray(), size)
        return BinaryPatch(kind, size, data) to i
    }

    private fun inflate(input: ByteArray, size: Long): ByteArray {
        if (size > Int.MAX_VALUE) throw PatchFormatException("binary patch too large")
        val inf = Inflater()
        try {
            inf.setInput(input)
            val out = ByteArray(size.toInt())
            var n = 0
            while (n < out.size && !inf.finished()) {
                val r = inf.inflate(out, n, out.size - n)
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += r
            }
            if (n != out.size) throw PatchFormatException("corrupt binary patch")
            return out
        } finally {
            inf.end()
        }
    }
}

/** git's base85 variant used by "GIT binary patch". */
internal object Base85 {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#\$%&()*+-;<=>?@^_`{|}~"
    private val DE = IntArray(256) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun decodeLine(line: String): ByteArray {
        if (line.isEmpty()) return ByteArray(0)
        val c = line[0]
        val len = when (c) {
            in 'A'..'Z' -> c - 'A' + 1
            in 'a'..'z' -> c - 'a' + 27
            else -> throw PatchFormatException("corrupt binary line")
        }
        val enc = line.substring(1)
        val groups = (len + 3) / 4
        if (enc.length < groups * 5) throw PatchFormatException("corrupt binary line")
        val out = ByteArray(len)
        var o = 0
        for (g in 0 until groups) {
            var acc = 0L
            for (k in 0 until 5) {
                val d = DE[enc[g * 5 + k].code and 0xff]
                if (d < 0) throw PatchFormatException("corrupt binary line")
                acc = acc * 85 + d
            }
            if (acc > 0xffffffffL) throw PatchFormatException("corrupt binary line")
            for (k in 3 downTo 0) {
                if (o < len) out[o++] = ((acc shr (k * 8)) and 0xff).toByte()
            }
        }
        return out
    }
}
