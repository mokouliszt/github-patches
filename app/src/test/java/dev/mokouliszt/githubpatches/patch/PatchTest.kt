package dev.mokouliszt.githubpatches.patch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import kotlin.random.Random

class PatchTest {

    private lateinit var repo: GitRepo
    private lateinit var base: String
    private lateinit var c1: String
    private lateinit var c2: String
    private lateinit var c3: String
    private lateinit var c4: String

    private val sjis: Charset = Charset.forName("Shift_JIS")

    private fun lines(prefix: String, n: Int, eol: String = "\n") = (1..n).joinToString("") { "$prefix line $it$eol" }

    @Before
    fun setUp() {
        repo = GitRepo()
        assumeTrue(repo.isGitAvailable())
        val rnd = Random(42)
        repo.write("README.md", lines("readme", 40))
        repo.write("src/main.c", lines("code", 80))
        repo.write("crlf.txt", lines("crlf", 20, "\r\n"))
        repo.write("sjis.txt", (1..10).joinToString("") { "日本語の行 $it\n" }.toByteArray(sjis))
        repo.write("noeol.txt", "a\nb\nc")
        repo.write("old_name.txt", lines("rename me", 20))
        repo.write("script.sh", "#!/bin/sh\necho hi\n")
        repo.write("bin/data.bin", rnd.nextBytes(65536))
        repo.write("bin/remove.bin", rnd.nextBytes(4000))
        repo.write("dir/to_delete.txt", lines("delete", 5))
        repo.write("日本語/ファイル.txt", lines("和名", 6))
        base = repo.commitAll("base")
        repo.git("branch", "base")

        // 1: hunks in an existing file, a new nested file, a deletion
        val readme = File(repo.dir, "README.md").readText()
            .replace("readme line 3\n", "readme line 3 changed\n")
            .replace("readme line 30\n", "readme line 30\ninserted after 30\n")
        repo.write("README.md", readme)
        repo.write("new/dir/added.txt", "brand new\nfile\n")
        File(repo.dir, "dir/to_delete.txt").delete()
        c1 = repo.commitAll("Change readme and add a file\n\nLonger description\nover two lines.")

        // 2: rename with edits, chmod, binary delta and literal
        File(repo.dir, "old_name.txt").delete()
        repo.write("renamed/new_name.txt", lines("rename me", 20).replace("rename me line 7\n", "renamed line 7\n"))
        File(repo.dir, "script.sh").setExecutable(true)
        val data = File(repo.dir, "bin/data.bin").readBytes()
        for (i in 30000 until 30010) data[i] = (data[i] + 1).toByte()
        repo.write("bin/data.bin", data)
        repo.write("bin/added.png", rnd.nextBytes(300))
        c2 = repo.commitAll("Rename, chmod and binaries", author = "山田 太郎 <taro@example.jp>")

        // 3: CRLF, Shift_JIS, missing final newline, non-ASCII paths
        repo.write("crlf.txt", lines("crlf", 20, "\r\n").replace("crlf line 10\r\n", "crlf line ten\r\n"))
        repo.write("sjis.txt", (1..10).joinToString("") { if (it == 3) "変更した行\n" else "日本語の行 $it\n" }.toByteArray(sjis))
        repo.write("noeol.txt", "a\nb\nc\nd")
        repo.write("日本語/ファイル.txt", lines("和名", 6).replace("和名 line 2\n", "和名 line 二\n"))
        repo.write("スペース 付き.txt", "x\n")
        c3 = repo.commitAll("日本語の件名: エンコード確認")

        // 4: symlink, binary deletion, empty file, edit of a file added in 1
        java.nio.file.Files.createSymbolicLink(File(repo.dir, "link").toPath(), File("README.md").toPath())
        File(repo.dir, "bin/remove.bin").delete()
        repo.write("empty.txt", ByteArray(0))
        repo.write("new/dir/added.txt", "brand new\nfile\nmore\n")
        c4 = repo.commitAll("Symlink and cleanup")
    }

