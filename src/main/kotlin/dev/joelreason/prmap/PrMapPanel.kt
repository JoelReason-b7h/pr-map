package dev.joelreason.prmap

import com.google.gson.Gson
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class PrMapPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

  private enum class Source(val label: String) {
    CURRENT_BRANCH("current branch"), REFS("base…head"), PR("pull request");
    override fun toString() = label
  }

  private val source = JComboBox(Source.entries.toTypedArray())
  private val baseField = JBTextField("origin/main", 10)
  private val headField = JBTextField("HEAD", 7)
  private val prField = JBTextField("", 5)
  private val excludeTests = JBCheckBox("no tests", true)
  private val baseLabel = JBLabel("base")
  private val headLabel = JBLabel("head")
  private val prLabel = JBLabel("PR")
  private val status = JBLabel(" ")
  private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
  /** The commit the drawn change set diverged from; a click diffs against it. */
  private var mergeBase: String? = null
  /** Read off the UI thread and kept, because asking git costs a subprocess and the
   *  toolbar asks on every keystroke in the base field. */
  @Volatile private var branchName: String? = null

  init {
    add(toolbar(), BorderLayout.NORTH)

    val view = browser
    if (view == null) {
      add(JBLabel("This IDE has no embedded browser, so the map cannot be drawn here."),
          BorderLayout.CENTER)
    } else {
      Disposer.register(this, view)
      installJump(view)
      add(view.component, BorderLayout.CENTER)
      view.loadHTML(placeholder())
    }
    add(status, BorderLayout.SOUTH)
    onSourceChanged()
    refreshBranchName()
  }

  private fun toolbar(): JPanel {
    // A wrapping layout, because a tool window docked narrow would otherwise push the
    // controls onto a row the panel never makes room for, and they vanish.
    val bar = JPanel(WrapLayout())
    bar.border = JBUI.Borders.empty(2, 4)
    source.addActionListener { onSourceChanged() }
    // Editing the base must refresh the line that names it, or that line goes stale and
    // claims a base the next Draw will not use.
    baseField.document.addDocumentListener(object : DocumentListener {
      override fun insertUpdate(event: DocumentEvent) = onSourceChanged()
      override fun removeUpdate(event: DocumentEvent) = onSourceChanged()
      override fun changedUpdate(event: DocumentEvent) = onSourceChanged()
    })
    bar.add(source)
    bar.add(baseLabel); bar.add(baseField)
    bar.add(headLabel); bar.add(headField)
    bar.add(prLabel); bar.add(prField)
    bar.add(excludeTests)
    val draw = JButton("Draw", AllIcons.Actions.Refresh)
    draw.addActionListener { draw() }
    bar.add(draw)
    return bar
  }

  private fun onSourceChanged() {
    val mode = source.selectedItem as Source
    val refs = mode == Source.REFS
    val pr = mode == Source.PR
    // The base stays editable while mapping the current branch, because which base the
    // branch is compared against is the one thing worth changing there. Only the head is
    // fixed, since that mode means HEAD.
    baseLabel.isVisible = !pr; baseField.isVisible = !pr
    headLabel.isVisible = refs; headField.isVisible = refs
    prLabel.isVisible = pr; prField.isVisible = pr
    if (mode == Source.CURRENT_BRANCH) {
      status.text = "  ${baseField.text} … " + (branchName ?: "HEAD")
    }
    revalidate(); repaint()
  }

  private fun refreshBranchName() {
    val root = repoRoot() ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val name = runCatching { Git(root).currentBranch() }.getOrNull()
      ApplicationManager.getApplication().invokeLater {
        branchName = name
        onSourceChanged()
      }
    }
  }

  /** Gives the page the function it calls when a box is clicked. */
  private fun installJump(view: JBCefBrowser) {
    val query = JBCefJSQuery.create(view as JBCefBrowserBase)
    Disposer.register(this, query)
    query.addHandler { payload -> receive(payload); null }

    view.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
      override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
        cefBrowser.executeJavaScript(
          "window.openInIde = function(payload) { ${query.inject("payload")} };",
          cefBrowser.url, 0
        )
      }
    }, view.cefBrowser)
  }

  private data class Target(val path: String = "", val line: Int = 1, val changed: Boolean = false)

  private fun receive(payload: String) {
    val target = try {
      Gson().fromJson(payload, Target::class.java)
    } catch (_: Exception) {
      null
    } ?: return
    ApplicationManager.getApplication().invokeLater { open(target) }
  }

  private fun open(target: Target) {
    val root = repoRoot() ?: return
    val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(root, target.path))
    if (file == null) return
    // A type the change set does not touch has nothing to compare, so it opens as a file.
    val base = mergeBase
    if (!target.changed || base == null) {
      OpenFileDescriptor(project, file, (target.line - 1).coerceAtLeast(0), 0).navigate(true)
      return
    }
    showDiff(target, file, base)
  }

  /** The file as it stands beside the version at the commit the change set diverged from. */
  private fun showDiff(target: Target, file: com.intellij.openapi.vfs.VirtualFile, base: String) {
    val root = repoRoot() ?: return
    val before = gitShow(root, base, target.path)
    val factory = DiffContentFactory.getInstance()
    val left = if (before == null) factory.createEmpty()
    else factory.create(project, before, file.fileType)
    val right = factory.create(project, file)
    val leftTitle = if (before == null) "not in ${base.take(10)} (added)" else base.take(10)
    DiffManager.getInstance().showDiff(
      project, SimpleDiffRequest(target.path, left, right, leftTitle, "working tree")
    )
  }

  /** null when the file did not exist at that commit, which is how an addition reads. */
  private fun gitShow(root: File, ref: String, path: String): String? = try {
    val process = ProcessBuilder("git", "show", "$ref:$path").directory(root).start()
    val text = process.inputStream.bufferedReader().readText()
    if (process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) text else null
  } catch (_: Exception) {
    null
  }

  private fun repoRoot(): File? = project.basePath?.let(::File)

  private fun draw() {
    val view = browser ?: return
    val root = repoRoot() ?: return
    val mode = source.selectedItem as Source
    val base = baseField.text.trim().ifBlank { "origin/main" }
    val head = if (mode == Source.REFS) headField.text.trim().ifBlank { "HEAD" } else "HEAD"
    val pr = if (mode == Source.PR) prField.text.trim() else ""
    val noTests = excludeTests.isSelected
    val branch = branchName
    status.text = "  reading…"

    object : Task.Backgroundable(project, "Building the PR map", true) {
      private var result: Topology? = null
      private var failure: String? = null

      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        try {
          result = PsiAnalyser(project, root).run(base, head, pr.ifBlank { null }, noTests, indicator)
        } catch (error: Exception) {
          failure = error.message ?: error.toString()
        }
      }

      override fun onSuccess() {
        val topology = result
        if (topology == null) {
          status.text = "  failed"
          Messages.showErrorDialog(project, failure ?: "unknown failure", "PR Map")
          return
        }
        mergeBase = topology.meta.mergeBase
        // Only the two refs the drawing compares. The counts are on screen, and a file
        // that opened is in the editor, so neither needs saying again. A branch name is
        // used wherever one is known, because a sha reads as nothing.
        val headName = when (mode) {
          Source.CURRENT_BRANCH -> branch ?: "HEAD"
          Source.REFS -> head
          Source.PR -> topology.meta.branch ?: "PR $pr"
        }
        status.text = "  $base … $headName"
        val page = Page.write(topology, dropUses = true, withCallers = false,
                              dark = !JBColor.isBright())
        view.loadURL(page.toURI().toString())
      }
    }.queue()
  }

  private fun placeholder(): String {
    val ground = if (JBColor.isBright()) "#FAFBFA" else "#1E2422"
    val ink = if (JBColor.isBright()) "#53645C" else "#9BAAA3"
    return """
      <html><body style="background:$ground;color:$ink;font:13px ui-sans-serif,system-ui,sans-serif;
                         display:flex;align-items:center;justify-content:center;height:100%;margin:0">
      <p>Press Draw to map the branch you have checked out.</p>
      </body></html>
    """.trimIndent()
  }

  override fun dispose() {}
}
