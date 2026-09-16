package dev.joelreason.prmap

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled analyser over the project's git repository.
 *
 * The analysis is a Python script rather than Kotlin because it reads the whole
 * repository through `git cat-file --batch`, and it is the same script the terminal
 * tooling uses, so both stay on one implementation. It ships inside the plugin and is
 * copied out to a temporary file on first use, so nothing needs installing.
 */
object Analyser {

  class Failed(message: String) : Exception(message)

  private var script: File? = null

  private fun scriptFile(): File {
    script?.let { if (it.exists()) return it }
    val body = javaClass.getResourceAsStream("/prmap/topology.py")
      ?.readBytes() ?: throw Failed("the analyser is missing from the plugin")
    val file = Files.createTempFile("prmap-topology", ".py").toFile()
    file.writeBytes(body)
    file.deleteOnExit()
    script = file
    return file
  }

  fun python(): String =
    listOf("/usr/bin/python3", "/opt/homebrew/bin/python3", "/usr/local/bin/python3")
      .firstOrNull { File(it).canExecute() } ?: "python3"

  fun run(
    repo: File,
    base: String,
    head: String,
    pr: String?,
    noTests: Boolean,
    timeoutSeconds: Long = 180,
  ): Topology {
    val out = Files.createTempFile("prmap", ".json").toFile()
    out.deleteOnExit()

    val command = mutableListOf(python(), scriptFile().absolutePath, "--out", out.absolutePath)
    if (!pr.isNullOrBlank()) {
      command += listOf("--pr", pr.trim())
    } else {
      command += listOf("--base", base, "--head", head)
    }
    if (noTests) command += "--no-tests"

    val process = ProcessBuilder(command)
      .directory(repo)
      .redirectErrorStream(true)
      .start()

    val log = process.inputStream.bufferedReader().readText()
    // A hung analysis must not hold the tool window, so the wait is bounded and the
    // process is killed rather than left behind.
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw Failed("the analysis did not finish within ${timeoutSeconds}s")
    }
    if (process.exitValue() != 0) throw Failed(log.trim().ifBlank { "the analysis failed" })

    val json = out.readText()
    if (json.isBlank()) throw Failed(log.trim().ifBlank { "the analysis produced nothing" })
    return Topology.parse(json)
  }
}
