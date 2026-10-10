package dev.mokouliszt.githubpatches.patch

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.Inflater

/**
 * Parses `git format-patch` mails (one or many in one file), `git diff` output and traditional
 * unified diffs. Everything is byte based: file contents are never decoded, so any encoding
 * (UTF-8, Shift_JIS, CRLF, files without a final newline) survives unchanged.
 */
object PatchParser {

    private val MBOX_FROM = Regex("^From ([0-9a-f]{40}|[0-9a-f]{64}) ")
    private val HUNK = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$")
    private val INDEX = Regex("^index ([0-9a-fA-F]+)\\.\\.([0-9a-fA-F]+)(?: ([0-7]{6}))?")
    private val ENCODED_WORD = Regex("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?]*)\\?=")
    private val LEADING_BRACKETS = Regex("^\\s*(?:(?:re|aw|fwd?)\\s*:\\s*|\\[[^\\]]*\\]\\s*)+", RegexOption.IGNORE_CASE)

    fun parse(input: ByteArray): PatchSet {
        val lines = normalize(splitLines(input))
        val starts = lines.indices.filter { MBOX_FROM.containsMatchIn(text(lines[it])) }
        val commits = ArrayList<PatchCommit>()
        if (starts.isEmpty()) {
            val first = lines.indexOfFirst { text(it).isNotBlank() }
            val firstText = if (first >= 0) text(lines[first]) else ""
            if (firstText.startsWith("From: ") || firstText.startsWith("Subject: ") || firstText.startsWith("Date: ")) {
                commits += parseMail(lines, first, lines.size)
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
                commits += parseMail(lines, s, end)
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

    private fun utf8(latin1: String): String = String(latin1.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)

    // ------------------------------------------------------------------ mails

    private fun parseMail(lines: List<ByteArray>, from: Int, to: Int): PatchCommit {
        var i = from
        var sha: String? = null
        MBOX_FROM.find(text(lines[i]))?.let { sha = it.groupValues[1]; i++ }

        val headers = LinkedHashMap<String, String>()
        var current: String? = null
        while (i < to) {
            val t = text(lines[i])
            if (t.isEmpty()) { i++; break }
            if ((t.startsWith(" ") || t.startsWith("\t")) && current != null) {
                headers[current] = headers[current] + " " + t.trim()
            } else {
                val colon = t.indexOf(':')
                if (colon <= 0) break // not a header block after all
                current = t.substring(0, colon).trim().lowercase()
                headers[current] = t.substring(colon + 1).trim()
            }
            i++
        }

        // Body ends at the "---" line before the diffstat, or where the diff itself begins.
        val body = ArrayList<String>()
        var diffStart = to
        var j = i
        while (j < to) {
            val t = text(lines[j])
            if (t == "---") { diffStart = j + 1; break }
            if (isDiffStart(lines, j, to)) { diffStart = j; break }
            body += utf8(lines[j].latin1().trimEnd('\n', '\r'))
            j++
        }
        // In-body headers ("From: ...", "Subject: ..." followed by a blank line) override the mail's.
        var bodyStart = 0
        while (bodyStart < body.size) {
            val t = body[bodyStart]
            val m = Regex("^(From|Subject|Date): (.*)$").find(t) ?: break
            headers[m.groupValues[1].lowercase()] = m.groupValues[2]
            bodyStart++
        }
        if (bodyStart > 0 && bodyStart < body.size && body[bodyStart].isBlank()) bodyStart++

        val (name, email) = parseAddress(headers["from"]?.let { decodeHeader(it) })
        val subject = cleanSubject(headers["subject"]?.let { decodeHeader(it) } ?: "")
        val message = body.drop(bodyStart).joinToString("\n").trim('\n').trimEnd()
        val files = parseFiles(lines, diffStart, to)
        return PatchCommit(sha, name, email, parseDate(headers["date"]), subject, message, files)
    }

    private fun isDiffStart(lines: List<ByteArray>, i: Int, to: Int): Boolean {
        val t = text(lines[i])
        if (t.startsWith("diff --git ")) return true
        return t.startsWith("--- ") && i + 2 < to && text(lines[i + 1]).startsWith("+++ ") && text(lines[i + 2]).startsWith("@@ ")
    }

    internal fun cleanSubject(s: String): String = s.replace(LEADING_BRACKETS, "").trim()

    /** RFC 2047 encoded words; raw 8-bit header bytes are taken as UTF-8. */
    internal fun decodeHeader(raw: String): String {
        val s = utf8(raw)
        val out = StringBuilder()
        var last = 0
        var prevWasWord = false
        for (m in ENCODED_WORD.findAll(s)) {
            val gap = s.substring(last, m.range.first)
            if (!(prevWasWord && gap.isBlank())) out.append(gap)
            val cs = runCatching { Charset.forName(m.groupValues[1]) }.getOrDefault(Charsets.UTF_8)
            val payload = m.groupValues[3]
            val bytes = if (m.groupValues[2].equals("b", true)) {
                runCatching { java.util.Base64.getDecoder().decode(payload) }.getOrDefault(ByteArray(0))
            } else {
                decodeQ(payload)
            }
            out.append(String(bytes, cs))
            last = m.range.last + 1
            prevWasWord = true
        }
        out.append(s.substring(last))
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
                else -> bos.write(c.code)
            }
            i++
        }
        return bos.toByteArray()
    }

    private fun parseAddress(v: String?): Pair<String?, String?> {
        if (v.isNullOrBlank()) return null to null
        val m = Regex("^(.*?)\\s*<([^>]*)>\\s*$").find(v)
        if (m != null) {
            var name = m.groupValues[1].trim()
            if (name.length >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            }
            val email = m.groupValues[2].trim()
            return (name.ifBlank { email }) to email
        }
        val p = Regex("^(\\S+@\\S+)\\s*\\((.*)\\)$").find(v.trim())
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
                t.startsWith("old mode ") -> oldMode = t.substring(9).trim()
                t.startsWith("new mode ") -> newMode = t.substring(9).trim()
                t.startsWith("deleted file mode ") -> { deleted = true; oldMode = t.substring(18).trim() }
                t.startsWith("new file mode ") -> { added = true; newMode = t.substring(14).trim() }
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

        val hunks = ArrayList<Hunk>()
        while (i < to && text(lines[i]).startsWith("@@ ")) {
            val (h, next) = parseHunk(lines, i, to) ?: break
            hunks += h
            i = next
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
            val (h, next) = parseHunk(lines, i, to) ?: break
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
                val rest = h.substring(end + 1).trimStart()
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
        val name = if (tab >= 0) s.substring(0, tab) else if (traditional) s.trimEnd() else s
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

    private fun parseHunk(lines: List<ByteArray>, start: Int, to: Int): Pair<Hunk, Int>? {
        val m = HUNK.find(text(lines[start])) ?: return null
        val oldStart = m.groupValues[1].toInt()
        val oldCount = m.groupValues[2].ifEmpty { "1" }.toInt()
        val newStart = m.groupValues[3].toInt()
        val newCount = m.groupValues[4].ifEmpty { "1" }.toInt()
        val section = utf8(m.groupValues[5].trim())

        val out = ArrayList<HunkLine>()
        var oldRem = oldCount
        var newRem = newCount
        var i = start + 1
        while (i < to && (oldRem > 0 || newRem > 0)) {
            val raw = lines[i]
            if (raw.isEmpty()) break
            // With wrong counts, do not swallow the next file's header or a mail signature.
            if (looksLikeBoundary(lines, i, to)) break
            when (raw[0].toInt().toChar()) {
                ' ' -> { out += HunkLine(' ', raw.copyOfRange(1, raw.size)); oldRem--; newRem-- }
                '-' -> { out += HunkLine('-', raw.copyOfRange(1, raw.size)); oldRem-- }
                '+' -> { out += HunkLine('+', raw.copyOfRange(1, raw.size)); newRem-- }
                '\\' -> markNoNewline(out)
                // An empty line is an empty context line whose leading space was stripped.
                '\n' -> { out += HunkLine(' ', raw.copyOf()); oldRem--; newRem-- }
                '\r' -> if (raw.size == 2 && raw[1] == '\n'.code.toByte()) {
                    out += HunkLine(' ', raw.copyOf()); oldRem--; newRem--
                } else break
                else -> break
            }
            i++
        }
        if (i < to && lines[i].isNotEmpty() && lines[i][0] == '\\'.code.toByte()) {
            markNoNewline(out)
            i++
        }
        // Hand-written patches often carry wrong counts: keep the lines that are really there.
        val actualOld = out.count { it.kind != '+' }
        val actualNew = out.count { it.kind != '-' }
        return Hunk(oldStart, actualOld, newStart, actualNew, section, out) to i
    }

    private val VERSION_LINE = Regex("^\\d+(\\.\\d+)+")

    private fun looksLikeBoundary(lines: List<ByteArray>, i: Int, to: Int): Boolean {
        val t = text(lines[i])
        val next = if (i + 1 < to) text(lines[i + 1]) else null
        if (t.startsWith("--- ") && next != null && next.startsWith("+++ ")) return true
        if (t == "-- " && (next == null || VERSION_LINE.containsMatchIn(next))) return true
        return false
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
            else -> throw PatchFormatException("corrupt binary patch")
        }
        val size = head.substringAfter(' ').trim().toLongOrNull() ?: throw PatchFormatException("corrupt binary patch")
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
