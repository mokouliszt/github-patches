package dev.mokouliszt.githubpatches.patch

/** The few Git Data API operations needed to commit without a local clone. */
interface GitRemote {
    /** Commit id the branch points to, or null when the branch does not exist. */
    suspend fun branchHead(branch: String): String?
    suspend fun commitTree(commit: String): String
    /** Direct children of a tree (non-recursive). */
    suspend fun tree(sha: String): List<TreeItem>
    suspend fun blob(sha: String): ByteArray
    suspend fun createBlob(content: ByteArray): String
    suspend fun createTree(baseTree: String, changes: List<TreeChange>): String
    suspend fun createCommit(message: String, tree: String, parents: List<String>, author: Author?): String
    /** Fast-forward only. Throws [RemoteException] with [RemoteException.Kind.NOT_FAST_FORWARD]. */
    suspend fun updateBranch(branch: String, commit: String)
    suspend fun createBranch(branch: String, commit: String)
}

class TreeItem(val name: String, val mode: String, val type: String, val sha: String)

/** One entry for "create a tree": [sha] null deletes the path. */
class TreeChange(val path: String, val mode: String, val sha: String?)

class Author(val name: String, val email: String, val date: String?)

class RemoteException(val kind: Kind, message: String, val status: Int = 0) : Exception(message) {
    enum class Kind { NOT_FOUND, NOT_FAST_FORWARD, ALREADY_EXISTS, FORBIDDEN, WORKFLOW_SCOPE, EMPTY_REPO, AUTH, RATE_LIMIT, NETWORK, OTHER }
}

enum class Outcome { OK, OFFSET, LOOSE, FAIL }

enum class FailReason { MISMATCH, MISSING, EXISTS, NOT_EMPTY, BINARY_NO_DATA, BINARY_BASE, SUBMODULE, CORRUPT }

class FileCheck(
    val commit: Int,
    val path: String,
    val oldPath: String?,
    val status: FileStatus,
    val outcome: Outcome,
    val hunk: Int = 0,
    val reason: FailReason? = null,
)

class CheckReport(val head: String, val files: List<FileCheck>) {
    val ok: Boolean get() = files.none { it.outcome == Outcome.FAIL }
}

class PushTarget(
    /** Branch to push to (existing) or to create. */
    val branch: String,
    val create: Boolean,
    /** Branch the new one starts from (only with [create]). */
    val base: String? = null,
)

/** [parent] is the commit the new ones were built on (the old branch tip, or the base branch tip). */
class PushResult(val branch: String, val head: String, val commits: List<String>, val created: Boolean, val base: String?, val parent: String)

class PushException(val kind: Kind, val detail: String, val report: CheckReport? = null) : Exception(detail) {
    enum class Kind { BRANCH_MISSING, BRANCH_EXISTS, DOES_NOT_APPLY, INTEGRITY, BRANCH_MOVED }
}

/**
 * Applies a [PatchSet] on top of a branch tip held only in GitHub and turns it into commits,
 * like `git am` would, then moves the branch (fast-forward only) or creates a new one.
 */
class Pusher(private val remote: GitRemote) {

    suspend fun check(patch: PatchSet, target: PushTarget): CheckReport {
        val head = resolveHead(target)
        val ws = Workspace(remote, remote.commitTree(head))
        val checks = ArrayList<FileCheck>()
        patch.commits.forEachIndexed { i, c -> c.files.forEach { checks += ws.apply(i, it) } }
        return CheckReport(head, checks)
    }

    /**
     * @param messages one message per commit (or a single one when [squash])
     * @param keepAuthor keep the patch's author and date (committer is always the signed-in user)
     * @param checkedHead the tip the user's check ran on. If the branch has moved since and the patch
     *   no longer applies cleanly there, nothing is pushed and the new report is returned instead.
     */
    suspend fun push(
        patch: PatchSet,
        target: PushTarget,
        messages: List<String>,
        squash: Boolean,
        keepAuthor: Boolean,
        checkedHead: String? = null,
    ): PushResult {
        repeat(2) { attempt ->
            try {
                return pushOnce(patch, target, messages, squash, keepAuthor, checkedHead)
            } catch (e: RemoteException) {
                // The branch moved between reading and updating it: build again on the new tip.
                if (e.kind != RemoteException.Kind.NOT_FAST_FORWARD || attempt == 1 || target.create) throw e
            }
        }
        error("unreachable")
    }

