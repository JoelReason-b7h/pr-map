package dev.joelreason.prmap

import com.google.gson.Gson
import java.io.File
import java.util.concurrent.TimeUnit

data class ChangedFile(
  val path: String,
  val additions: Int,
  val deletions: Int,
  val status: String,
)

/** What changed, and between which two commits. PSI knows the code; git knows the change. */
class Git(private val repo: File) {

  class Failed(message: String) : Exception(message)

  fun resolve(base: String, head: String, pr: String?): MapMeta {
    if (pr.isNullOrBlank()) {
      val mergeBase = run("git", "merge-base", base, head)
        .ifBlank { throw Failed("no merge base between $base and $head") }
      return MapMeta(base = base, head = head, mergeBase = mergeBase)
    }

    val json = run("gh", "pr", "view", pr, "--json",
                   "baseRefName,headRefOid,number,title,url,headRefName")
      .ifBlank { throw Failed("no pull request $pr") }
    val view = Gson().fromJson(json, PrView::class.java)
    val baseRef = "origin/${view.baseRefName}"
    val mergeBase = run("git", "merge-base", baseRef, view.headRefOid.orEmpty())
      .ifBlank { throw Failed("the head of pull request $pr is not in this checkout") }
    return MapMeta(pr = view.number, title = view.title, url = view.url, base = baseRef,
                   head = view.headRefOid, mergeBase = mergeBase, branch = view.headRefName)
  }

  /**
   * --no-renames keeps every path literal, because rename detection prints a rewrite form
   * such as "dir/{old => new}.java", which is not a path anything downstream can open.
   */
  fun changedFiles(mergeBase: String, head: String): List<ChangedFile> {
    val letters = run("git", "diff", "--no-renames", "--name-status", "$mergeBase..$head")
      .lineSequence().mapNotNull { line ->
        val parts = line.split("\t")
        if (parts.size >= 2) parts.last() to parts.first().first() else null
      }.toMap()

    return run("git", "diff", "--no-renames", "--numstat", "$mergeBase..$head")
      .lineSequence().mapNotNull { line ->
        val parts = line.split("\t")
        if (parts.size != 3) return@mapNotNull null
        ChangedFile(
          path = parts[2],
          additions = parts[0].toIntOrNull() ?: 0,
          deletions = parts[1].toIntOrNull() ?: 0,
          status = when (letters[parts[2]]) {
            'A' -> "added"; 'D' -> "deleted"; else -> "modified"
          },
        )
      }.toList()
  }

  /**
   * Every named blob at a ref, in one pass.
   *
   * One `git cat-file --batch` rather than a `git show` for each path, because these are
   * read before the analysis takes a read action and a subprocess per file would hold it.
   */
  fun showAll(ref: String, paths: List<String>): Map<String, String> {
    if (paths.isEmpty()) return emptyMap()
    val process = ProcessBuilder("git", "cat-file", "--batch").directory(repo)
      .redirectErrorStream(false).start()
    process.outputStream.bufferedWriter().use { writer ->
      paths.forEach { writer.write("$ref:$it\n") }
    }
    val out = process.inputStream.readBytes()
    if (!process.waitFor(120, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw Failed("git cat-file did not finish within 120s")
    }

    val contents = LinkedHashMap<String, String>()
    var offset = 0
    for (path in paths) {
      val end = out.indexOfFirst(offset, '\n'.code.toByte())
      if (end < 0) break
      val header = String(out, offset, end - offset).split(" ")
      offset = end + 1
      if (header.size < 3) continue                 // "<object> missing"
      val size = header[2].toIntOrNull() ?: continue
      contents[path] = String(out, offset, size)
      offset += size + 1
    }
    return contents
  }

  private fun ByteArray.indexOfFirst(from: Int, value: Byte): Int {
    for (index in from until size) if (this[index] == value) return index
    return -1
  }

  /** Whether this ref is what the working tree currently holds. */
  fun isCheckedOut(ref: String): Boolean {
    if (ref.isBlank() || ref == "HEAD") return true
    val head = run("git", "rev-parse", "HEAD")
    return head.isNotBlank() && head == run("git", "rev-parse", ref)
  }

  fun currentBranch(): String? = run("git", "rev-parse", "--abbrev-ref", "HEAD")
    .takeIf { it.isNotBlank() && it != "HEAD" }

  private fun run(vararg command: String): String = try {
    val process = ProcessBuilder(*command).directory(repo).start()
    val text = process.inputStream.bufferedReader().readText().trim()
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw Failed("${command.first()} did not finish within 60s")
    }
    if (process.exitValue() == 0) text else ""
  } catch (error: Failed) {
    throw error
  } catch (error: Exception) {
    throw Failed(error.message ?: command.joinToString(" "))
  }

  private data class PrView(
    val baseRefName: String? = null,
    val headRefOid: String? = null,
    val number: Int? = null,
    val title: String? = null,
    val url: String? = null,
    val headRefName: String? = null,
  )
}
