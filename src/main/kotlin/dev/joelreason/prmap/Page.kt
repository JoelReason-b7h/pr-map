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
        "path" to node.path,
        "line" to node.line,
        "changed" to node.changed,
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
  /* The diagram source sits in the page as text until mermaid replaces it, and the
     bundled script takes a moment to parse, so the stage stays hidden until an svg
     exists. Opacity rather than display, because mermaid measures the text to lay the
     boxes out and a hidden element has no size. */
  #stage { transform-origin:0 0; padding:16px; opacity:0; }
  #stage.ready { opacity:1; }
  #loading { position:absolute; inset:0; display:flex; align-items:center;
             justify-content:center; color:var(--muted); font-size:12px; }
  #loading.gone { display:none; }
  @keyframes spin { to { transform:rotate(360deg); } }
  #loading i { width:12px; height:12px; margin-right:8px; border-radius:50%;
               border:2px solid var(--line); border-top-color:var(--accent);
               animation:spin 700ms linear infinite; }
  #zoom { position:absolute; left:8px; top:8px; display:flex; gap:4px; align-items:center;
          background:var(--surface); border:1px solid var(--line); border-radius:6px;
          padding:3px 5px; z-index:5; }
  #zoom button { font:inherit; font-size:12px; line-height:1; padding:4px 8px; cursor:pointer;
                 border:1px solid var(--line); border-radius:4px; background:var(--ground);
                 color:var(--ink); }
  #zoom button:hover { border-color:var(--accent); }
  #zoom span { color:var(--muted); font-size:11px; min-width:36px; text-align:center; }
  g.node { cursor:pointer; }

  /* Mermaid tags each arrow with LS-<source> and LE-<target>, which is what lets an
     arrow be found from the box it points at. */
  .flowchart-link { transition:opacity 120ms, stroke-width 120ms; }
  svg.focus .flowchart-link, svg.focus .edgeLabel { opacity:0.12; }
  svg.focus .flowchart-link.hot { opacity:1; stroke-width:2.5px; }
  .flowchart-link.done { opacity:0.1; }
  svg.focus .flowchart-link.done.hot { opacity:0.45; stroke-width:1.5px; }
  g.node.ticked { opacity:0.45; }
  g.tick { cursor:pointer; }
  g.tick rect { fill:var(--ground); stroke:var(--muted); stroke-width:1; }
  g.tick:hover rect { stroke:var(--accent); }
  g.tick .mark { display:none; fill:none; stroke:var(--accent); stroke-width:2;
                 stroke-linecap:round; stroke-linejoin:round; }
  g.node.ticked g.tick .mark { display:block; }
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
  <div id="loading"><i></i>drawing the map…</div>
