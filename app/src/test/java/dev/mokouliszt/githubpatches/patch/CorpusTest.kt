package dev.mokouliszt.githubpatches.patch

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays real commits of existing repositories through the parser and the pusher and compares the
 * result with git's own tree. Off unless GHP_CORPUS names a folder of clones:
 *
 *   GHP_CORPUS=/path/to/clones GHP_CORPUS_MAX=200 ./gradlew :app:testDebugUnitTest --tests '*CorpusTest'
 *
 * The report goes to GHP_CORPUS_OUT (default: build/corpus-report.txt).
 */
class CorpusTest {

    private class Tally {
        var commits = 0
        var patches = 0
        var same = 0
        var lostBy100 = 0
        var dashDashBy100 = 0
        val problems = ArrayList<String>()
    }

    @Test
    fun replayRealCommits() {
        val root = System.getenv("GHP_CORPUS")?.let(::File)
        assumeTrue(root != null && root.isDirectory)
        val max = System.getenv("GHP_CORPUS_MAX")?.toIntOrNull() ?: 200
        val out = File(System.getenv("GHP_CORPUS_OUT") ?: "build/corpus-report.txt")
        val report = StringBuilder()
        val total = Tally()
        for (dir in root!!.listFiles().orEmpty().filter { File(it, ".git").exists() }.sortedBy { it.name }) {
            val t = Tally()
            replay(GitRepo(dir), max, t)
            report.append("%-30s commits %5d  patches %5d  same %5d  1.0.0-lost %3d  1.0.0-dashdash %3d  problems %d\n"
                .format(dir.name, t.commits, t.patches, t.same, t.lostBy100, t.dashDashBy100, t.problems.size))
            t.problems.take(30).forEach { report.append("    ").append(it).append('\n') }
            total.commits += t.commits; total.patches += t.patches; total.same += t.same
            total.lostBy100 += t.lostBy100; total.dashDashBy100 += t.dashDashBy100; total.problems += t.problems
            out.parentFile?.mkdirs()
            out.writeText(report.toString())
        }
        report.append("TOTAL commits ${total.commits} patches ${total.patches} same ${total.same} " +
            "1.0.0-lost ${total.lostBy100} 1.0.0-dashdash ${total.dashDashBy100} problems ${total.problems.size}\n")
        out.writeText(report.toString())
    }

    private fun replay(repo: GitRepo, max: Int, t: Tally) {
        val commits = repo.git("rev-list", "--no-merges", "--max-count=$max", "HEAD").lines().filter { it.isNotBlank() }
        for (c in commits) {
            // Shallow clones lack the oldest parents; root commits have none.
            if (runCatching { repo.git("cat-file", "-e", "$c^{commit}^") }.isFailure) continue
            val parent = repo.git("rev-parse", "$c^")
            t.commits++
            val variants = listOf(
                "format-patch -M" to { repo.gitBytes("format-patch", "-1", "--stdout", "-M", c) },
                "format-patch --no-renames" to { repo.gitBytes("format-patch", "-1", "--stdout", "--no-renames", c) },
                "diff --binary -M" to { repo.gitBytes("diff", "--binary", "-M", parent, c) },
            )
            for ((name, make) in variants) {
                t.patches++
                val raw = make()
                val patch = try {
                    PatchParser.parse(raw)
                } catch (e: Exception) {
                    t.problems += "${c.take(10)} $name parse: ${e.javaClass.simpleName}: ${e.message}"
                    continue
                }
                if (name == "format-patch -M") {
                    if (patch.files.any { it.droppedBy100 > 0 }) t.lostBy100++
                    if (patch.files.any { f -> f.hunks.any { h -> dashDash(h) } }) t.dashDashBy100++
                }
                try {
                    repo.git("update-ref", "refs/heads/ghp-corpus", parent)
                    runBlocking {
                        Pusher(LocalRemote(repo)).push(patch, PushTarget("ghp-corpus", create = false), patch.commits.map { it.message.ifBlank { "x" } }, false, true)
                    }
                    if (repo.tree("ghp-corpus") == repo.tree(c)) t.same++
                    else t.problems += "${c.take(10)} $name tree differs: ${changedPaths(repo, c)}"
                } catch (e: PushException) {
                    val why = e.report?.files?.joinToString { "${it.path}:${it.outcome}:${it.reason}:${it.hunk}" } ?: e.detail
                    t.problems += "${c.take(10)} $name push ${e.kind}: $why"
                } catch (e: Exception) {
                    t.problems += "${c.take(10)} $name push: ${e.javaClass.simpleName}: ${e.message?.take(200)}"
                }
            }
        }
    }

    /** 1.0.0 took a removed "-- x" line followed by an added "++ y" line for the next file's header. */
    private fun dashDash(h: Hunk): Boolean = h.lines.zipWithNext().any { (a, b) ->
        a.kind == '-' && String(a.bytes, Charsets.ISO_8859_1).startsWith("-- ") &&
            b.kind == '+' && String(b.bytes, Charsets.ISO_8859_1).startsWith("++ ")
    }

    private fun changedPaths(repo: GitRepo, c: String): String =
        runCatching { repo.git("diff", "--name-only", c, "ghp-corpus").lines().take(5).joinToString() }.getOrDefault("?")
}
