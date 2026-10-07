#!/usr/bin/env python3
"""Generate every ``client/src/main/resources/mappings-<version>.json`` table.

The mapping *requirement* surface (which canonical classes/members the client
actually resolves through the table) comes from two places, merged:

* ``scan.py``       — ``ClassType`` constants + member string literals in
  ``client/src/main/java``;
* ``requirements.txt`` — the curated list for names that travel through a
  variable / GUI-font-input members added later.

Every requirement is resolved into **loader namespaces** (``vanilla`` /
``fabric`` / ``forge`` / ``neoforge``) by ``namespaces.py``; the runtime tries
those names in order, so one table serves a plain install, a Fabric install, a
Forge install and a NeoForge install of the same game version.  Anything that
cannot be resolved in a version is written as ``"absent": true`` and listed by
``--report`` — that list is the to-do list for patching an alias or a
requirement.

Usage::

    python tools/mapping/generate.py                 # write all tables
    python tools/mapping/generate.py --only 1.8.9    # one version
    python tools/mapping/generate.py --check         # fail if any table is stale
    python tools/mapping/generate.py --javap         # also verify against local jars
    python tools/mapping/generate.py --report        # print the absent-member lists

Stdlib only.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import javap
import namespaces
import scan
import sources


ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOOLS = os.path.join(ROOT, "tools", "mapping")
CACHE = os.path.join(TOOLS, "cache")
CLIENT_SRC = os.path.join(ROOT, "client", "src", "main", "java")
CLASS_TYPE = os.path.join(CLIENT_SRC, "dev", "nocturne", "client", "mapping", "ClassType.java")
REQUIREMENTS = os.path.join(TOOLS, "requirements.txt")
RESOURCES = os.path.join(ROOT, "client", "src", "main", "resources")
VANILLA_ROOT = os.path.join(ROOT, "..", "OpenVape4.21", "src", "main", "resources", "mappings")

DEFAULT_JAVAP = os.environ.get(
    "NOCTURNE_JAVAP",
    r"C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot\bin\javap.exe",
)

#: Oldest version the client supports.  Everything released after it is a
#: candidate; ``namespaces.build_resolver`` decides per version whether a
#: source exists (it returns ``None`` for versions older than Mojang's
#: mappings that have no alias bridge — those are reported, not guessed).
OLDEST = "1.8.9"


def release_versions(manifest: sources.Manifest) -> "list[str]":
    """Every release from ``OLDEST`` up to the newest, newest first."""
    versions = [v["id"] for v in manifest.manifest()["versions"] if v["type"] == "release"]
    floor = namespaces._version_key(OLDEST)
    selected = [v for v in versions if namespaces._version_key(v) >= floor]
    return list(reversed(selected))


def load_shape_hints() -> dict:
    """(canonical class, method) -> 1.8.9 parameter count.

    The shipped 1.8.9 table records which overload of a canonical method the
    client actually uses; reuse that shape when a modern Mojmap class exposes
    several overloads under the same name.
    """
    path = table_path("1.8.9")
    if not os.path.exists(path):
        return {}
    try:
        table = json.load(open(path, encoding="utf-8"))
    except (ValueError, OSError):
        return {}
    hints = {}
    for canonical, entry in table.get("classes", {}).items():
        for name, spec in (entry.get("methods") or {}).items():
            descriptor = _first_signature(spec)
            if descriptor:
                hints[(canonical, name)] = namespaces.parameter_count(descriptor)
    return hints


def _first_signature(spec: dict) -> "str | None":
    """The first recorded descriptor of a member entry, whatever its shape."""
    signatures = spec.get("signatures")
    if isinstance(signatures, dict):
        for namespace in namespaces.NAMESPACES:
            if signatures.get(namespace):
                return signatures[namespace]
    return spec.get("signature")


def build_anchor_table(requirements: scan.Requirements, manifest: sources.Manifest,
                       fetch: namespaces.Fetch, shape_hints: dict) -> "namespaces.AnchorTable | None":
    """canonical -> intermediary, learned on 1.12.2.

    Built in memory from the same legacy path that produces the 1.12.2 table, so
    an anchor run never depends on a previously written artefact and the order
    of ``--only`` arguments cannot change the result.
    """
    resolver = namespaces.build_resolver(
        "1.12.2", fetch, CACHE, manifest, VANILLA_ROOT, TOOLS, DEFAULT_JAVAP,
        find_local_jar("1.12.2"),
    )
    if resolver is None:
        return None
    table = build_table("1.12.2", resolver, requirements, {"versions": {}}, shape_hints)
    return namespaces.AnchorTable(table)


def find_local_jar(version: str) -> "str | None":
    candidates = [
        os.path.join(ROOT, "..", "analysis", f"client-{version}.jar"),
        os.path.join(CACHE, f"client-{version}.jar"),
    ]
    for candidate in candidates:
        if os.path.isfile(candidate):
            return os.path.normpath(candidate)
    return None


def build_table(version: str, resolver, requirements: scan.Requirements,
                report: dict, shape_hints: dict) -> dict:
    classes = {}
    resolved = 0
    absent = []
    for canonical in sorted(requirements.classes):
        class_names = resolver.resolve_class(canonical)
        if not class_names:
            classes[canonical] = {"absent": True, "names": {}, "methods": {}, "fields": {}}
            absent.append((canonical, None, f"no counterpart in {version}"))
            continue
        methods = {}
        fields = {}
        for name in sorted(requirements.methods[canonical]):
            hint = shape_hints.get((canonical, name))
            answer = resolver.resolve_method(canonical, name, hint)
            if answer:
                entry = {"names": {ns: name_ for ns, (name_, _) in answer.items()}}
                signatures = {ns: sig for ns, (_, sig) in answer.items() if sig}
                if signatures:
                    entry["signatures"] = signatures
                methods[name] = entry
                resolved += 1
            else:
                methods[name] = {"absent": True}
                absent.append((canonical, name + "()", f"{version}: unresolved"))
        for name in sorted(requirements.fields[canonical]):
            answer = resolver.resolve_field(canonical, name)
            if answer:
                entry = {"names": {ns: name_ for ns, (name_, _) in answer.items()}}
                descriptors = {ns: desc for ns, (_, desc) in answer.items() if desc}
                if descriptors:
                    entry["descriptors"] = descriptors
                fields[name] = entry
                resolved += 1
            else:
                fields[name] = {"absent": True}
                absent.append((canonical, name, f"{version}: unresolved"))
        classes[canonical] = {"names": class_names, "methods": methods, "fields": fields}
    save = getattr(resolver, "save", None)
    if save is not None:
        save()
    report["versions"][version] = {
        "classes": len(requirements.classes),
        "resolved": resolved,
        "absent": absent,
        "namespaces": sorted(
            {ns for entry in classes.values() for ns in (entry.get("names") or {})}
        ),
    }
    return {"version": version, "classes": classes}


def render(table: dict) -> str:
    return json.dumps(table, indent=4, ensure_ascii=False) + "\n"


def table_path(version: str) -> str:
    return os.path.join(RESOURCES, f"mappings-{version}.json")


# --------------------------------------------------------------------------
# javap cross-check
# --------------------------------------------------------------------------


def verify_javap(version: str, table: dict) -> "tuple[list, int, bool]":
    """Check the ``vanilla`` (obfuscated) names against the real client jar.

    That namespace is the only one whose jar we can obtain: Fabric/Forge
    installs are produced by the loader at launch, so their names are verified
    against the mapping sources themselves, not a jar.
    """
    jar = find_local_jar(version)
    if jar is None:
        return ([f"{version}: no local client jar"], 0, True)
    index = javap.JavapIndex(jar, DEFAULT_JAVAP, os.path.join(CACHE, f"javap-{version}.json"))
    problems = []
    checked = 0
    for canonical, entry in table["classes"].items():
        if entry.get("absent"):
            continue
        obf = (entry.get("names") or {}).get("vanilla")
        if not obf:
            continue
        internal = obf.replace(".", "/")
        if not index.exists(internal):
            problems.append(f"{version}: {canonical} -> {obf} not in jar")
            continue
        for name, spec in entry["methods"].items():
            if spec.get("absent"):
                continue
            runtime = (spec.get("names") or {}).get("vanilla")
            if not runtime:
                continue
            signature = (spec.get("signatures") or {}).get("vanilla")
            if not index.reachable(internal, "method", runtime, signature):
                problems.append(
                    f"{version}: {canonical}.{name} -> {runtime}{signature or ''} not found on {obf}"
                )
            else:
                checked += 1
        for name, spec in entry["fields"].items():
            if spec.get("absent"):
                continue
            runtime = (spec.get("names") or {}).get("vanilla")
            if not runtime:
                continue
            if not index.reachable(internal, "field", runtime):
                problems.append(f"{version}: {canonical}.{name} -> {runtime} not found on {obf}")
            else:
                checked += 1
    index.save()
    return (problems, checked, False)


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="do not write; fail if a table is stale")
    parser.add_argument("--javap", action="store_true", help="cross-check tables against local client jars")
    parser.add_argument("--only", default=None, help="comma-separated version filter")
    parser.add_argument("--report", action="store_true", help="print absent-member lists")
    args = parser.parse_args()

    requirements = scan.collect(CLIENT_SRC, CLASS_TYPE, REQUIREMENTS)
    manifest = sources.Manifest(CACHE)
    shape_hints = load_shape_hints()
    fetch = namespaces.Fetch(CACHE)
    anchor_table = build_anchor_table(requirements, manifest, fetch, shape_hints)

    versions = release_versions(manifest)
    if args.only:
        wanted = {v.strip() for v in args.only.split(",")}
        versions = [v for v in versions if v in wanted]
        unknown = wanted - set(versions)
        if unknown:
            print(f"unknown versions: {sorted(unknown)}", file=sys.stderr)
            return 2

    report = {"versions": {}, "unsupported": []}
    stale = False
    failures = []
    for version in versions:
        local_jar = find_local_jar(version)
        resolver = namespaces.build_resolver(
            version, fetch, CACHE, manifest, VANILLA_ROOT, TOOLS, DEFAULT_JAVAP, local_jar,
            anchor_table,
        )
        if resolver is None:
            report["unsupported"].append(version)
            print(f"{version:8} skipped  (no mapping source for this version)")
            continue
        table = build_table(version, resolver, requirements, report, shape_hints)
        text = render(table)
        path = table_path(version)
        info = report["versions"][version]
        print(
            f"{version:8} classes={info['classes']:3} "
            f"resolved={info['resolved']:4} absent={len(info['absent']):3} "
            f"namespaces={','.join(info['namespaces'])}"
        )
        if args.javap:
            problems, checked, skipped = verify_javap(version, table)
            report["versions"][version]["javap"] = problems
            if skipped:
                print("  javap: no local client jar — skipped")
            elif problems:
                print(f"  javap: {len(problems)} PROBLEMS ({checked} checked)")
            else:
                print(f"  javap: ok ({checked} members verified against the client jar)")
            for problem in problems:
                print(f"    {problem}")
                if not skipped:
                    failures.append(problem)
        if args.check:
            current = None
            if os.path.exists(path):
                current = open(path, encoding="utf-8", newline="").read()
            if current != text:
                print(f"  STALE: {path}", file=sys.stderr)
                stale = True
        else:
            with open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(text)

    if args.report:
        for version in versions:
            info = report["versions"].get(version)
            if info is None:
                continue
            print(f"\n===== {version} — {len(info['absent'])} absent =====")
            for canonical, member, reason in info["absent"]:
                where = canonical + ("#" + member if member else "")
                print(f"  {where}: {reason}")
        if report["unsupported"]:
            print(f"\n===== no mapping source ({len(report['unsupported'])}) =====")
            print("  " + ", ".join(report["unsupported"]))
            print("  No route reaches these: they need either an aliases-<version>.toml"
                  " bridge, an intermediary file, or an SRG source for that version.")

    if failures:
        print(f"\n{len(failures)} javap problems", file=sys.stderr)
        return 1
    if args.check and stale:
        print("tables are stale — re-run without --check", file=sys.stderr)
        return 1
    if args.check:
        print("all tables up to date")
    return 0


if __name__ == "__main__":
    sys.exit(main())
