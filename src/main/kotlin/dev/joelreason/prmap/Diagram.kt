package dev.joelreason.prmap

/**
 * Turns a topology into Mermaid text.
 *
 * Two rules keep a diagram of this size readable. A type the whole repository names —
 * a status enum, say — is drawn on its own, because wiring it in joins every branch to
 * every other one. An untouched type that merely names two changed types is left out
 * unless [withCallers] asks for it, because a dozen of them all point at the same two
 * services and bury the shape.
 */
object Diagram {

  private val ARROW = mapOf(
    "extends" to "-->", "implements" to "-.->", "injects" to "-->", "uses" to "-.->"
  )
  private val VERB = mapOf(
    "extends" to "extends", "implements" to "implements",
    "injects" to "holds", "uses" to "calls"
  )

  /** Diagram node id -> topology node id, so a click resolves back to a file. */
  fun keys(nodes: List<MapNode>): Map<String, String> {
    val bySimple = nodes.groupBy { it.id.substringAfterLast('.') }
    return nodes.associate { node ->
      val owners = bySimple[node.id.substringAfterLast('.')].orEmpty()
      val raw = if (owners.size == 1) node.id.substringAfterLast('.') else node.id
      node.id to raw.replace(Regex("\\W"), "_")
    }
  }

  fun build(
    topology: Topology,
    dropUses: Boolean = true,
    sharedAt: Int = 50,
    withCallers: Boolean = false,
  ): String {
    val incomingKinds = topology.edges.groupBy({ it.to }, { it.kind })
      .mapValues { it.value.toSet() }

    val shared = topology.nodes.filter { node ->
      val kinds = incomingKinds[node.id].orEmpty()
      node.fanIn >= sharedAt && kinds.isNotEmpty() && kinds.all { it == "uses" }
    }.map { it.id }.toMutableSet()

    val hidden = if (withCallers) emptySet()
    else topology.nodes.filter { it.bridge == "caller" }.map { it.id }.toSet()
    shared -= hidden

    val dropped = shared + hidden
    val candidates = topology.edges.filter { it.from !in dropped && it.to !in dropped }
    val kept = candidates.filter { !(dropUses && it.kind == "uses") }.toMutableList()

    if (dropUses) {
      // A call is put back where it is the only edge an end has, so a unit test
      // never floats free of the type it tests.
      val attached = kept.flatMap { listOf(it.from, it.to) }.toMutableSet()
      candidates.filter { it.kind == "uses" }.forEach { edge ->
        if (edge.from !in attached || edge.to !in attached) {
          kept += edge
          attached += edge.from
          attached += edge.to
        }
      }
    }

    val connected = kept.flatMap { listOf(it.from, it.to) }.toSet()
    val drawn = topology.nodes.filter { it.id !in dropped && (it.changed || it.id in connected) }
    val visible = drawn.map { it.id }.toSet()
    val key = keys(topology.nodes)

    val out = StringBuilder("flowchart TB\n")
    drawn.sortedBy { it.name }.forEach { node ->
      val churn = if (node.changed) "+${node.additions}/-${node.deletions}" else "unchanged"
      // Every label is quoted: a bare bracket starts a shape and breaks the parse.
      val text = "\"${node.name}<br/>${node.role} · $churn\""
      val shape = if (node.changed) "[$text]" else "([$text])"
      out.append("  ${key[node.id]}$shape\n")
    }
    kept.filter { it.from in visible && it.to in visible }.forEach { edge ->
      out.append("  ${key[edge.from]} ${ARROW[edge.kind] ?: "-->"}")
        .append("|${VERB[edge.kind] ?: edge.kind}| ${key[edge.to]}\n")
    }
    topology.nodes.filter { it.id in shared }.forEach { node ->
      out.append("  ${key[node.id]}[/\"${node.name}<br/>named from ${node.fanIn} places repo-wide\"/]\n")
    }

    out.append("  classDef changed fill:#F4EBD8,stroke:#8A5A12,stroke-width:2px;\n")
    out.append("  classDef added fill:#DBEAE3,stroke:#0E4A3C,stroke-width:2px;\n")
    out.append("  classDef untouched fill:#FCFDFC,stroke:#8A9992,stroke-dasharray:3 3,color:#53645C;\n")
    drawn.filter { it.changed }.map { key[it.id] }.joinToString(",").ifBlank { null }
      ?.let { out.append("  class $it changed;\n") }
    drawn.filter { it.status == "added" }.map { key[it.id] }.joinToString(",").ifBlank { null }
      ?.let { out.append("  class $it added;\n") }
    drawn.filter { !it.changed }.map { key[it.id] }.joinToString(",").ifBlank { null }
      ?.let { out.append("  class $it untouched;\n") }
    return out.toString()
  }
}
