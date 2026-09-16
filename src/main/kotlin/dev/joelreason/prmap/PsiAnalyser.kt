package dev.joelreason.prmap

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import java.io.File
import java.util.ArrayDeque

/**
 * Builds the topology from the IDE's own resolved index.
 *
 * Every reference comes from `resolve()`, which is what the compiler sees, so imports,
 * wildcard imports, static imports, same-package names, nested types and generic bounds
 * all resolve correctly rather than being imitated by rules over the text.
 *
 * A changed file is read from the ref itself rather than from disk, so a pull request
 * maps without checking its branch out. Its references resolve against the project index,
 * which holds the untouched code, and a name the index cannot resolve is matched against
 * the change set — that is how a reference to a class the change set adds is kept.
 */
class PsiAnalyser(private val project: Project, private val repo: File) {

  class Failed(message: String) : Exception(message)

  private data class Relationship(val target: String, val kind: String, val line: Int)

  /** Simple name -> fqn for the types this change set declares, used when the index cannot
   *  resolve a name because the change set is the only place that class exists. */
  private var declaredHere: Map<String, String> = emptyMap()
  private var readFrom: String = "HEAD"
  private var refIsCheckout = true
  private val parsed = HashMap<String, PsiClass?>()      // path -> class, parsed once

  fun run(
    base: String,
    head: String,
    pr: String?,
    noTests: Boolean,
    indicator: ProgressIndicator,
  ): Topology {
    val git = Git(repo)
    val meta = git.resolve(base, head, pr)
    val files = git.changedFiles(meta.mergeBase.orEmpty(), meta.head.orEmpty())
      .filter { !noTests || !isTest(it.path) }

    readFrom = meta.head.orEmpty()
    refIsCheckout = git.isCheckedOut(readFrom)
    indicator.text = "Resolving references"
    return DumbService.getInstance(project).runReadActionInSmartMode<Topology> {
      build(meta, files)
    }
  }

  // ------------------------------------------------------------------ building

  private fun build(meta: MapMeta, files: List<ChangedFile>): Topology {
    val changed = LinkedHashMap<String, MapNode>()          // fqn -> node
    val unlinked = mutableListOf<UnlinkedFile>()
    val owner = HashMap<String, PsiClass>()                 // fqn -> class

    // Every class the change set declares, so a reference between two of them resolves
    // even when neither exists on disk.
    declaredHere = files.mapNotNull { entry ->
      classFor(entry.path)?.let { psiClass ->
        psiClass.name?.let { name -> name to (psiClass.qualifiedName ?: name) }
      }
    }.toMap()

    for (entry in files) {
      val psiClass = classFor(entry.path)
      val fqn = psiClass?.qualifiedName
      if (psiClass == null || fqn == null) {
        unlinked += UnlinkedFile(entry.path, entry.additions, entry.deletions, entry.status)
        continue
      }
      owner[fqn] = psiClass
      changed[fqn] = node(psiClass, entry)
    }

    val relationships = HashMap<String, List<Relationship>>()
    fun outgoing(fqn: String): List<Relationship> = relationships.getOrPut(fqn) {
      owner[fqn]?.let { references(it) } ?: classByName(fqn)?.let { references(it) } ?: emptyList()
    }

    val fanIn = HashMap<String, Int>()
    changed.keys.forEach { fqn ->
      fanIn[fqn] = owner[fqn]?.let { countReferences(it) } ?: 0
    }

    val bridges = findBridges(changed.keys, ::outgoing, fanIn)
    val selected = LinkedHashMap<String, MapNode>(changed)
    bridges.forEach { (fqn, why) ->
      val psiClass = classByName(fqn) ?: return@forEach
      selected[fqn] = node(psiClass, null).copy(bridge = why, fanIn = fanIn(psiClass, fanIn))
    }

    val edges = mutableListOf<MapEdge>()
    selected.keys.forEach { from ->
      outgoing(from).forEach { relationship ->
        if (relationship.target in selected) {
          edges += MapEdge(from, relationship.target, relationship.kind)
        }
      }
    }

    val nodes = selected.values.map { it.copy(fanIn = it.fanIn.takeIf { n -> n > 0 } ?: 0) }
    return Topology(meta, nodes, edges.distinctBy { Triple(it.from, it.to, it.kind) }, unlinked)
  }

