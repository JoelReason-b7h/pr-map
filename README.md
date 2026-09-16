# PR Map

An IntelliJ plugin. It draws the types a change set touches and how they reach each other.
Click a box to open that type's diff against the base.

![tool window](docs/screenshot.png)

## Why

GitHub lists the files a pull request changes in path order. Path order does not show which
file starts the change, what it pulls in, or which parts are independent.

## Install

Build or download `pr-map-<version>.zip`. In IntelliJ: **Settings → Plugins → the gear icon
→ Install Plugin from Disk…**, pick the zip, restart. A **PR Map** tool window appears on
the right of every project window.

Needs `git`, and `gh` for pull request mode.

## Use

Pick a source, then press **Draw**.

| source | maps |
| --- | --- |
| current branch | `HEAD` against the base ref beside it, starting at `origin/main` |
| base…head | any two refs you type |
| pull request | a PR number, read through `gh` |

The base ref is editable in the first two. **no tests** leaves test sources and test
resources out. The line under the toolbar shows the two refs being compared.

Drag to pan. Use the buttons or **Fit** to frame the diagram. Press twice in the same
place and drag to zoom: up zooms in, down zooms out, anchored where you pressed.

Click a changed type for a diff, with the merge-base on the left and your working tree on
the right. Click an untouched type to open the file. The diff's right side is the working
tree, so uncommitted edits appear in it.

## The diagram

- **Square box**: a type the change set touches. Green is added, amber is modified.
- **Rounded dashed box**: an untouched type. It appears when it lies on a path between two
  changed types, or when two changed types depend on it.
- **Parallelogram**: a type the whole repository names, such as a status enum. Drawn on its
  own, since wiring it in connects every branch to every other one.
- **Arrow labels**: `extends`, `implements`, `holds` (a constructor parameter or a field),
  and `calls`.

`calls` edges are hidden. One is drawn when it is the only edge either of its two types
has, which keeps a unit test attached to the class it tests.

Untouched types that only name two changed types are left out. `Diagram.build` draws them
when `withCallers` is set, and nothing in the tool window sets it.

## How it works

Each changed `.java` file maps to the type it declares. For each type the analysis records
what it extends, what it implements, what it holds, and what else it names. When two types
relate twice, the strongest relationship wins.

An untouched type is added when it:

- lies on a path from one changed type to another;
- names two or more changed types;
- is a dependency of two or more changed types that nothing else connects.

A type the whole repository names does not count as a connection while the analysis checks
what is already connected. One status enum would otherwise make everything look connected.

A changed file that declares no type, such as a `.feature` file, is listed separately.

### Where the facts come from

References come from the IDE index, resolved with `resolve()`. Imports, wildcard imports,
static imports, same-package names, nested types and generic bounds resolve as the compiler
resolves them. References to a changed type come from `ReferencesSearch`.

The change set comes from git. Changed files are read from the ref being mapped and parsed
in memory, so a pull request maps without checking out its branch. They resolve against the
project index, and a name the index cannot resolve is matched against the change set, which
covers references to classes the change set adds. When the ref is the current checkout,
files are read from disk instead, so uncommitted edits count.

### Limits

A box is a type, so a click opens a type. Each relationship stores the line of the
reference, and nothing uses it yet.

References made through reflection or configuration are invisible.

The first draw after opening a project waits for indexing. Java only.

## Layout

```
src/main/kotlin/dev/joelreason/prmap/
  PrMapToolWindowFactory.kt   registers the tool window
  PrMapPanel.kt               toolbar, embedded browser, opening a file or a diff
  PsiAnalyser.kt              resolves the change set into the topology
  Git.kt                      what changed, and between which two commits
  Topology.kt                 the model the analysis produces
  Diagram.kt                  the drawing rules, and the Mermaid text
  Page.kt                     the page: zoom, pan, and the click back to the plugin
  WrapLayout.kt               a toolbar that wraps instead of hiding its controls
  Diagnose.kt                 renders a topology file outside the IDE
src/main/resources/prmap/
  mermaid.min.js              vendored
tools/
  topology.py                 the same analysis over text, for testing without an IDE
```

Mermaid is vendored. A page built from a string has an opaque origin, which blocks a module
import, and the diagram has to draw with no network.

`tools/topology.py` answers the same question by reading the repository's text through one
`git cat-file --batch`. It resolves less than the IDE index does. It runs anywhere without
an IDE, which is how the drawing rules were worked out, and it does not ship in the plugin.

## Versions

The version in `build.gradle.kts` is what tells one installed build from another, since
IntelliJ lists it and the zip is named after it. Bump it on every change worth installing.

- **0.2.2** — scroll to zoom removed, since it never behaved in the tool window. Git no
  longer runs on the UI thread or inside the read action, which is what made IntelliJ
  report the plugin as slow.
- **0.2.1** — the wheel step followed the distance scrolled instead of counting events.
  Superseded by 0.2.2, which removes the wheel handler.
- **0.2.0** — the analysis moved to the IDE index, so references come from `resolve()`
  instead of rules over the text. Java only. Changed files are read from the ref being
  mapped, so a pull request maps without checking out its branch.
- **0.1.0** — first version, with the analysis in a bundled Python script.

## Build

```bash
JAVA_HOME=~/.sdkman/candidates/java/17.0.3.6.1-amzn ./gradlew buildPlugin
# build/distributions/pr-map-<version>.zip
```

Everything targets JVM 17 against platform 2024.1, the last line that runs on 17. A plugin
built against it still loads in a newer IDE, since `plugin.xml` sets `since-build` and no
upper bound. Gradle 8.10 refuses to run on Java 25, so point `JAVA_HOME` at a 17.

To see what the drawing rules make of a change set without starting an IDE:

```bash
python3 tools/topology.py --base origin/main --head HEAD --no-tests \
  --out /tmp/map.json --repo /path/to/repo
./gradlew diagnose -Pmap=/tmp/map.json                             # counts and diagram text
./gradlew diagnose -Pmap=/tmp/map.json -Ppage=/tmp/out/index.html  # the page itself
```

The page it writes is the one the tool window loads. The screenshot above came from it.

## Analysis options

`tools/topology.py --help` lists them all. The plugin uses the same defaults and exposes
only **no tests**.

- `--pr N` — read a pull request through `gh` instead of two refs.
- `--no-tests` — leave test sources and test resources out.
- `--max-bridge N` (3) — the longest path between two changed types to follow.
- `--hub-fan-in N` (50) — the repo-wide reference count above which a type counts as named
  from everywhere.
- `--max-shared-callers N` (12) — the cap on each kind of bridge.
- `--test-bridges` — let untouched test classes connect two changed types. Off by default.
