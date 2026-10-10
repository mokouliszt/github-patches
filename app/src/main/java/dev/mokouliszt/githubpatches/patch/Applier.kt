package dev.mokouliszt.githubpatches.patch

import java.security.MessageDigest

class ApplyException(val hunk: Int, message: String) : Exception(message)

/**
 * @property offsetHunks hunks that matched away from the line numbers in the patch
 * @property looseHunks hunks that only matched when trailing whitespace / CR was ignored
 */
class TextResult(val content: ByteArray, val offsetHunks: Int, val looseHunks: Int)

/**
 * Applies hunks the way `git apply` does: context must match exactly, hunks are searched
 * outward from their recorded position, and hunks at the start / end of a file must match there.
 * Two fallbacks follow, each reported to the caller: ignoring the recorded start / end anchor,
 * then ignoring trailing whitespace and CR (common when a patch went through a chat or an editor).
 * There is no fuzz: context lines are never dropped.
 */
object Applier {

    private const val LF = '\n'.code.toByte()
    private const val CR = '\r'.code.toByte()

    fun applyText(base: ByteArray?, hunks: List<Hunk>): TextResult {
        val img: MutableList<ByteArray> = if (base == null || base.isEmpty()) ArrayList() else splitLines(base).toMutableList()
        val crlf = isCrlf(img)
        // A -U0 patch has no context anywhere; anchoring to the file start / end would be wrong.
        val unidiffZero = hunks.isNotEmpty() && hunks.all { leading(it) == 0 && trailing(it) == 0 } &&
            hunks.any { h -> h.lines.any { it.kind == ' ' || it.kind == '-' } }
        var offset = 0
        var lastEnd = 0
        var offsetHunks = 0
        var looseHunks = 0
        var anyLoose = false

        for ((idx, h) in hunks.withIndex()) {
            val pre = h.lines.filter { it.kind != '+' }.map { it.bytes }
            val expected = if (pre.isEmpty()) h.oldStart + offset else h.oldStart - 1 + offset
            val matchBegin = !unidiffZero && (h.oldStart == 0 || (h.oldStart == 1 && pre.isNotEmpty()))
            val matchEnd = !unidiffZero && pre.isNotEmpty() && trailing(h) == 0

            var loose = false
            var anchored = true
            var pos = find(img, pre, expected, lastEnd, matchBegin, matchEnd, false)
            if (pos < 0 && (matchBegin || matchEnd)) {
                pos = find(img, pre, expected, lastEnd, false, false, false); anchored = false
            }
            if (pos < 0) {
                pos = find(img, pre, expected, lastEnd, matchBegin, matchEnd, true); loose = true; anchored = true
                if (pos < 0 && (matchBegin || matchEnd)) {
                    pos = find(img, pre, expected, lastEnd, false, false, true); anchored = false
                }
            }
            if (pos < 0) throw ApplyException(idx + 1, "hunk ${idx + 1} does not match")

            val replacement = ArrayList<ByteArray>()
            var k = pos
            for (line in h.lines) {
                when (line.kind) {
                    ' ' -> { replacement += img[k]; k++ }
                    '-' -> k++
                    else -> replacement += if (loose) adaptEol(line.bytes, crlf) else line.bytes
                }
            }
            for (r in 0 until pre.size) img.removeAt(pos)
            img.addAll(pos, replacement)

            if (pos != expected || !anchored) offsetHunks++
            if (loose) { looseHunks++; anyLoose = true }
            offset += (pos - expected) + (replacement.size - pre.size)
            lastEnd = pos + replacement.size
        }

        if (anyLoose) {
            // A context line that lost its newline in a loose match must not glue onto the next one.
            val eol = if (crlf) byteArrayOf(CR, LF) else byteArrayOf(LF)
            for (i in 0 until img.size - 1) {
                val l = img[i]
                if (l.isEmpty() || l[l.size - 1] != LF) img[i] = l + eol
            }
        }
        return TextResult(join(img), offsetHunks, looseHunks)
    }

    fun applyBinary(base: ByteArray?, patch: BinaryPatch): ByteArray = when (patch.kind) {
        BinaryKind.LITERAL -> patch.data
        BinaryKind.DELTA -> GitDelta.apply(base ?: throw ApplyException(0, "delta without base"), patch.data)
    }

    // ------------------------------------------------------------------ matching

    private fun find(
        img: List<ByteArray>, pre: List<ByteArray>, expected: Int, lastEnd: Int,
        matchBegin: Boolean, matchEnd: Boolean, loose: Boolean,
    ): Int {
        val maxPos = img.size - pre.size
        if (maxPos < lastEnd) return -1
        if (pre.isEmpty()) {
            if (matchBegin) return if (lastEnd == 0) 0 else -1
            return expected.coerceIn(lastEnd, img.size)
        }
        fun at(p: Int): Boolean {
            for (i in pre.indices) if (!same(img[p + i], pre[i], loose)) return false
            return true
        }
        if (matchBegin && matchEnd) return if (lastEnd == 0 && pre.size == img.size && at(0)) 0 else -1
        if (matchBegin) return if (lastEnd == 0 && at(0)) 0 else -1
        if (matchEnd) return if (at(maxPos)) maxPos else -1
        val start = expected.coerceIn(lastEnd, maxPos)
        var d = 0
        while (true) {
            val f = start + d
            val b = start - d
            if (f > maxPos && b < lastEnd) return -1
            if (f <= maxPos && at(f)) return f
            if (d > 0 && b >= lastEnd && at(b)) return b
            d++
        }
    }