    private suspend fun resolveHead(target: PushTarget): String {
        val from = if (target.create) target.base ?: throw PushException(PushException.Kind.BRANCH_MISSING, "") else target.branch
        if (target.create && remote.branchHead(target.branch) != null) {
            throw PushException(PushException.Kind.BRANCH_EXISTS, target.branch)
        }
        return remote.branchHead(from) ?: throw PushException(PushException.Kind.BRANCH_MISSING, from)
    }

    private suspend fun pushOnce(
        patch: PatchSet, target: PushTarget, messages: List<String>, squash: Boolean, keepAuthor: Boolean, checkedHead: String?,
    ): PushResult {
        val head = resolveHead(target)
        var parentTree = remote.commitTree(head)
        val ws = Workspace(remote, parentTree)
        val groups: List<List<Int>> = if (squash) listOf(patch.commits.indices.toList()) else patch.commits.indices.map { listOf(it) }
        var parent = head
        val created = ArrayList<String>()
        val failures = ArrayList<FileCheck>()
        val results = ArrayList<FileCheck>()

        for ((g, members) in groups.withIndex()) {
            ws.beginGroup()
            for (ci in members) {
                for (f in patch.commits[ci].files) {
                    val r = ws.apply(ci, f)
                    results += r
                    if (r.outcome == Outcome.FAIL) failures += r
                }
            }
            if (failures.isNotEmpty()) {
                throw PushException(PushException.Kind.DOES_NOT_APPLY, "", CheckReport(head, failures))
            }
            val changes = ws.groupChanges()
            val tree = if (changes.isEmpty()) parentTree else remote.createTree(parentTree, changes)
            val first = patch.commits[members.first()]
            val message = messages.getOrNull(g)?.takeIf { it.isNotBlank() } ?: first.message.ifBlank { "Apply patch" }
            val author = if (keepAuthor && first.authorName != null && first.authorEmail != null) {
                Author(first.authorName, first.authorEmail, first.date)
            } else null
            val commit = remote.createCommit(message.trimEnd() + "\n", tree, listOf(parent), author)
            created += commit
            parent = commit
            parentTree = tree
        }

        // The user approved a check made on another tip: push only what applies cleanly without asking again.
        if (checkedHead != null && head != checkedHead && results.any { it.outcome != Outcome.OK }) {
            throw PushException(PushException.Kind.BRANCH_MOVED, "", CheckReport(head, results))
        }
        if (target.create) remote.createBranch(target.branch, parent) else remote.updateBranch(target.branch, parent)
        return PushResult(target.branch, parent, created, target.create, target.base, head)
    }
}

/**
 * The branch tip's tree plus the changes made so far, read lazily from GitHub. Trees and blobs are
 * addressed by content, so caching them is always safe.
 */
internal class Workspace(private val remote: GitRemote, private val root: String) {

    class State(val mode: String, val sha: String, private var bytes: ByteArray?) {
        fun contentOrNull() = bytes
        fun setContent(b: ByteArray) { bytes = b }
    }

    private sealed class Lookup {
        class File(val state: State) : Lookup()
        object Missing : Lookup()
        object Blocked : Lookup()   // a directory, a submodule, or a file where a directory is needed
    }

    private val trees = HashMap<String, Map<String, TreeItem>>()
    private val blobs = HashMap<String, ByteArray>()
    private val overlay = LinkedHashMap<String, State?>()
    private val baseShas = HashSet<String>()
    private val uploaded = HashSet<String>()
    private var groupStart = LinkedHashMap<String, State?>()

    fun beginGroup() { groupStart = LinkedHashMap() }