    private fun formatPatch(range: String, vararg paths: String): ByteArray =
        if (paths.isEmpty()) repo.gitBytes("format-patch", "--stdout", "-M", range)
        else repo.gitBytes("format-patch", "--stdout", "-M", range, "--", *paths)

    private fun diff(vararg args: String): ByteArray = repo.gitBytes("diff", *args)

    private fun push(patch: PatchSet, target: PushTarget, squash: Boolean = false, remote: LocalRemote = LocalRemote(repo)): PushResult =
        runBlocking {
            Pusher(remote).push(patch, target, patch.commits.map { it.message }, squash, keepAuthor = true)
        }

    private fun check(patch: PatchSet, target: PushTarget): CheckReport = runBlocking { Pusher(LocalRemote(repo)).check(patch, target) }

    @Test
    fun seriesIsReproducedCommitByCommit() {
        val raw = formatPatch("$base..$c4")
        assertTrue("expected a binary delta", String(raw, Charsets.ISO_8859_1).contains("\ndelta "))
        val patch = PatchParser.parse(raw)
        assertEquals(4, patch.commits.size)
        assertEquals("Change readme and add a file", patch.commits[0].subject)
        assertEquals("Longer description\nover two lines.", patch.commits[0].body)
        assertEquals("山田 太郎", patch.commits[1].authorName)
        assertEquals("taro@example.jp", patch.commits[1].authorEmail)
        assertEquals("日本語の件名: エンコード確認", patch.commits[2].subject)

        repo.git("branch", "target", base)
        val report = check(patch, PushTarget("target", create = false))
        assertTrue(report.files.joinToString { "${it.path}:${it.outcome}:${it.reason}" }, report.ok)
        assertTrue(report.files.all { it.outcome == Outcome.OK })

        val res = push(patch, PushTarget("target", create = false))
        assertEquals(4, res.commits.size)
        listOf(c1, c2, c3, c4).forEachIndexed { i, orig ->
            assertEquals("tree of commit ${i + 1}", repo.tree(orig), repo.tree(res.commits[i]))
        }
        assertEquals(repo.git("log", "-1", "--format=%B", c1), repo.git("log", "-1", "--format=%B", res.commits[0]))
        assertEquals("山田 太郎 <taro@example.jp>", repo.git("log", "-1", "--format=%an <%ae>", res.commits[1]))
        assertEquals(repo.git("log", "-1", "--format=%at", c2), repo.git("log", "-1", "--format=%at", res.commits[1]))
        assertEquals(res.head, repo.git("rev-parse", "target"))
    }

    @Test
    fun plainBinaryDiffToNewBranch() {
        val patch = PatchParser.parse(diff("--binary", "-M", base, c4))
        assertEquals(1, patch.commits.size)
        assertFalse(patch.hasMetadata)
        val res = push(patch, PushTarget("feature/x", create = true, base = "base"))
        assertEquals(repo.tree(c4), repo.tree("feature/x"))
        assertEquals(base, repo.git("rev-parse", "feature/x^"))
        assertTrue(res.created)
    }

    @Test
    fun squashedSeries() {
        val patch = PatchParser.parse(formatPatch("$base..$c4"))
        repo.git("branch", "sq", base)
        val res = push(patch, PushTarget("sq", create = false), squash = true)
        assertEquals(1, res.commits.size)
        assertEquals(repo.tree(c4), repo.tree("sq"))
    }

    @Test
    fun zeroContextDiff() {
        val patch = PatchParser.parse(diff("-U0", base, c1, "--", "README.md", "src/main.c"))
        repo.git("branch", "u0", base)
        push(patch, PushTarget("u0", create = false))
        assertEquals(repo.git("rev-parse", "$c1:README.md"), repo.git("rev-parse", "u0:README.md"))
    }