    private fun same(a: ByteArray, b: ByteArray, loose: Boolean): Boolean {
        if (!loose) return a.contentEquals(b)
        val ea = trimmedEnd(a)
        val eb = trimmedEnd(b)
        if (ea != eb) return false
        for (i in 0 until ea) if (a[i] != b[i]) return false
        return true
    }

    private fun trimmedEnd(a: ByteArray): Int {
        var e = a.size
        while (e > 0) {
            val c = a[e - 1]
            if (c == LF || c == CR || c == ' '.code.toByte() || c == '\t'.code.toByte()) e-- else break
        }
        return e
    }

    private fun adaptEol(b: ByteArray, crlf: Boolean): ByteArray {
        if (b.isEmpty() || b[b.size - 1] != LF) return b
        val hasCr = b.size >= 2 && b[b.size - 2] == CR
        return when {
            crlf && !hasCr -> b.copyOfRange(0, b.size - 1) + byteArrayOf(CR, LF)
            !crlf && hasCr -> b.copyOfRange(0, b.size - 2) + byteArrayOf(LF)
            else -> b
        }
    }

    private fun leading(h: Hunk) = h.lines.takeWhile { it.kind == ' ' }.size
    private fun trailing(h: Hunk) = h.lines.takeLastWhile { it.kind == ' ' }.size

    private fun isCrlf(img: List<ByteArray>): Boolean {
        var crlf = 0
        var lf = 0
        for (l in img) {
            if (l.isEmpty() || l[l.size - 1] != LF) continue
            if (l.size >= 2 && l[l.size - 2] == CR) crlf++ else lf++
        }
        return crlf > lf
    }

    fun splitLines(b: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var start = 0
        for (i in b.indices) {
            if (b[i] == LF) { out += b.copyOfRange(start, i + 1); start = i + 1 }
        }
        if (start < b.size) out += b.copyOfRange(start, b.size)
        return out
    }

    private fun join(lines: List<ByteArray>): ByteArray {
        val out = ByteArray(lines.sumOf { it.size })
        var o = 0
        for (l in lines) { System.arraycopy(l, 0, out, o, l.size); o += l.size }
        return out
    }
}

/** git's delta format (copy / insert instructions against a base object). */
object GitDelta {
    fun apply(base: ByteArray, delta: ByteArray): ByteArray {
        var p = 0
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                if (p >= delta.size) throw ApplyException(0, "corrupt delta")
                val b = delta[p++].toInt() and 0xff
                r = r or ((b and 0x7f).toLong() shl shift)
                shift += 7
                if (b and 0x80 == 0) return r
            }
        }
        val srcSize = varint()
        if (srcSize != base.size.toLong()) throw ApplyException(0, "delta base size mismatch")
        val dstSize = varint()
        if (dstSize > Int.MAX_VALUE) throw ApplyException(0, "delta too large")
        val out = ByteArray(dstSize.toInt())
        var o = 0
        while (p < delta.size) {
            val cmd = delta[p++].toInt() and 0xff
            if (cmd and 0x80 != 0) {
                var off = 0L
                var size = 0L
                if (cmd and 0x01 != 0) off = off or (delta[p++].toLong() and 0xff)
                if (cmd and 0x02 != 0) off = off or ((delta[p++].toLong() and 0xff) shl 8)
                if (cmd and 0x04 != 0) off = off or ((delta[p++].toLong() and 0xff) shl 16)
                if (cmd and 0x08 != 0) off = off or ((delta[p++].toLong() and 0xff) shl 24)
                if (cmd and 0x10 != 0) size = size or (delta[p++].toLong() and 0xff)
                if (cmd and 0x20 != 0) size = size or ((delta[p++].toLong() and 0xff) shl 8)
                if (cmd and 0x40 != 0) size = size or ((delta[p++].toLong() and 0xff) shl 16)
                if (size == 0L) size = 0x10000
                if (off + size > base.size || o + size > out.size) throw ApplyException(0, "corrupt delta")
                System.arraycopy(base, off.toInt(), out, o, size.toInt())
                o += size.toInt()
            } else if (cmd != 0) {
                if (p + cmd > delta.size || o + cmd > out.size) throw ApplyException(0, "corrupt delta")
                System.arraycopy(delta, p, out, o, cmd)
                p += cmd
                o += cmd
            } else {
                throw ApplyException(0, "corrupt delta")
            }
        }
        if (o != out.size) throw ApplyException(0, "corrupt delta")
        return out
    }
}

object GitHash {
    /** Object id of a blob, as git and GitHub compute it. */
    fun blob(content: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${content.size}\u0000".toByteArray(Charsets.US_ASCII))
        md.update(content)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