</div>
<script src="mermaid.min.js"></script>
<script>
  window.DETAIL = __DETAIL__;
  // The bundled build is UMD, so it defines a global. If that global is missing the
  // script did not load, and the page would otherwise show the diagram source as text
  // with no explanation.
  if (typeof mermaid === "undefined") {
    document.getElementById("loading").textContent =
      "mermaid.min.js did not load, so the diagram cannot be drawn.";
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
  function scaleAt(px, py, next) {
    next = Math.max(0.1, Math.min(6, next));
    tx = px - (px-tx)*(next/scale); ty = py - (py-ty)*(next/scale); scale = next; apply();
  }
  function zoomAt(px, py, factor) { scaleAt(px, py, scale*factor); }
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


  // Panning must not capture the pointer on the way down. While a pointer is captured
  // the browser retargets the click to the capturing element, so a box would never
  // receive its own click and nothing would open. Capture starts only once the pointer
  // has travelled far enough to be a drag rather than a click.
  var DRAG_THRESHOLD = 4, DOUBLE_MS = 350, DOUBLE_SLOP = 8;
  var drag = false, captured = false, ox = 0, oy = 0, moved = false, sx = 0, sy = 0, pid = null;
  var zooming = false, anchorX = 0, anchorY = 0, startScale = 1;
  var lastUp = 0, lastUpX = 0, lastUpY = 0;

  vp.addEventListener("pointerdown", function (e) {
    if (e.target.closest("#zoom")) return;
    drag = true; moved = false; captured = false; pid = e.pointerId;
    sx = e.clientX; sy = e.clientY; ox = e.clientX-tx; oy = e.clientY-ty;

    // A second press soon after the first, in the same place, drags the zoom instead of
    // the diagram: up zooms in, down zooms out, about the point pressed.
    zooming = (Date.now() - lastUp) < DOUBLE_MS &&
              Math.abs(e.clientX-lastUpX) + Math.abs(e.clientY-lastUpY) < DOUBLE_SLOP;
    if (zooming) {
      var r = vp.getBoundingClientRect();
      anchorX = e.clientX-r.left; anchorY = e.clientY-r.top; startScale = scale;
      moved = true;                    // the press belongs to the gesture, not to a box
    }
  });
  vp.addEventListener("pointermove", function (e) {
    if (!drag) return;
    if (zooming) {
      if (!captured) {
        captured = true;
        try { vp.setPointerCapture(pid); } catch (err) { /* capture is optional */ }
      }
      scaleAt(anchorX, anchorY, startScale * Math.pow(1.006, sy - e.clientY));
      return;
    }
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
      lastUp = Date.now(); lastUpX = e.clientX; lastUpY = e.clientY;
      drag = false; captured = false; zooming = false; vp.classList.remove("drag");
      // A drag ends here, and the click that follows it must not open a file. The flag
      // is cleared after the click has been dispatched.
      setTimeout(function () { moved = false; }, 0);
    });
  });

  // The plugin installs window.openInIde once the page has loaded.
  function open(node) {
    if (!node || !node.path || !window.openInIde) return;
    window.openInIde(JSON.stringify(
      { path: node.path, line: node.line, changed: !!node.changed }));
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
      document.getElementById("loading").textContent = "the diagram failed to draw";
      return;
    }
    svg.style.maxWidth = "none";

    function arrowsInto(id) { return svg.querySelectorAll("path.LE-" + id); }
    function mark(id, name, on) {
      arrowsInto(id).forEach(function (path) { path.classList.toggle(name, on); });
    }

    var SVG_NS = "http://www.w3.org/2000/svg";
    function addTick(g, id) {
      var box = g.getBBox();
      var tick = document.createElementNS(SVG_NS, "g");
      tick.setAttribute("class", "tick");
      tick.setAttribute("transform", "translate(" + (box.x + 5) + "," + (box.y + 5) + ")");
      var rect = document.createElementNS(SVG_NS, "rect");
      rect.setAttribute("width", "13"); rect.setAttribute("height", "13");
      rect.setAttribute("rx", "3");
      var mark2 = document.createElementNS(SVG_NS, "path");
      mark2.setAttribute("class", "mark");
      mark2.setAttribute("d", "M3 6.8 L5.6 9.4 L10 4");
      tick.appendChild(rect); tick.appendChild(mark2);
      tick.addEventListener("pointerdown", function (e) { e.stopPropagation(); });
      tick.addEventListener("click", function (e) {
        e.stopPropagation();                       // a tick is not a request to open the file
        var done = g.classList.toggle("ticked");
        mark(id, "done", done);
      });
      g.appendChild(tick);
    }

    var nodes = svg.querySelectorAll("g.node");
    nodes.forEach(function (g) {
      var id = nodeId(g);
      addTick(g, id);

      // Hovering a box picks out what points at it, and fades everything else.
      g.addEventListener("mouseenter", function () {
        svg.classList.add("focus"); mark(id, "hot", true);
      });
      g.addEventListener("mouseleave", function () {
        svg.classList.remove("focus"); mark(id, "hot", false);
      });

      g.addEventListener("click", function (e) {
        if (moved) return;                       // a drag that ended on a box is not a click
        e.stopPropagation();
        svg.querySelectorAll("g.node.sel").forEach(function (n) { n.classList.remove("sel"); });
        g.classList.add("sel");
        var node = (window.DETAIL || {})[id];
        if (!node) return;              // nothing recorded for this box
        open(node);
      });
    });
    fit();
    document.getElementById("loading").classList.add("gone");
    stage.classList.add("ready");
  }, 60);
})();
</script>
</body></html>
""".trimIndent()
}