    @Test
    fun appliesWithOffsetOnMovedBranch() {
        repo.git("checkout", "-q", "-b", "drift", base)
        repo.write("README.md", "top 1\ntop 2\ntop 3\ntop 4\ntop 5\n" + lines("readme", 40))
        repo.commitAll("drift")
        val patch = PatchParser.parse(formatPatch("$base..$c1"))
        val report = check(patch, PushTarget("drift", create = false))
        assertTrue(report.ok)
        assertEquals(Outcome.OFFSET, report.files.first { it.path == "README.md" }.outcome)
        push(patch, PushTarget("drift", create = false))
        // Same result as a three-way cherry-pick.
        repo.git("checkout", "-q", "-b", "expected", "drift~1")
        repo.git("cherry-pick", "--allow-empty", c1)
        assertEquals(repo.tree("expected"), repo.tree("drift"))
    }

    @Test
    fun reportsConflictingHunk() {
        repo.git("checkout", "-q", "-b", "conflict", base)
        repo.write("README.md", lines("readme", 40).replace("readme line 30\n", "someone else edited 30\n"))
        repo.commitAll("other edit")
        val patch = PatchParser.parse(formatPatch("$base..$c1"))
        val report = check(patch, PushTarget("conflict", create = false))
        assertFalse(report.ok)
        val f = report.files.first { it.path == "README.md" }
        assertEquals(Outcome.FAIL, f.outcome)
        assertEquals(FailReason.MISMATCH, f.reason)
        assertEquals(2, f.hunk)
        val before = repo.git("rev-parse", "conflict")
        val e = runCatching { push(patch, PushTarget("conflict", create = false)) }.exceptionOrNull()
        assertTrue(e is PushException && e.kind == PushException.Kind.DOES_NOT_APPLY)
        assertEquals(before, repo.git("rev-parse", "conflict"))
    }

    @Test
    fun strippedCarriageReturnsApplyLoosely() {
        val raw = String(formatPatch("$c2..$c3", "crlf.txt"), Charsets.ISO_8859_1).replace("\r", "")
        val patch = PatchParser.parse(raw.toByteArray(Charsets.ISO_8859_1))
        repo.git("branch", "loose", c2)
        val report = check(patch, PushTarget("loose", create = false))
        assertTrue(report.ok)
        assertEquals(Outcome.LOOSE, report.files.single().outcome)
        push(patch, PushTarget("loose", create = false))
        assertEquals(repo.git("rev-parse", "$c3:crlf.txt"), repo.git("rev-parse", "loose:crlf.txt"))
    }

    @Test
    fun wholePatchConvertedToCrlf() {
        val raw = String(formatPatch("$c2..$c3"), Charsets.ISO_8859_1).replace("\n", "\r\n")
        val patch = PatchParser.parse(raw.toByteArray(Charsets.ISO_8859_1))
        repo.git("branch", "dos", c2)
        val report = check(patch, PushTarget("dos", create = false))
        assertTrue(report.files.joinToString { "${it.path}:${it.outcome}" }, report.files.all { it.outcome == Outcome.OK })
        push(patch, PushTarget("dos", create = false))
        assertEquals(repo.tree(c3), repo.tree("dos"))
    }

    @Test
    fun handWrittenCountsAreTolerated() {
        val p = """
            --- a/README.md
            +++ b/README.md
            @@ -2,3 +2,9 @@
             readme line 2
            -readme line 3
            +readme line 3 changed
             readme line 4
        """.trimIndent() + "\n"
        val patch = PatchParser.parse(p.toByteArray())
        repo.git("branch", "hand", base)
        push(patch, PushTarget("hand", create = false))
        val text = repo.git("show", "hand:README.md")
        assertTrue(text.contains("readme line 3 changed\nreadme line 4\n"))
    }

    @Test
    fun traditionalDiff() {
        val p = """
            --- README.md.orig	2026-10-10 10:00:00.000000000 +0900
            +++ README.md	2026-10-10 10:01:00.000000000 +0900
            @@ -39,2 +39,3 @@
             readme line 39
             readme line 40
            +appended
        """.trimIndent() + "\n"
        val patch = PatchParser.parse(p.toByteArray())
        assertEquals("README.md", patch.files.single().path)
        repo.git("branch", "trad", base)
        push(patch, PushTarget("trad", create = false))
        assertTrue(repo.git("show", "trad:README.md").endsWith("readme line 40\nappended"))
    }

