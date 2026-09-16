package dev.joelreason.prmap

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files

/**
 * The page the tool window shows, written to a temporary directory and loaded over
 * `file://`.
 *
 * Mermaid ships inside the plugin and sits next to the page rather than coming from a
 * CDN, so the map draws with no network and nothing to block. A page loaded from a
 * string has an opaque origin, which stops a module import, so the file is what makes
 * the script tag work.
 */
object Page {

  /** Writes the page and its script, and returns the file to load. */
  fun write(topology: Topology, dropUses: Boolean, withCallers: Boolean, dark: Boolean): File {
    val dir = Files.createTempDirectory("prmap-page").toFile()
    dir.deleteOnExit()

    val script = File(dir, "mermaid.min.js")
    javaClass.getResourceAsStream("/prmap/mermaid.min.js").use { stream ->
      requireNotNull(stream) { "mermaid is missing from the plugin" }
      script.outputStream().use { stream.copyTo(it) }
    }
    script.deleteOnExit()

    val page = File(dir, "index.html")
    page.writeText(html(topology, dropUses, withCallers, dark))
    page.deleteOnExit()
    return page
  }

  fun html(topology: Topology, dropUses: Boolean, withCallers: Boolean, dark: Boolean): String {
    val diagram = Diagram.build(topology, dropUses, withCallers = withCallers)
    val key = Diagram.keys(topology.nodes)
    val detail = topology.nodes.associate { node ->
      key.getValue(node.id) to mapOf(
        "name" to node.name,
        "role" to node.role,
        "path" to node.path,
        "line" to node.line,
        "changed" to node.changed,
        "status" to node.status,
        "churn" to "+${node.additions} / −${node.deletions}",
        "links" to links(topology, node.id),
      )
    }

    return TEMPLATE
      .replace("__GROUND__", if (dark) "#1E2422" else "#FAFBFA")
      .replace("__INK__", if (dark) "#E4EDE8" else "#101E19")
      .replace("__SURFACE__", if (dark) "#28302D" else "#EFF3F0")
      .replace("__LINE__", if (dark) "#3A4441" else "#D2DDD7")
      .replace("__MUTED__", if (dark) "#9BAAA3" else "#53645C")
      .replace("__DIAGRAM__", escape(diagram))
      .replace("__DETAIL__", Gson().toJson(detail))
  }

  private fun links(topology: Topology, id: String): List<String> {
    val name = topology.nodes.associate { it.id to it.name }
    val verb = mapOf("extends" to "extends", "implements" to "implements",
                     "injects" to "holds", "uses" to "calls")
    val out = mutableListOf<String>()
    topology.edges.forEach { edge ->
      val label = verb[edge.kind] ?: edge.kind
      if (edge.from == id) out += "$label → ${name[edge.to]}"
      if (edge.to == id) out += "${name[edge.from]} $label this"
    }
    return out.distinct().sorted()
  }