  /**
   * The untouched types that earn a place: one on a path from a changed type down to
   * another, one that names two or more changed types, and one that two or more changed
   * types depend on where that joins parts otherwise separate.
   */
  private fun findBridges(
    roots: Set<String>,
    outgoing: (String) -> List<Relationship>,
    fanIn: Map<String, Int>,
  ): Map<String, String> {
    val bridges = LinkedHashMap<String, String>()

    for (start in roots) {
      val parent = HashMap<String, String>()
      val distance = hashMapOf(start to 0)
      val queue = ArrayDeque(listOf(start))
      while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        if (distance.getValue(node) >= MAX_BRIDGE) continue
        for (relationship in outgoing(node)) {
          val target = relationship.target
          if (target in distance) continue
          distance[target] = distance.getValue(node) + 1
          parent[target] = node
          queue += target
        }
      }
      for (end in roots) {
        if (end == start || end !in parent) continue
        var node = end
        val chain = mutableListOf<String>()
        while (node != start) {
          node = parent.getValue(node)
          if (node != start && node !in roots) chain += node
        }
        chain.forEach { bridges[it] = "path" }
      }
    }

    // Who names two or more changed types: the blast radius.
    val callers = HashMap<String, Int>()
    roots.forEach { root ->
      classByName(root)?.let { psiClass ->
        referencingClasses(psiClass).forEach { caller ->
          if (caller !in roots) callers[caller] = (callers[caller] ?: 0) + 1
        }
      }
    }
    callers.filterValues { it >= 2 }.keys.take(MAX_SHARED)
      .forEach { bridges.putIfAbsent(it, "caller") }

    // A base class or interface two changed types share, where nothing else joins them.
    val shared = HashMap<String, MutableList<String>>()
    roots.forEach { root ->
      outgoing(root).forEach { relationship ->
        if (relationship.target !in roots) {
          shared.getOrPut(relationship.target) { mutableListOf() } += root
        }
      }
    }
    shared.entries
      .filter { it.value.size >= 2 && (fanIn[it.key] ?: 0) < HUB_FAN_IN }
      .sortedBy { entry -> entry.value.size * -1 }
      .take(MAX_SHARED)
      .forEach { bridges.putIfAbsent(it.key, "shared") }

