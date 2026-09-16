# PR Map

An IntelliJ plugin that draws the types a change set touches and how they reach one
another, so a large pull request has a place to start rather than an alphabetical list of
files. Clicking a box opens that type's diff against the base.

![tool window](docs/screenshot.png)

## What problem it solves

GitHub lists the files a pull request changes in path order. That order says nothing about
which file drives the change, which files it pulls in, or which parts are independent. A
reviewer has to rebuild that picture by hand every time.

This plugin builds it from the code. It resolves every changed file to the type it
declares, works out how those types reach one another, and pulls in the untouched types
that join two changed ones — so a path that runs through code the change set never touched
is still visible.

## Install

Build or download `pr-map-<version>.zip`, then in IntelliJ:

**Settings → Plugins → the gear icon → Install Plugin from Disk…** → pick the zip →
restart. A **PR Map** tool window appears on the right of every project window.

Needs `git` on the path, and `gh` for pull request mode. IntelliJ supplies everything else,
including the Java support the analysis depends on.

## Use

The toolbar has one selector with three sources:

| source | what it maps |
| --- | --- |
| current branch | `HEAD` against the base ref beside it, which starts at `origin/main` |
| base…head | any two refs you type |
| pull request | a PR number, resolved through the `gh` CLI |

The base ref stays editable in the first two, so a branch can be compared against a release
tag or another long-lived branch. Leave **no tests** ticked to keep test sources and test
resources out. Press **Draw**.

The line under the toolbar names the two refs the drawing compares, and nothing else.

In the diagram: drag to pan, scroll to zoom, and the buttons or **Fit** to frame it. A
second press in the same place drags the zoom, up to zoom in and down to zoom out, about
the point pressed. Clicking a box opens it —

- a **changed** type opens as a diff, with the file at the merge-base on the left and your
  working tree on the right;
- an **untouched** type opens as a plain file, because it has nothing to compare.

The right side of a diff is the working tree rather than the branch head, so uncommitted
edits appear in it. That is what you want while reviewing your own branch, and it is not
what GitHub shows for a pull request.

## Reading the diagram

- A **square** box is a type the change set touches. Green fill means added, amber means
  modified.
- A **rounded, dashed** box is untouched, and appears only because it lies on a path
  between two changed types, or because two changed types both depend on it.
- A **parallelogram** is a type the whole repository names, such as a status enum. It is
  drawn on its own, because wiring it in joins every branch to every other one and hides
  the structure.
- Arrow labels are the relationship: `extends`, `implements`, `holds` (a constructor
  parameter or a field, which is the injection graph) and `calls`.

Two rules keep a diagram of this size readable, and both are deliberate.

`calls` edges are hidden, because a class names many types it merely uses and drawing all
of them buries the inheritance and the injection. One is put back when it is the only edge
either of its two types has, since that type would otherwise be drawn with no line to
anything — which is what would happen to a unit test, whose only link to the class it tests
is a call.

Untouched types that merely name two changed types are also left out, because a dozen of
them all pointing at the same two services buries the shape. The analysis still finds them,
and `Diagram.build` draws them when `withCallers` is set, but nothing in the tool window
sets it — so today they are computed and not shown.

## How the analysis works

Every changed `.java` file maps to the type it declares. For each type the analysis records
what it extends, what it implements, what it holds and what it otherwise names, taking the
strongest relationship when two types relate twice.

Three kinds of untouched type then earn a place:

- one that lies on a path from one changed type down to another;
- one that names two or more changed types, which is the blast radius;
- one that two or more changed types depend on **and** that joins parts of the map
  otherwise separate — a shared base class or interface. A shared dependency between two
  types that already reach each other is left out, because it adds a row and no link.

A type the whole repository names does not count as a connection while the analysis looks
for what is already joined. Without that rule a single status enum makes everything look
connected and no bridge is ever found.

A changed file that declares no type — a `.feature` file, a resource — is reported
separately.

### Where the facts come from

References come from the IDE's own index. Each one is resolved with `resolve()`, so
imports, wildcard imports, static imports, same-package names, nested types and generic
bounds all resolve as the compiler sees them, rather than being imitated by rules over the
text. Which types reference a changed one — the blast radius — comes from
`ReferencesSearch` over the project.

