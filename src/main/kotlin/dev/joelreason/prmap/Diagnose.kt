package dev.joelreason.prmap

import java.io.File

/**
 * Runs the parsing and the drawing rules over a topology file and prints what they
 * produced, so a diagram that comes out empty can be traced without the IDE.
 *
 * `./gradlew diagnose -Pmap=/path/to/map.json`
 */
object Diagnose {
  @JvmStatic
  fun main(args: Array<String>) {
    val file = File(args.firstOrNull() ?: error("give the topology json path"))
    val topology = Topology.parse(file.readText())

    println("parsed nodes=${topology.nodes.size} edges=${topology.edges.size}")
    println("changed=${topology.nodes.count { it.changed }}")
    println("bridges=${topology.nodes.mapNotNull { it.bridge }.groupingBy { it }.eachCount()}")
    println("first node=${topology.nodes.firstOrNull()}")

    val diagram = Diagram.build(topology, dropUses = true, withCallers = false)
    val boxes = diagram.lines().count { it.trim().matches(Regex("^\\w+[\\[(].*")) }
    val arrows = diagram.lines().count { it.contains("-->") || it.contains("-.->") }
    println("diagram lines=${diagram.lines().size} boxes=$boxes arrows=$arrows")
    println("---- first 500 characters ----")
    println(diagram.take(500))

    // A second argument writes the real page, which is how the screenshot is made.
    args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { target ->
      val page = Page.write(topology, dropUses = true, withCallers = false, dark = false)
      File(target).parentFile?.mkdirs()
      page.parentFile.listFiles()?.forEach { it.copyTo(File(File(target).parentFile, it.name), true) }
      println("page written beside $target")
    }
  }
}