    return bridges
  }

  // ------------------------------------------------------------------ psi reading

  /**
   * The class a changed path declares, taken from the ref being mapped.
   *
   * The file on disk is used when the ref is the checkout, so uncommitted edits count.
   * Otherwise the text comes from git and PSI parses it in memory, which is what lets a
   * pull request map without checking its branch out.
   */
  private fun classFor(path: String): PsiClass? = parsed.getOrPut(path) {
    if (!path.endsWith(".java")) return@getOrPut null
    val onDisk = LocalFileSystem.getInstance().findFileByIoFile(File(repo, path))
    if (onDisk != null && refIsCheckout) {
      val psiFile = PsiManager.getInstance(project).findFile(onDisk) as? PsiJavaFile
      psiFile?.classes?.firstOrNull()?.let { return@getOrPut it }
    }
    val text = Git(repo).show(readFrom, path) ?: return@getOrPut null
    val file = PsiFileFactory.getInstance(project)
      .createFileFromText(path.substringAfterLast('/'), JavaFileType.INSTANCE, text)
    (file as? PsiJavaFile)?.classes?.firstOrNull()
  }

  private fun classByName(fqn: String): PsiClass? =
    com.intellij.psi.JavaPsiFacade.getInstance(project)
      .findClass(fqn, GlobalSearchScope.projectScope(project))

  /** What this class references, with the strongest relationship kept per target. */
  private fun references(psiClass: PsiClass): List<Relationship> {
    val strongest = LinkedHashMap<String, Relationship>()

    fun record(target: PsiClass?, kind: String, element: PsiElement) {
      val fqn = PsiUtil.getTopLevelClass(target ?: return)?.qualifiedName ?: return
      if (fqn == psiClass.qualifiedName) return
      val existing = strongest[fqn]
      if (existing == null || RANK.getValue(kind) < RANK.getValue(existing.kind)) {
        strongest[fqn] = Relationship(fqn, kind, lineOf(element))
      }
    }

    psiClass.extendsListTypes.forEach { record(it.resolve(), "extends", psiClass) }
    psiClass.implementsListTypes.forEach { record(it.resolve(), "implements", psiClass) }
    psiClass.fields.forEach { field -> record(typeClass(field), "injects", field) }
    psiClass.constructors.forEach { constructor ->
      constructor.parameterList.parameters.forEach { record(typeClass(it), "injects", it) }
    }
    PsiTreeUtil.findChildrenOfType(psiClass, PsiJavaCodeReferenceElement::class.java)
      .forEach { reference ->
        val resolved = reference.resolve()
        val target = resolved as? PsiClass ?: PsiTreeUtil.getParentOfType(resolved, PsiClass::class.java)
        if (target != null) {
          record(target, "uses", reference)
        } else {
          // The index cannot resolve it, so the change set is the only place it exists.
          val fqn = declaredHere[reference.referenceName] ?: return@forEach
          if (fqn != psiClass.qualifiedName && fqn !in strongest) {
            strongest[fqn] = Relationship(fqn, "uses", lineOf(reference))
          }
        }
      }
    return strongest.values.toList()
  }

  private fun typeClass(element: PsiElement): PsiClass? = when (element) {
    is PsiField -> PsiUtil.resolveClassInType(element.type)
    is PsiParameter -> PsiUtil.resolveClassInType(element.type)
    is PsiMethod -> PsiUtil.resolveClassInType(element.returnType)
    else -> null
  }

  private fun countReferences(psiClass: PsiClass): Int {
    var count = 0
    ReferencesSearch.search(psiClass, GlobalSearchScope.projectScope(project)).forEach {
      count += 1
      count < FAN_IN_CAP                     // stop counting once it is plainly a hub
    }
    return count
  }

  private fun referencingClasses(psiClass: PsiClass): Set<String> {
    val out = LinkedHashSet<String>()
    ReferencesSearch.search(psiClass, GlobalSearchScope.projectScope(project)).forEach { reference ->
      PsiUtil.getTopLevelClass(reference.element)?.qualifiedName?.let { out += it }
      out.size < MAX_SHARED * 8
    }
    return out
  }

  private fun fanIn(psiClass: PsiClass, cache: Map<String, Int>): Int =
    psiClass.qualifiedName?.let { cache[it] } ?: countReferences(psiClass)

  private fun lineOf(element: PsiElement): Int {
    val file: PsiFile = element.containingFile ?: return 1
    val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return 1
    return document.getLineNumber(element.textOffset) + 1
  }

  private fun node(psiClass: PsiClass, entry: ChangedFile?): MapNode {
    val path = psiClass.containingFile?.virtualFile?.path
      ?.removePrefix(repo.path + File.separator) ?: ""
    return MapNode(
      id = psiClass.qualifiedName ?: psiClass.name.orEmpty(),
      name = psiClass.name.orEmpty(),
      path = entry?.path ?: path,
      role = roleOf(psiClass, entry?.path ?: path),
      changed = entry != null,
      status = entry?.status,
      bridge = null,
      additions = entry?.additions ?: 0,
      deletions = entry?.deletions ?: 0,
      line = lineOf(psiClass),
      fanIn = 0,
    )
  }

  private fun roleOf(psiClass: PsiClass, path: String): String {
    val name = psiClass.name.orEmpty()
    val annotations = psiClass.annotations.mapNotNull { it.qualifiedName?.substringAfterLast('.') }
    return when {
      isTest(path) -> "test"
      "Scheduled" in annotations || name.endsWith("Scheduler") -> "scheduler"
      "Controller" in annotations || name.endsWith("Controller") -> "controller"
      "Client" in annotations || name.endsWith("Client") -> "client"
      psiClass.isEnum -> "enum"
      name.endsWith("Repository") -> "repository"
      name.endsWith("Strategy") -> "strategy"
      name.endsWith("Processor") -> "processor"
      name.endsWith("Operations") || name.endsWith("Orchestrator") -> "operations"
      name.endsWith("Service") -> "service"
      name.startsWith("Validate") || name.endsWith("Validator") -> "validator"
      psiClass.isInterface -> "interface"
      else -> "type"
    }
  }

  private fun isTest(path: String) = TEST_ROOTS.any { it in path }

  companion object {
    private const val MAX_BRIDGE = 3
    private const val MAX_SHARED = 12
    private const val HUB_FAN_IN = 50
    private const val FAN_IN_CAP = 400
    private val RANK = mapOf("extends" to 0, "implements" to 1, "injects" to 2, "uses" to 3)
    private val TEST_ROOTS = listOf("/src/test/", "/src/integrationTest/", "/src/testFixtures/",
                                    "/src/acceptanceTest/", "/tools/test-e2e/")
  }
}