The change set itself comes from git, because the index knows the code and git knows the
change. A changed file is read from the ref being mapped and parsed in memory, so a pull
request maps without checking its branch out; those files resolve against the project
index, and a name the index cannot resolve is matched against the change set, which is how
a reference to a class the change set itself adds survives. A ref that is already the
checkout reads from disk instead, so uncommitted edits count.

### Limits

A box is a type, so a click opens a type. Each relationship records the line where the
reference occurs, which is what a jump to the call site would need, but nothing uses it yet.

A reference reachable only through reflection or through configuration stays invisible,
because nothing static can see it.

The first draw after opening a project waits for indexing, since the analysis runs in smart
mode. Java only.

## Layout

```
src/main/kotlin/dev/joelreason/prmap/
  PrMapToolWindowFactory.kt   registers the tool window
  PrMapPanel.kt               toolbar, the embedded browser, and opening a file or a diff
  PsiAnalyser.kt              resolves the change set into the topology, through the index
  Git.kt                      what changed, and between which two commits
  Topology.kt                 the model the analysis produces
  Diagram.kt                  the drawing rules, and the Mermaid text
  Page.kt                     the page: zoom, pan, and the click that reaches the plugin
  WrapLayout.kt               a toolbar that wraps instead of hiding its controls
  Diagnose.kt                 renders a topology file outside the IDE
src/main/resources/prmap/
  mermaid.min.js              vendored, so the diagram draws with no network
tools/
  topology.py                 the same analysis by reading text, for testing without an IDE
```

Mermaid is vendored rather than loaded from a CDN because a page built from a string has an
opaque origin, which blocks a module import, and the diagram has to draw with no network.

`tools/topology.py` answers the same question by reading the repository's text through one
`git cat-file --batch`. It resolves less than the index does, which is why the plugin
stopped using it, but it runs anywhere with no IDE, and it is how the drawing rules were
worked out. It does not ship inside the plugin.

## Versions

The version in `build.gradle.kts` is the only thing that tells one installed build from
another, because IntelliJ lists it and the zip is named after it. Bump it on every change
worth installing.

- **0.2.1** — the wheel step follows the distance scrolled rather than counting events,
  so a trackpad no longer bolts.
- **0.2.0** — the analysis moved to IntelliJ's own resolved index, so references come from
  `resolve()` rather than from rules over the text. Java only. A changed file is read from
  the ref being mapped, so a pull request still maps without checking its branch out.
- **0.1.0** — first version, with the analysis in a bundled Python script.

## Build

```bash
JAVA_HOME=~/.sdkman/candidates/java/17.0.3.6.1-amzn ./gradlew buildPlugin
# build/distributions/pr-map-<version>.zip
```

Everything targets JVM 17 against platform 2024.1, which is the last line that runs on 17.
A plugin built against it still loads in a newer IDE — `plugin.xml` sets `since-build` and
no upper bound. Gradle 8.10 refuses to run on Java 25, so point `JAVA_HOME` at a 17.

To see what the drawing rules make of a real change set without starting an IDE:

```bash
python3 tools/topology.py --base origin/main --head HEAD --no-tests \
  --out /tmp/map.json --repo /path/to/repo
./gradlew diagnose -Pmap=/tmp/map.json                             # counts and diagram text
./gradlew diagnose -Pmap=/tmp/map.json -Ppage=/tmp/out/index.html  # the real page
```

The page it writes is the same one the tool window loads, which is how the screenshot in
this README was taken.

## Analysis options

`tools/topology.py --help` lists them all. The plugin applies the same defaults, and only
exposes **no tests** in its toolbar.

- `--pr N` — resolve a pull request through `gh`, rather than two refs.
- `--no-tests` — leave test sources and test resources out of the change set.
- `--max-bridge N` (3) — the longest path between two changed types to follow.
- `--hub-fan-in N` (50) — the repo-wide reference count above which a type counts as named
  from everywhere.
- `--max-shared-callers N` (12) — the cap on each kind of bridge.
- `--test-bridges` — let untouched test classes bridge two changed types. Off by default,
  because a test that names two changed types explains nothing about how production code
  fits together.