    private suspend fun listing(sha: String): Map<String, TreeItem> =
        trees[sha] ?: remote.tree(sha).associateBy { it.name }.also { trees[sha] = it }

    private suspend fun lookup(path: String): Lookup {
        if (overlay.containsKey(path)) return overlay[path]?.let { Lookup.File(it) } ?: Lookup.Missing
        // A parent directory replaced by a file in this patch, or removed together with its files.
        var tree = root
        val parts = path.split('/')
        for ((i, part) in parts.withIndex()) {
            val item = listing(tree)[part] ?: return Lookup.Missing
            if (i == parts.lastIndex) {
                return when (item.type) {
                    "blob" -> { baseShas += item.sha; Lookup.File(State(item.mode, item.sha, null)) }
                    else -> Lookup.Blocked
                }
            }
            if (item.type != "tree") {
                val prefix = parts.subList(0, i + 1).joinToString("/")
                return if (overlay.containsKey(prefix) && overlay[prefix] == null) Lookup.Missing else Lookup.Blocked
            }
            tree = item.sha
        }
        return Lookup.Missing
    }

    private suspend fun content(s: State): ByteArray {
        s.contentOrNull()?.let { return it }
        val b = blobs[s.sha] ?: remote.blob(s.sha).also {
            // Content that changed on the way down would be edited and pushed as if it were the file.
            if (GitHash.blob(it) != s.sha) throw PushException(PushException.Kind.INTEGRITY, "blob ${s.sha}")
            blobs[s.sha] = it
        }
        s.setContent(b)
        return b
    }

    private suspend fun remember(path: String) {
        if (groupStart.containsKey(path)) return
        groupStart[path] = (lookup(path) as? Lookup.File)?.state
    }

    private suspend fun write(path: String, bytes: ByteArray, mode: String) {
        remember(path)
        overlay[path] = State(mode, GitHash.blob(bytes), bytes)
    }

    private suspend fun delete(path: String) {
        remember(path)
        overlay[path] = null
    }

    suspend fun apply(commit: Int, f: FileDiff): FileCheck {
        fun result(o: Outcome, hunk: Int = 0, reason: FailReason? = null) =
            FileCheck(commit, f.path, f.oldPath, f.status, o, hunk, reason)
        if (f.submodule) return result(Outcome.FAIL, reason = FailReason.SUBMODULE)
        if (f.binaryWithoutData) return result(Outcome.FAIL, reason = FailReason.BINARY_NO_DATA)

        return try {
            when (f.status) {
                FileStatus.ADDED -> {
                    val path = f.newPath!!
                    if (lookup(path) !is Lookup.Missing) return result(Outcome.FAIL, reason = FailReason.EXISTS)
                    val (bytes, o) = produce(null, f)
                    if (!matchesNewIndex(f, bytes)) return result(Outcome.FAIL, reason = FailReason.CORRUPT)
                    write(path, bytes, normalMode(f.newMode) ?: "100644")
                    result(o)
                }
                FileStatus.DELETED -> {
                    val path = f.oldPath!!
                    val cur = (lookup(path) as? Lookup.File)?.state ?: return result(Outcome.FAIL, reason = FailReason.MISSING)
                    var o = Outcome.OK
                    if (f.hunks.isNotEmpty()) {
                        val (bytes, oo) = produce(content(cur), f)
                        if (bytes.isNotEmpty()) return result(Outcome.FAIL, reason = FailReason.NOT_EMPTY)
                        o = oo
                    } else if (f.binary != null && f.oldIndex != null && !shaMatches(cur.sha, f.oldIndex)) {
                        return result(Outcome.FAIL, reason = FailReason.BINARY_BASE)
                    }
                    delete(path)
                    result(o)
                }
                FileStatus.MODIFIED, FileStatus.RENAMED, FileStatus.COPIED -> {
                    val from = f.oldPath!!
                    val to = f.newPath!!
                    val cur = (lookup(from) as? Lookup.File)?.state ?: return result(Outcome.FAIL, reason = FailReason.MISSING)
                    if (to != from && lookup(to) !is Lookup.Missing) return result(Outcome.FAIL, reason = FailReason.EXISTS)
                    val mode = normalMode(f.newMode) ?: cur.mode
                    if (f.hunks.isEmpty() && f.binary == null) {
                        // Pure rename / copy / mode change: the object itself is reused, never downloaded.
                        if (f.status == FileStatus.RENAMED && to != from) delete(from)
                        remember(to)
                        overlay[to] = State(mode, cur.sha, cur.contentOrNull())
                        return result(Outcome.OK)
                    }
                    val (bytes, o) = produce(content(cur), f, cur.sha)
                    // Applied cleanly to the exact file the patch was made from, so the result must be
                    // the file it was made into. A mismatch means the patch is not what its index line says.
                    if (o == Outcome.OK && f.oldIndex != null && shaMatches(cur.sha, f.oldIndex) && !matchesNewIndex(f, bytes)) {
                        return result(Outcome.FAIL, reason = FailReason.CORRUPT)
                    }
                    if (f.status == FileStatus.RENAMED && to != from) delete(from)
                    write(to, bytes, mode)
                    result(o)
                }
            }
        } catch (e: ApplyException) {
            result(Outcome.FAIL, e.hunk, if (e.hunk > 0) FailReason.MISMATCH else FailReason.CORRUPT)
        } catch (e: BinaryBaseMismatch) {
            result(Outcome.FAIL, reason = FailReason.BINARY_BASE)
        }
    }

