package dev.mokouliszt.githubpatches.patch

import java.io.File
import java.nio.file.Files

/** A throwaway git repository driven through the git CLI. */
class GitRepo(val dir: File = Files.createTempDirectory("ghp").toFile()) {

    init {
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test User")
        git("config", "user.email", "test@example.com")
        git("config", "core.autocrlf", "false")
        git("config", "commit.gpgsign", "false")
    }

    fun gitBytes(vararg args: String, input: ByteArray? = null, env: Map<String, String> = emptyMap()): ByteArray {
        val pb = ProcessBuilder(listOf("git") + args).directory(dir)
        pb.environment().putAll(env)
        pb.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        val p = pb.start()
        if (input != null) p.outputStream.use { it.write(input) } else p.outputStream.close()
        val out = p.inputStream.readBytes()
        val err = p.errorStream.readBytes()
        val code = p.waitFor()
        if (code != 0) throw IllegalStateException("git ${args.joinToString(" ")} failed ($code): ${String(err)}")
        return out
    }

    fun git(vararg args: String, input: ByteArray? = null, env: Map<String, String> = emptyMap()): String =
        String(gitBytes(*args, input = input, env = env)).trimEnd('\n')

    fun write(path: String, content: ByteArray) {
        val f = File(dir, path)
        f.parentFile.mkdirs()
        f.writeBytes(content)
    }

    fun write(path: String, text: String) = write(path, text.toByteArray())

    fun commitAll(message: String, author: String = "Test User <test@example.com>"): String {
        git("add", "-A")
        git("commit", "-q", "--allow-empty", "-m", message, "--author", author)
        return git("rev-parse", "HEAD")
    }

    fun tree(rev: String) = git("rev-parse", "$rev^{tree}")

    fun isGitAvailable(): Boolean = runCatching { git("--version"); true }.getOrDefault(false)
}

/** [GitRemote] over a local repository, mirroring the GitHub Git Data API semantics. */
class LocalRemote(private val repo: GitRepo) : GitRemote {
    var blobUploads = 0
    var blobDownloads = 0
    /** Simulates someone else pushing right before our update (once). */
    var raceOnce: (() -> Unit)? = null

    override suspend fun branchHead(branch: String): String? =
        runCatching { repo.git("rev-parse", "--verify", "-q", "refs/heads/$branch") }.getOrNull()?.takeIf { it.isNotBlank() }

    override suspend fun commitTree(commit: String): String = repo.tree(commit)

    override suspend fun tree(sha: String): List<TreeItem> {
        val raw = repo.gitBytes("ls-tree", "-z", sha)
        val out = ArrayList<TreeItem>()
        var start = 0
        for (i in raw.indices) {
            if (raw[i] == 0.toByte()) {
                val rec = raw.copyOfRange(start, i)
                val tab = rec.indexOf('\t'.code.toByte())
                val meta = String(rec, 0, tab).split(' ')
                val name = String(rec, tab + 1, rec.size - tab - 1, Charsets.UTF_8)
                out += TreeItem(name, meta[0], meta[1], meta[2])
                start = i + 1
            }
        }
        return out
    }

    override suspend fun blob(sha: String): ByteArray { blobDownloads++; return repo.gitBytes("cat-file", "blob", sha) }

    override suspend fun createBlob(content: ByteArray): String {
        blobUploads++
        return repo.git("hash-object", "-w", "--stdin", input = content)
    }

    override suspend fun createTree(baseTree: String, changes: List<TreeChange>): String {
        val index = File.createTempFile("idx", null).also { it.delete() }
        val env = mapOf("GIT_INDEX_FILE" to index.absolutePath)
        repo.git("read-tree", baseTree, env = env)
        for (c in changes) {
            if (c.sha == null) repo.git("update-index", "--force-remove", "--", c.path, env = env)
            else repo.git("update-index", "--add", "--cacheinfo", "${c.mode},${c.sha},${c.path}", env = env)
        }
        val t = repo.git("write-tree", env = env)
        index.delete()
        return t
    }

    override suspend fun createCommit(message: String, tree: String, parents: List<String>, author: Author?): String {
        val env = HashMap<String, String>()
        if (author != null) {
            env["GIT_AUTHOR_NAME"] = author.name
            env["GIT_AUTHOR_EMAIL"] = author.email
            author.date?.let { env["GIT_AUTHOR_DATE"] = it }
        }
        val args = arrayListOf("commit-tree", tree)
        parents.forEach { args += listOf("-p", it) }
        return repo.git(*args.toTypedArray(), input = message.toByteArray(), env = env)
    }

    override suspend fun updateBranch(branch: String, commit: String) {
        raceOnce?.let { raceOnce = null; it() }
        val cur = branchHead(branch) ?: throw RemoteException(RemoteException.Kind.NOT_FOUND, branch)
        val ff = runCatching { repo.git("merge-base", "--is-ancestor", cur, commit); true }.getOrDefault(false)
        if (!ff) throw RemoteException(RemoteException.Kind.NOT_FAST_FORWARD, "Update is not a fast forward")
        repo.git("update-ref", "refs/heads/$branch", commit)
    }

    override suspend fun createBranch(branch: String, commit: String) {
        if (branchHead(branch) != null) throw RemoteException(RemoteException.Kind.ALREADY_EXISTS, "Reference already exists")
        repo.git("update-ref", "refs/heads/$branch", commit)
    }
}