  private fun escape(text: String) = text
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private val TEMPLATE = """
<!doctype html>
<html><head><meta charset="utf-8">
<style>
  :root { --ground:__GROUND__; --ink:__INK__; --surface:__SURFACE__;
          --line:__LINE__; --muted:__MUTED__; --accent:#3F7F63; }
  * { box-sizing: border-box; }
  html, body { height:100%; margin:0; }
  body { background:var(--ground); color:var(--ink); display:flex; flex-direction:column;
         font:13px/1.5 ui-sans-serif, system-ui, -apple-system, sans-serif; }
  #viewport { flex:1; overflow:hidden; position:relative; cursor:grab; }
  #viewport.drag { cursor:grabbing; }
  #stage { transform-origin:0 0; padding:16px; }
  #zoom { position:absolute; left:8px; top:8px; display:flex; gap:4px; align-items:center;
          background:var(--surface); border:1px solid var(--line); border-radius:6px;
          padding:3px 5px; z-index:5; }
  #zoom button { font:inherit; font-size:12px; line-height:1; padding:4px 8px; cursor:pointer;
                 border:1px solid var(--line); border-radius:4px; background:var(--ground);
                 color:var(--ink); }
  #zoom button:hover { border-color:var(--accent); }
  #zoom span { color:var(--muted); font-size:11px; min-width:36px; text-align:center; }
  g.node { cursor:pointer; }
  g.node.sel rect, g.node.sel polygon { stroke-width:3px !important; }
</style></head>
<body>
<div id="viewport">
  <div id="zoom">
    <button id="z-out" title="Zoom out">&minus;</button>
    <span id="z-pct">100%</span>
    <button id="z-in" title="Zoom in">+</button>
    <button id="z-fit" title="Fit to the window">Fit</button>
  </div>
  <div id="stage"><pre class="mermaid">__DIAGRAM__</pre></div>
</div>
<script src="mermaid.min.js"></script>
<script>
  window.DETAIL = __DETAIL__;
  // The bundled build is UMD, so it defines a global. If that global is missing the
  // script did not load, and the page would otherwise show the diagram source as text
  // with no explanation.
  if (typeof mermaid === "undefined") {
    document.getElementById("stage").innerHTML =
      "<p style='padding:16px'>mermaid.min.js did not load, so the diagram cannot be drawn.</p>";
  } else {
    mermaid.initialize({ startOnLoad:false, theme:"base", flowchart:{ curve:"basis" },
      themeVariables:{ fontFamily:"ui-sans-serif, system-ui, sans-serif", fontSize:"13px",
                       lineColor:"__MUTED__" } });
    // run() is called rather than relying on startOnLoad, because this script runs after
    // the document has already loaded and the automatic pass would never fire.
    mermaid.run({ querySelector: ".mermaid" });
  }
</script>
<script>
(function () {
  var vp = document.getElementById("viewport"), stage = document.getElementById("stage");
  var pct = document.getElementById("z-pct");
  var scale = 1, tx = 0, ty = 0;

  function apply() {
    stage.style.transform = "translate("+tx+"px,"+ty+"px) scale("+scale+")";
    pct.textContent = Math.round(scale*100) + "%";
  }
  function zoomAt(px, py, factor) {
    var next = Math.max(0.1, Math.min(6, scale*factor));
    tx = px - (px-tx)*(next/scale); ty = py - (py-ty)*(next/scale); scale = next; apply();
  }
  function fit() {
    var svg = stage.querySelector("svg"); if (!svg) return;
    var b = svg.getBoundingClientRect(), w = b.width/scale, h = b.height/scale;
    scale = Math.max(0.1, Math.min((vp.clientWidth-32)/w, (vp.clientHeight-32)/h, 1.5));
    tx = (vp.clientWidth - w*scale)/2; ty = 16; apply();
  }
  document.getElementById("z-in").onclick = function () {
    zoomAt(vp.clientWidth/2, vp.clientHeight/2, 1.25);
  };
  document.getElementById("z-out").onclick = function () {
    zoomAt(vp.clientWidth/2, vp.clientHeight/2, 1/1.25);
  };
  document.getElementById("z-fit").onclick = fit;

  vp.addEventListener("wheel", function (e) {
    if (!e.ctrlKey && !e.metaKey) return; e.preventDefault();
    var r = vp.getBoundingClientRect();
    zoomAt(e.clientX-r.left, e.clientY-r.top, e.deltaY < 0 ? 1.1 : 1/1.1);
  }, { passive:false });

  // Panning must not capture the pointer on the way down. While a pointer is captured
  // the browser retargets the click to the capturing element, so a box would never
  // receive its own click and nothing would open. Capture starts only once the pointer
  // has travelled far enough to be a drag rather than a click.
  var DRAG_THRESHOLD = 4;
  var drag = false, captured = false, ox = 0, oy = 0, moved = false, sx = 0, sy = 0, pid = null;

  vp.addEventListener("pointerdown", function (e) {
    if (e.target.closest("#zoom")) return;
    drag = true; moved = false; captured = false; pid = e.pointerId;
    sx = e.clientX; sy = e.clientY; ox = e.clientX-tx; oy = e.clientY-ty;
  });
  vp.addEventListener("pointermove", function (e) {
    if (!drag) return;
    if (!moved && Math.abs(e.clientX-sx) + Math.abs(e.clientY-sy) < DRAG_THRESHOLD) return;
    if (!captured) {
      captured = true; moved = true; vp.classList.add("drag");
      try { vp.setPointerCapture(pid); } catch (err) { /* capture is optional */ }
    }
    tx = e.clientX-ox; ty = e.clientY-oy; apply();
  });
  ["pointerup","pointercancel"].forEach(function (n) {
    vp.addEventListener(n, function (e) {
      if (captured) { try { vp.releasePointerCapture(pid); } catch (err) {} }
      drag = false; captured = false; vp.classList.remove("drag");
      // A drag ends here, and the click that follows it must not open a file. The flag
      // is cleared after the click has been dispatched.
      setTimeout(function () { moved = false; }, 0);
    });
  });

  // Two ways back to the plugin, because one of them may not be installed: a function
  // the plugin injects, and a navigation the plugin intercepts. Whichever exists runs.
  function openInIde(node) {
    if (!node || !node.path) return;
    var payload = JSON.stringify({ path: node.path, line: node.line, changed: !!node.changed });
    if (window.openInIde && window.openInIde !== openInIde) {
      try { window.openInIde(payload); return; } catch (e) { /* fall through */ }
    }
    window.location.href = "prmap://open?payload=" + encodeURIComponent(payload);
  }

  function nodeId(g) {
    // Mermaid ids look like "flowchart-Name-3". The name itself can hold a dash only
    // when it came from a package, so the ends are stripped rather than split on.
    var raw = g.id || "";
    var parts = raw.split("-");
    if (parts.length >= 3 && parts[0] === "flowchart") return parts.slice(1, -1).join("-");
    return parts.length > 1 ? parts[1] : raw;
  }

  var tries = 0;
  var timer = setInterval(function () {
    var svg = stage.querySelector("svg");
    if (!svg && tries++ < 200) return;
    clearInterval(timer);
    if (!svg) {
      // The stage carries the failure, because the page has no status line of its own.
      stage.innerHTML = "<p style='padding:16px'>the diagram failed to draw</p>";
      return;
    }
    svg.style.maxWidth = "none";
    var nodes = svg.querySelectorAll("g.node");
    nodes.forEach(function (g) {
      var id = nodeId(g);
      g.addEventListener("click", function (e) {
        if (moved) return;                       // a drag that ended on a box is not a click
        e.stopPropagation();
        svg.querySelectorAll("g.node.sel").forEach(function (n) { n.classList.remove("sel"); });
        g.classList.add("sel");
        var node = (window.DETAIL || {})[id];
        if (!node) return;              // nothing recorded for this box
        openInIde(node);
      });
    });
    fit();
  }, 60);
})();
</script>
</body></html>
""".trimIndent()
}