    @Test
    fun retriesWhenBranchMoves() {
        repo.git("branch", "race", base)
        val remote = LocalRemote(repo)
        remote.raceOnce = {
            val blob = repo.git("hash-object", "-w", "--stdin", input = "other\n".toByteArray())
            val idx = File.createTempFile("idx", null).also { it.delete() }
            val env = mapOf("GIT_INDEX_FILE" to idx.absolutePath)
            repo.git("read-tree", "race", env = env)
            repo.git("update-index", "--add", "--cacheinfo", "100644,$blob,other.txt", env = env)
            val t = repo.git("write-tree", env = env)
            val c = repo.git("commit-tree", t, "-p", "race", input = "concurrent\n".toByteArray())
            repo.git("update-ref", "refs/heads/race", c)
        }
        val patch = PatchParser.parse(formatPatch("$base..$c1"))
        push(patch, PushTarget("race", create = false), remote = remote)
        assertEquals("other\n", repo.git("show", "race:other.txt") + "\n")
        assertEquals(repo.git("rev-parse", "$c1:README.md"), repo.git("rev-parse", "race:README.md"))
    }

    @Test
    fun existingBranchNameIsRejected() {
        val patch = PatchParser.parse(formatPatch("$base..$c1"))
        val e = runCatching { check(patch, PushTarget("main", create = true, base = "base")) }.exceptionOrNull()
        assertTrue(e is PushException && e.kind == PushException.Kind.BRANCH_EXISTS)
    }

    @Test
    fun renameDoesNotDownloadContent() {
        repo.git("checkout", "-q", "-b", "mv", base)
        repo.git("mv", "bin/data.bin", "moved.bin")
        val c = repo.commitAll("move")
        val patch = PatchParser.parse(formatPatch("$base..$c"))
        repo.git("branch", "mvtarget", base)
        val remote = LocalRemote(repo)
        push(patch, PushTarget("mvtarget", create = false), remote = remote)
        assertEquals(repo.tree(c), repo.tree("mvtarget"))
        assertEquals(0, remote.blobDownloads)
        assertEquals(0, remote.blobUploads)
    }

    @Test
    fun quotedPathsAndHeaders() {
        val patch = PatchParser.parse(formatPatch("$c2..$c3"))
        val paths = patch.files.map { it.path }.toSet()
        assertTrue(paths.toString(), "日本語/ファイル.txt" in paths)
        assertTrue(paths.toString(), "スペース 付き.txt" in paths)
        assertNotNull(patch.commits.single().date)
    }

    @Test
    fun reportsExistingAndMissingFiles() {
        // c1 adds new/dir/added.txt and deletes dir/to_delete.txt; replaying it on c1 itself must fail on both.
        val patch = PatchParser.parse(formatPatch("$base..$c1"))
        repo.git("branch", "again", c1)
        val report = check(patch, PushTarget("again", create = false))
        assertFalse(report.ok)
        assertEquals(FailReason.EXISTS, report.files.first { it.path == "new/dir/added.txt" }.reason)
        assertEquals(FailReason.MISSING, report.files.first { it.path == "dir/to_delete.txt" }.reason)
    }

    @Test
    fun copiesWithEdits() {
        repo.git("checkout", "-q", "-b", "cp", base)
        repo.write("src/copy.c", lines("code", 80).replace("code line 40\n", "copied line 40\n"))
        val c = repo.commitAll("copy")
        val patch = PatchParser.parse(repo.gitBytes("format-patch", "--stdout", "-C", "-C", "$base..$c"))
        assertEquals(FileStatus.COPIED, patch.files.single().status)
        repo.git("branch", "cptarget", base)
        push(patch, PushTarget("cptarget", create = false))
        assertEquals(repo.tree(c), repo.tree("cptarget"))
    }

    @Test
    fun rejectsNonPatch() {
        val e = runCatching { PatchParser.parse("hello\nworld\n".toByteArray()) }.exceptionOrNull()
        assertTrue(e is PatchFormatException)
    }
}
