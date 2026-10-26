package dev.mokouliszt.githubpatches.patch

/**
 * A parsed patch file. Plain diffs become a single commit without metadata; `git format-patch`
 * output (one or many mails in one file) becomes one commit per mail.
 */
class PatchSet(val commits: List<PatchCommit>) {
    val files: List<FileDiff> get() = commits.flatMap { it.files }
    val additions: Int get() = commits.sumOf { c -> c.files.sumOf { it.additions } }
    val deletions: Int get() = commits.sumOf { c -> c.files.sumOf { it.deletions } }
    val hasMetadata: Boolean get() = commits.any { it.hasMetadata }

    /**
     * What 1.0.0 left out when it pushed this patch: per file, the hunks from the first one it could
     * not read, to be applied where the rest already landed (a renamed file is edited at its new path).
     */
    fun droppedBy100(): PatchSet = PatchSet(commits.mapNotNull { c ->
        val files = c.files.filter { it.droppedBy100 > 0 }.map { f ->
            val left = f.hunks.takeLast(f.droppedBy100)
            // The hunks before them are in the file now, so they start where the full patch put them.
            val shift = left.first().newStart - left.first().oldStart
            FileDiff(
                oldPath = f.newPath, newPath = f.newPath, status = FileStatus.MODIFIED, oldMode = null, newMode = null,
                similarity = null, oldIndex = f.oldIndex, newIndex = f.newIndex,
                hunks = left.map { Hunk(it.oldStart + shift, it.oldCount, it.newStart, it.newCount, it.section, it.lines) },
                binary = null, binaryWithoutData = false, submodule = false,
            )
        }
        if (files.isEmpty()) null else PatchCommit(c.sha, c.authorName, c.authorEmail, c.date, c.subject, c.body, files)
    })
}

class PatchCommit(
    /** Commit id from the mbox "From <sha>" line, when present. */
    val sha: String?,
    val authorName: String?,
    val authorEmail: String?,
    /** ISO-8601 with offset, e.g. 2026-10-10T12:00:00+09:00. */
    val date: String?,
    /** Subject with "[PATCH n/m]" style prefixes removed. Empty for plain diffs. */
    val subject: String,
    val body: String,
    val files: List<FileDiff>,
) {
    val hasMetadata: Boolean get() = subject.isNotEmpty() || authorName != null
    val message: String
        get() = if (body.isBlank()) subject else "$subject\n\n$body"
}

enum class FileStatus { MODIFIED, ADDED, DELETED, RENAMED, COPIED }

class FileDiff(
    /** Path before the change; null for added files. */
    val oldPath: String?,
    /** Path after the change; null for deleted files. */
    val newPath: String?,
    val status: FileStatus,
    val oldMode: String?,
    val newMode: String?,
    val similarity: Int?,
    val oldIndex: String?,
    val newIndex: String?,
    val hunks: List<Hunk>,
    /** Forward hunk of a "GIT binary patch". */
    val binary: BinaryPatch?,
    /** "Binary files a/x and b/x differ": no data to apply. */
    val binaryWithoutData: Boolean,
    val submodule: Boolean,
) {
    val path: String get() = newPath ?: oldPath ?: ""
    val additions: Int get() = hunks.sumOf { h -> h.lines.count { it.kind == '+' } }
    val deletions: Int get() = hunks.sumOf { h -> h.lines.count { it.kind == '-' } }
    val isBinary: Boolean get() = binary != null || binaryWithoutData
    /** Hunks that 1.0.0 left out of this file: the first one it could not read and all after it. */
    val droppedBy100: Int get() = hunks.indexOfFirst { it.droppedBy100 }.let { if (it < 0) 0 else hunks.size - it }
}

class Hunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    /** Text after the second "@@", usually the enclosing function. */
    val section: String,
    val lines: List<HunkLine>,
    /** 1.0.0 could not read this hunk's header and skipped it together with the rest of the file. */
    val droppedBy100: Boolean = false,
)

/**
 * One line of a hunk. [bytes] is the raw content without the leading marker and *with* its line
 * terminator, except for a line followed by "\ No newline at end of file".
 */
class HunkLine(val kind: Char, val bytes: ByteArray)

enum class BinaryKind { LITERAL, DELTA }

class BinaryPatch(val kind: BinaryKind, val size: Long, val data: ByteArray)

/** [line] is 1-based, 0 when the problem is not tied to one line. */
open class PatchFormatException(message: String, val line: Int = 0) : Exception(message)

/** The diff does not hold the lines its own diffstat counts. */
class PatchCountException(message: String) : PatchFormatException(message)