    private class BinaryBaseMismatch : Exception()

    private fun produce(base: ByteArray?, f: FileDiff, baseSha: String? = null): Pair<ByteArray, Outcome> {
        val bin = f.binary
        if (bin != null) {
            // A binary patch is only valid against the exact object it was made from.
            if (baseSha != null && f.oldIndex != null && !shaMatches(baseSha, f.oldIndex)) {
                if (bin.kind == BinaryKind.DELTA || f.oldIndex.length >= 40) throw BinaryBaseMismatch()
            }
            val out = Applier.applyBinary(base, bin)
            if (out.size.toLong() != bin.size && bin.kind == BinaryKind.LITERAL) throw ApplyException(0, "size")
            return out to Outcome.OK
        }
        val r = Applier.applyText(base, f.hunks)
        val o = when {
            r.looseHunks > 0 -> Outcome.LOOSE
            r.offsetHunks > 0 -> Outcome.OFFSET
            else -> Outcome.OK
        }
        return r.content to o
    }

    private fun matchesNewIndex(f: FileDiff, bytes: ByteArray): Boolean {
        val expected = f.newIndex ?: return true
        return expected.all { it == '0' } || shaMatches(GitHash.blob(bytes), expected)
    }

    private fun shaMatches(full: String, abbrev: String): Boolean {
        if (abbrev.all { it == '0' }) return false
        return full.startsWith(abbrev.lowercase())
    }

    private fun normalMode(m: String?): String? = when (m) {
        null -> null
        "100755", "100644", "120000" -> m
        "100664" -> "100644"
        else -> if (m.startsWith("100") && m.endsWith("755")) "100755" else "100644"
    }

    /** Net changes of the current group against the state it started from. */
    suspend fun groupChanges(): List<TreeChange> {
        val out = ArrayList<TreeChange>()
        for ((path, before) in groupStart) {
            val now = overlay[path]
            if (now == null && before == null) continue
            if (now != null && before != null && now.sha == before.sha && now.mode == before.mode) continue
            if (now == null) {
                out += TreeChange(path, before!!.mode, null)
            } else {
                out += TreeChange(path, now.mode, ensureBlob(now))
            }
        }
        return out
    }

    private suspend fun ensureBlob(s: State): String {
        if (s.sha in baseShas || s.sha in uploaded) return s.sha
        val bytes = s.contentOrNull() ?: return s.sha
        val sha = remote.createBlob(bytes)
        if (sha != s.sha) throw PushException(PushException.Kind.INTEGRITY, "blob $sha != ${s.sha}")
        uploaded += sha
        return sha
    }
}
