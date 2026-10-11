# Class-name randomizer

Renames Nocturne's own classes inside a built jar, keeping every reference in
the artifact consistent. This exists because of the OpenZen lesson: a published
binary gets banned by matching class names against a blacklist. So the public
repo ships source only, and the official binary comes out of a build that
randomizes class names.

```
python3 tools/randomize/randomize.py --in dist.jar --out dist-randomized.jar --salt <seed>
python3 tools/randomize/randomize.py --in dist.jar --check     # CI gate, exit 1 on dangling refs
```

Wired into the build as `./gradlew :dist:randomizedJar` (pass `-PclassSalt=<seed>`
for a reproducible build; omit it and the tool draws a fresh seed from
`os.urandom`, so every official build differs).

## Why this is not a package rename in source

Nocturne is fully reflective, so a class name shows up in three separate forms
and all three have to move together:

| form | example |
|---|---|
| internal name | `dev/nocturne/agent/NocturneAgent` |
| type in a descriptor | `(Ldev/nocturne/client/module/Module;I)V` |
| **string constant** | `"dev.nocturne.agent.transform.FrameHookTransformer"` |

That third form is invisible to any source-level rename. The repo currently
holds **7 such literals** (the transformer class list and prefix in
`EmbeddedAsmLoader`, `DISPATCHER_CLASS` in `NocturneRuntime`, the relaunch
targets in `Nocturne` and `InjectorApp`). Miss one and the injection chain
breaks *silently* on a real client — no exception, just a class that cannot be
found.

## How it stays consistent

The tool rewrites only the payload of `CONSTANT_Utf8` pool entries. Every
cross-reference in a class file is a **constant-pool index** (a 1-based slot
number), never a byte offset — so letting a payload grow or shrink just shifts
the bytes after it and all indices stay valid. That property is what lets a
single-pass rewriter stay correct without a full bytecode parser.

The manifest is rewritten too: `Main-Class` / `Premain-Class` / `Agent-Class`
all point at classes the injector resolves by name from the manifest.

Packages are preserved by default — only the simple name changes. The package
path is the anchor for the prefix-concatenation literals above, and it is also
what the child-first ASM loader's guard tests, so moving it multiplies the ways
a rename can silently break. Renaming packages would need those two sites
rewritten by hand first.

## Guarantees, and how they are enforced

* **No dangling references.** After writing, the tool re-reads its own output
  and fails the build if any in-prefix class is referenced but not present.
  Same philosophy as `PayloadTool`'s round-trip check: fail at build time
  rather than leave a silent failure for the target JVM.
* **Reproducible.** The same `--salt` produces a byte-identical jar (entry
  timestamps and attributes are carried over), so `--check` and artifact diffs
  are meaningful in CI.
* **Deterministic mapping.** New names come from `HMAC-SHA256(salt, oldName)`,
  so the same salt maps a given class the same way in every jar of a build.

Python 3.12 stdlib only.
