#!/usr/bin/env python3
"""Build a dependency topology for the types a change set touches.

Reads a git repository and a change set (a pull request, or an explicit base and
head), works out which types each changed file declares, and resolves how those
types reach one another. Where two changed types are joined only through types
the change set does not touch, the connecting types are pulled in as bridges so
the path is visible.

Emits JSON on stdout, or to --out. Rendering lives in separate scripts.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from collections import defaultdict, deque

PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.$]+(?:\.\*)?)\s*;", re.M)
DECL_RE = re.compile(
    r"\b(?:public|protected|private|abstract|final|sealed|static|\s)*"
    r"(class|interface|enum|record|@interface)\s+(\w+)"
)
EXTENDS_RE = re.compile(r"\bextends\s+([\w.]+)")
IMPLEMENTS_RE = re.compile(r"\bimplements\s+([^{]+)")
FIELD_RE = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:private|protected|public)\s+"
    r"(?:static\s+)?(?:final\s+)?([A-Z][\w.]*)\s*(?:<[^;=]*>)?(?:\[\])?\s+\w+\s*[;=]",
    re.M,
)
IDENTIFIER_RE = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\b")
COMMENT_RE = re.compile(r"/\*.*?\*/|//[^\n]*", re.S)
STRING_RE = re.compile(r'"(?:\\.|[^"\\])*"')
ANNOTATION_RE = re.compile(r"@([A-Z]\w*)")

# Reference kinds, strongest first. A stronger kind wins when the same pair is
# seen twice, because a subclass that also calls its parent is still a subclass.
KIND_RANK = {"extends": 0, "implements": 1, "injects": 2, "uses": 3}

SOURCE_ROOTS = ("/src/main/java/", "/src/test/java/", "/src/integrationTest/java/",
                "/src/testFixtures/java/", "/src/main/kotlin/", "/src/test/kotlin/")
TEST_ROOTS = ("/src/test/", "/src/integrationTest/", "/src/testFixtures/",
              "/src/acceptanceTest/", "/tools/test-e2e/")


def run(repo, *args, check=True):
    result = subprocess.run(
        list(args), cwd=repo, capture_output=True, text=True, check=False
    )
    if check and result.returncode != 0:
        raise SystemExit(f"command failed: {' '.join(args)}\n{result.stderr.strip()}")
    return result.stdout


def read_blobs(repo, ref, paths):
    """Read many files out of a ref in one pass.

    The head of a pull request usually holds files the working tree does not, so
    every read goes through the ref rather than the checkout. One `git cat-file
    --batch` keeps that to a single process for the whole repository.
    """
    if not paths:
        return {}
    request = "".join(f"{ref}:{path}\n" for path in paths).encode()
    proc = subprocess.run(["git", "cat-file", "--batch"], cwd=repo,
                          input=request, stdout=subprocess.PIPE, check=True)
    out = proc.stdout
    contents, offset = {}, 0
    for path in paths:
        end = out.find(b"\n", offset)
        if end < 0:
            break
        header = out[offset:end].decode("utf-8", "replace").split()
        offset = end + 1
        if len(header) < 3:          # "<object> missing"
            continue
        size = int(header[2])
        contents[path] = out[offset:offset + size].decode("utf-8", "replace")
        offset += size + 1
    return contents


# --------------------------------------------------------------------------
# change set
# --------------------------------------------------------------------------

def resolve_pr(repo, pr):
    raw = run(repo, "gh", "pr", "view", str(pr), "--json",
              "baseRefName,headRefOid,number,title,url,headRefName")
    data = json.loads(raw)
    head = data["headRefOid"]
    if run(repo, "git", "cat-file", "-t", head, check=False).strip() != "commit":
        run(repo, "git", "fetch", "origin", data["headRefName"])
    return data


def change_set(repo, base, head):
    merge_base = run(repo, "git", "merge-base", base, head).strip()
    if not merge_base:
        raise SystemExit(f"no merge base between {base} and {head}")
    # --no-renames keeps every path literal. Rename detection prints a rewrite
    # form such as "dir/{old => new}.java", which is not a path any reader or
    # any later git command can use.
    numstat = run(repo, "git", "diff", "--no-renames", "--numstat", f"{merge_base}..{head}")
    status = run(repo, "git", "diff", "--no-renames", "--name-status", f"{merge_base}..{head}")

    statuses = {}
    for line in status.splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            statuses[parts[-1]] = parts[0][0]

    files = []
    for line in numstat.splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        adds, dels, path = parts
        files.append({
            "path": path,
            "additions": int(adds) if adds.isdigit() else 0,
            "deletions": int(dels) if dels.isdigit() else 0,
            "status": {"A": "added", "M": "modified", "D": "deleted",
                       "R": "renamed"}.get(statuses.get(path, "M"), "modified"),
        })
    return merge_base, files


# --------------------------------------------------------------------------
# source index
# --------------------------------------------------------------------------

def strip_noise(text):
    """Remove comments and string literals so they cannot contribute references."""
    return STRING_RE.sub('""', COMMENT_RE.sub(" ", text))


def package_of(path, text):
    match = PACKAGE_RE.search(text)
    if match:
        return match.group(1)
    for root in SOURCE_ROOTS:
        if root in path:
            tail = path.split(root, 1)[1]
            return os.path.dirname(tail).replace("/", ".")
    return ""


def module_of(path):
    for marker in ("/src/", "/resources/"):
        if marker in path:
            return path.split(marker, 1)[0]
    return os.path.dirname(path)


class Index:
    """Every type the repository declares, and what each one references."""

    def __init__(self, repo, ref):
        self.repo = repo
        self.ref = ref
        self.by_fqn = {}            # fqn -> path
        self.by_package = defaultdict(dict)   # package -> {simple: fqn}
        self.text = {}              # path -> stripped source
        self.decl_line = {}         # path -> 1-based line of the type declaration
        self._refs = {}             # fqn -> {target fqn: kind}

    def build(self, paths):
        paths = [path for path in paths if path.endswith((".java", ".kt"))]
        for path, raw in read_blobs(self.repo, self.ref, paths).items():
            text = strip_noise(raw)
            self.text[path] = text
            pkg = package_of(path, text)
            simple = os.path.basename(path).rsplit(".", 1)[0]
            fqn = f"{pkg}.{simple}" if pkg else simple
            self.by_fqn[fqn] = path
            self.by_package[pkg][simple] = fqn
            self.decl_line[path] = declaration_line(text, simple)

    def fqn_for_path(self, path):
        text = self.text.get(path)
        if text is None:
            return None
        pkg = package_of(path, text)
        simple = os.path.basename(path).rsplit(".", 1)[0]
        return f"{pkg}.{simple}" if pkg else simple

    def resolve_import(self, imported):
        """Map an import to a top-level type, folding a nested type onto its outer type."""
        if imported.endswith(".*"):
            return None
        candidate = imported
        while candidate:
            if candidate in self.by_fqn:
                return candidate
            head, _, tail = candidate.rpartition(".")
            if not head or not tail or not tail[0].isupper():
                return None
            candidate = head
        return None

    def refs(self, fqn):
        """Types this type references, each with the strongest relationship found."""
        cached = self._refs.get(fqn)
        if cached is not None:
            return cached

        path = self.by_fqn.get(fqn)
        if path is None:
            self._refs[fqn] = {}
            return {}
        text = self.text.get(path, "")
        pkg = package_of(path, text)
        simple = os.path.basename(path).rsplit(".", 1)[0]

        # simple name -> fqn, from explicit imports first, then the same package
        visible = {}
        for imported in IMPORT_RE.findall(text):
            target = self.resolve_import(imported)
            if target:
                visible[target.rsplit(".", 1)[1]] = target
        for other_simple, other_fqn in self.by_package.get(pkg, {}).items():
            visible.setdefault(other_simple, other_fqn)

        found = {}

        def record(name, kind):
            target = visible.get(name)
            if not target or target == fqn:
                return
            existing = found.get(target)
            if existing is None or KIND_RANK[kind] < KIND_RANK[existing]:
                found[target] = kind

        # the declaration of this type, so extends and implements are not mistaken
        # for the same words inside a nested type
        decl_index = text.find(f" {simple}")
        window = text[max(0, decl_index - 200): decl_index + 400] if decl_index >= 0 else ""
        window = strip_type_parameters(window)
        extends = EXTENDS_RE.search(window)
        if extends:
            record(extends.group(1).rsplit(".", 1)[-1], "extends")
        implements = IMPLEMENTS_RE.search(window)
        if implements:
            for part in implements.group(1).split(","):
                name = part.strip().split("<")[0].rsplit(".", 1)[-1]
                if name:
                    record(name, "implements")

        for field_type in FIELD_RE.findall(text):
            record(field_type.rsplit(".", 1)[-1], "injects")
        for param_type in constructor_param_types(text, simple):
            record(param_type, "injects")

        for name in set(IDENTIFIER_RE.findall(text)):
            record(name, "uses")

        self._refs[fqn] = found
        return found

    def annotations(self, fqn):
        path = self.by_fqn.get(fqn)
        if not path:
            return set()
        return set(ANNOTATION_RE.findall(self.text.get(path, "")))

    def kind_of(self, fqn):
        path = self.by_fqn.get(fqn)
        if not path:
            return "type"
        text = self.text.get(path, "")
        simple = os.path.basename(path).rsplit(".", 1)[0]
        for match in DECL_RE.finditer(text):
            if match.group(2) == simple:
                return match.group(1)
        return "type"


def strip_type_parameters(text):
    """Remove every <...> group, brackets included.

    A type parameter carries its own `extends`: in
    `CommonTransferProcessor<T extends CustomerProductInstructionRecord, …>` the
    bound is not a superclass, so reading it as one invents an inheritance edge
    that does not exist.
    """
    out, depth = [], 0
    for char in text:
        if char == "<":
            depth += 1
        elif char == ">":
            depth = max(0, depth - 1)
        elif depth == 0:
            out.append(char)
    return "".join(out)


def declaration_line(text, simple):
    """1-based line of `class Simple`, so an editor opens on the type rather than
    on the licence header."""
    for match in DECL_RE.finditer(text):
        if match.group(2) == simple:
            return text.count("\n", 0, match.start()) + 1
    return 1


def constructor_param_types(text, simple):
    """Parameter types of every constructor, which is the injection surface."""
    types = []
    for match in re.finditer(rf"\b{re.escape(simple)}\s*\(", text):
        start = match.end()
        depth = 1
        index = start
        while index < len(text) and depth:
            if text[index] == "(":
                depth += 1
            elif text[index] == ")":
                depth -= 1
            index += 1
        params = text[start: index - 1]
        for param in split_params(params):
            head = param.strip().split("<")[0].split()[0] if param.strip() else ""
            head = head.lstrip("@").rstrip("[]").rsplit(".", 1)[-1]
            if head and head[0].isupper():
                types.append(head)
            # a generic argument is a real dependency too: ExpectationPublisher<TransferExpectation>
            for inner in re.findall(r"<([^>]*)>", param):
                for piece in inner.split(","):
                    name = piece.strip().rsplit(".", 1)[-1]
                    if name and name[0].isupper():
                        types.append(name)
    return types


def split_params(params):
    out, depth, current = [], 0, []
    for char in params:
        if char in "<(":
            depth += 1
        elif char in ">)":
            depth -= 1
        if char == "," and depth == 0:
            out.append("".join(current))
            current = []
        else:
            current.append(char)
    if current:
        out.append("".join(current))
    return out


# --------------------------------------------------------------------------
# graph
# --------------------------------------------------------------------------

def is_test_path(path):
    return any(root in path for root in TEST_ROOTS)


def build_graph(index, roots, max_bridge, max_shared_callers, test_bridges, hub_fan_in):
    """Changed types, plus the unchanged types that join them together."""
    forward = {}
    for fqn in index.by_fqn:
        forward[fqn] = index.refs(fqn)

    def may_bridge(fqn):
        # An untouched test class that names two changed types explains nothing
        # about how production code fits together, so it stays out by default.
        return test_bridges or not is_test_path(index.by_fqn.get(fqn, ""))

    reverse = defaultdict(set)
    for source, targets in forward.items():
        for target in targets:
            reverse[target].add(source)

    selected = set(roots)
    bridges = {}

    # paths from one changed type down to another, through untouched types
    for start in roots:
        distance = {start: 0}
        parent = {}
        queue = deque([start])
        while queue:
            node = queue.popleft()
            if distance[node] >= max_bridge:
                continue
            for target in forward.get(node, {}):
                if target in distance:
                    continue
                distance[target] = distance[node] + 1
                parent[target] = node
                queue.append(target)
        for end in roots:
            if end == start or end not in parent:
                continue
            chain, node = [], end
            while node != start:
                node = parent[node]
                if node != start and node not in roots:
                    chain.append(node)
            if all(may_bridge(node) for node in chain):
                for node in chain:
                    bridges[node] = "path"
                    selected.add(node)

    # untouched types that call more than one changed type, which is how two
    # changed types relate when neither one calls the other
    shared = []
    for candidate, targets in forward.items():
        if candidate in selected or not may_bridge(candidate):
            continue
        hits = [t for t in targets if t in roots]
        if len(hits) >= 2:
            shared.append((len(hits), len(targets), candidate))
    shared.sort(key=lambda item: (-item[0], item[1]))
    for _, _, candidate in shared[:max_shared_callers]:
        bridges[candidate] = "caller"
        selected.add(candidate)

    # The other way round: a base class or an interface that two changed types
    # both depend on. Two sibling implementations relate through it and through
    # nothing else, so without it they read as unrelated changes.
    #
    # Only a shared dependency that joins parts of the map still separate earns a
    # place. Where the two changed types already reach each other, naming what
    # they both inject adds a row and no link, which is how a map of 23 changed
    # types grows 27 untouched ones and stops being readable. A type the whole
    # repository depends on is skipped for the same reason: "everything holds it"
    # says nothing about this change.
    groups = {node: node for node in selected}

    def find(node):
        while groups[node] != node:
            groups[node] = groups[groups[node]]
            node = groups[node]
        return node

    # A type the whole repository names joins everything to everything, so it is
    # not a connection for this test. Counting it as one makes every part of the
    # map look already joined, and no bridge is ever found.
    for source in selected:
        for target in forward.get(source, {}):
            if target in groups and len(reverse.get(target, ())) < hub_fan_in:
                a, b = find(source), find(target)
                if a != b:
                    groups[a] = b

    common = []
    for candidate in {t for root in roots for t in forward.get(root, {})}:
        if candidate in selected or not may_bridge(candidate):
            continue
        if len(reverse.get(candidate, ())) >= hub_fan_in:
            continue
        dependants = [root for root in roots if candidate in forward.get(root, {})]
        if len({find(root) for root in dependants}) >= 2:
            # A shared base class or interface explains far more than a service two
            # types happen to inject, so the strongest relationship wins the slot.
            strength = min(KIND_RANK[forward[root][candidate]] for root in dependants)
            common.append((strength, -len(dependants),
                           len(reverse.get(candidate, ())), candidate))
    common.sort()
    for _, _, _, candidate in common[:max_shared_callers]:
        dependants = [root for root in roots if candidate in forward.get(root, {})]
        if len({find(root) for root in dependants}) < 2:
            continue                      # an earlier bridge already joined them
        bridges[candidate] = "shared"
        selected.add(candidate)
        groups[candidate] = candidate
        for root in dependants:
            a, b = find(root), find(candidate)
            if a != b:
                groups[a] = b

    edges = []
    for source in selected:
        for target, kind in forward.get(source, {}).items():
            if target in selected:
                edges.append({"from": source, "to": target, "kind": kind})
    return selected, bridges, edges, forward, reverse


def layer_nodes(nodes, edges):
    """Longest-path layering. Back edges found by depth-first search are ignored,
    because a cycle has no layering and dropping the edge that closes it keeps the
    rest of the ordering true."""
    out = defaultdict(list)
    for edge in edges:
        out[edge["from"]].append(edge["to"])

    colour = {}
    back = set()

    def visit(node):
        colour[node] = 1
        for target in out.get(node, []):
            state = colour.get(target, 0)
            if state == 1:
                back.add((node, target))
            elif state == 0:
                visit(target)
        colour[node] = 2

    for node in sorted(nodes):
        if colour.get(node, 0) == 0:
            visit(node)

    acyclic = defaultdict(list)
    indegree = {node: 0 for node in nodes}
    for edge in edges:
        pair = (edge["from"], edge["to"])
        if pair in back:
            continue
        acyclic[edge["from"]].append(edge["to"])
        indegree[edge["to"]] += 1

    layer = {node: 0 for node in nodes}
    queue = deque(sorted(node for node in nodes if indegree[node] == 0))
    seen = 0
    while queue:
        node = queue.popleft()
        seen += 1
        for target in acyclic.get(node, []):
            layer[target] = max(layer[target], layer[node] + 1)
            indegree[target] -= 1
            if indegree[target] == 0:
                queue.append(target)
    return layer, back


def role_of(index, fqn, path):
    annotations = index.annotations(fqn)
    kind = index.kind_of(fqn)
    name = fqn.rsplit(".", 1)[-1]
    if any(root in path for root in TEST_ROOTS):
        return "test"
    if "Scheduled" in annotations or name.endswith("Scheduler"):
        return "scheduler"
    if "Controller" in annotations or name.endswith("Controller"):
        return "controller"
    if "Client" in annotations or name.endswith("Client"):
        return "client"
    if kind == "enum":
        return "enum"
    if name.endswith("Repository"):
        return "repository"
    if name.endswith("Strategy"):
        return "strategy"
    if name.endswith("Processor"):
        return "processor"
    if name.endswith(("Operations", "Orchestrator")):
        return "operations"
    if name.endswith("Service"):
        return "service"
    if name.startswith("Validate") or name.endswith("Validator"):
        return "validator"
    if kind == "interface":
        return "interface"
    return "type"


# --------------------------------------------------------------------------
# entry point
# --------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", default=".", help="repository root (default: current directory)")
    parser.add_argument("--pr", help="pull request number; resolves base and head through gh")
    parser.add_argument("--base", help="base ref, when --pr is not given")
    parser.add_argument("--head", default="HEAD", help="head ref (default: HEAD)")
    parser.add_argument("--max-bridge", type=int, default=3,
                        help="longest path between two changed types to follow (default: 3)")
    parser.add_argument("--max-shared-callers", type=int, default=12,
                        help="most untouched callers of two or more changed types to keep (default: 12)")
    parser.add_argument("--hub-fan-in", type=int, default=50,
                        help="repo-wide reference count above which a shared dependency is "
                             "too widely used to explain anything (default: 50)")
    parser.add_argument("--no-tests", action="store_true",
                        help="leave test sources and test resources out of the change set")
    parser.add_argument("--test-bridges", action="store_true",
                        help="let untouched test classes bridge two changed types")
    parser.add_argument("--out", help="write JSON here instead of stdout")
    args = parser.parse_args()

    repo = os.path.abspath(args.repo)
    meta = {"repo": repo}

    if args.pr:
        pr = resolve_pr(repo, args.pr)
        base = f"origin/{pr['baseRefName']}"
        head = pr["headRefOid"]
        meta.update({"pr": pr["number"], "title": pr["title"], "url": pr["url"],
                     "branch": pr["headRefName"]})
    else:
        if not args.base:
            raise SystemExit("give --pr, or --base and --head")
        base, head = args.base, args.head

    merge_base, files = change_set(repo, base, head)
    meta.update({"base": base, "head": head, "merge_base": merge_base,
                 "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())})

    tracked = run(repo, "git", "ls-tree", "-r", "--name-only", head).splitlines()
    index = Index(repo, head)
    index.build(tracked)

    if args.no_tests:
        files = [entry for entry in files if not is_test_path(entry["path"])]
    changed_paths = {entry["path"]: entry for entry in files}
    roots, unlinked = {}, []
    for path, entry in changed_paths.items():
        fqn = index.fqn_for_path(path) if path.endswith((".java", ".kt")) else None
        if fqn and fqn in index.by_fqn:
            roots[fqn] = entry
        else:
            unlinked.append(entry)

    changed_source = read_blobs(repo, head, [index.by_fqn[fqn] for fqn in roots])

    selected, bridges, edges, forward, reverse = build_graph(
        index, set(roots), args.max_bridge, args.max_shared_callers,
        args.test_bridges, args.hub_fan_in
    )
    layer, back_edges = layer_nodes(selected, edges)

    # a changed file that declares no type still belongs to the reader's picture,
    # so say which changed types it names
    root_simple = {fqn.rsplit(".", 1)[-1]: fqn for fqn in roots}
    literals = defaultdict(set)
    for fqn, path in ((fqn, index.by_fqn[fqn]) for fqn in roots):
        for literal in STRING_RE.findall(changed_source.get(path, "")):
            value = literal[1:-1]
            # An all-capitals token is a domain constant that turns up everywhere,
            # so it identifies nothing; a longer mixed-case literal is a job name,
            # a step name or a message, and those do identify their owner.
            if len(value) >= 8 and not re.fullmatch(r"[A-Z0-9_]+", value):
                literals[value].add(fqn)

    bodies = read_blobs(repo, head, [entry["path"] for entry in unlinked])
    for entry in unlinked:
        body = bodies.get(entry["path"], "")
        related = {fqn for simple, fqn in root_simple.items() if simple in body}
        # A resource file names no type, so the shared string is the only honest
        # link: an e2e feature calls a job by the literal the Java side declares.
        for value, owners in literals.items():
            if value in body:
                related |= owners
        entry["related"] = sorted(related)

    nodes = []
    for fqn in sorted(selected):
        path = index.by_fqn.get(fqn, "")
        entry = roots.get(fqn)
        nodes.append({
            "id": fqn,
            "name": fqn.rsplit(".", 1)[-1],
            "package": fqn.rsplit(".", 1)[0] if "." in fqn else "",
            "path": path,
            "module": module_of(path),
            "kind": index.kind_of(fqn),
            "role": role_of(index, fqn, path),
            "changed": entry is not None,
            "bridge": bridges.get(fqn),
            "status": entry["status"] if entry else None,
            "additions": entry["additions"] if entry else 0,
            "deletions": entry["deletions"] if entry else 0,
            "line": index.decl_line.get(path, 1),
            "layer": layer.get(fqn, 0),
            "fan_in": len(reverse.get(fqn, ())),
            "fan_out": len(forward.get(fqn, {})),
        })

    for edge in edges:
        edge["back"] = (edge["from"], edge["to"]) in back_edges

    payload = {
        "meta": meta,
        "nodes": nodes,
        "edges": sorted(edges, key=lambda e: (e["from"], e["to"])),
        "unlinked": sorted(unlinked, key=lambda e: e["path"]),
    }

    text = json.dumps(payload, indent=2)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
        print(f"{args.out}: {len(nodes)} nodes ({len(roots)} changed, "
              f"{len(bridges)} bridging), {len(edges)} edges, {len(unlinked)} unlinked files",
              file=sys.stderr)
    else:
        print(text)


if __name__ == "__main__":
    main()
