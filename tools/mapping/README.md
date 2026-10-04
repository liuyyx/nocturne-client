# Mapping table generator

Produces every `client/src/main/resources/mappings-<version>.json` table that
the injected client uses to translate canonical (Mojmap) class/member names to
whatever the running game actually exposes.

```
python tools/mapping/generate.py            # (re)write all nine tables
python tools/mapping/generate.py --check    # exit 1 if any table is stale
python tools/mapping/generate.py --javap    # also verify against local client jars
python tools/mapping/generate.py --only 1.20.1 --report
```

Output is byte-stable (sorted keys, `indent=4`, UTF-8, trailing newline), so
`--check` is a reliable CI gate. Python 3.12 stdlib only.

## What gets a table

The **requirement surface** is merged from two sources:

* `scan.py` — walks `client/src/main/java` for `ClassType` enum constants and
  for the member string literals passed to the reflection bridge
  (`callMapped`/`methodName` = method, `readField`/`writeField`/`fieldName` =
  field);
* `requirements.txt` — the curated list for names the scanner cannot see
  (the name travels through a variable, or GUI/font/input members added later).
  Format:

  ```
  net/minecraft/client/Minecraft              # the class is needed
  net/minecraft/client/Minecraft#player       # a field
  net/minecraft/client/Minecraft#getInstance()  # a method
  ```

Anything that resolves is written with its runtime name (and, for methods, the
obfuscated JNI descriptor). Anything that does **not** resolve in a given
version is written as `"absent": true` and listed by `--report` — that list is
the to-do list for adding an alias or a requirement.

> Note for the parent session: `ObfuscatedMapping` currently only reads the
> `absent` flag at the *class* level. Member-level `"absent": true` is written
> into the JSON (Gson ignores the extra field, no parse breakage) but the
> runtime still falls back to the canonical name for an absent member; it does
> not yet log a member-level diagnostic.

## Per-version sources

| Version | Kind | Source |
|---|---|---|
| 1.8.9 / 1.12.2 | `legacy` | `vanilla{189,1122}/joined.srg` + `forge{189,1122}/{methods,fields}.csv` (in `../OpenVape4.21/.../mappings`) via the `aliases-<version>.toml` bridge: canonical → MCP human name → SRG → obf name + obf descriptor |
| 1.16.5 / 1.20.1 / 1.21.4 / 1.21.10 / 1.21.11 | `proguard` | official `client_mappings` (`client.txt`) from `piston-meta.mojang.com`, plus the client jar for the class hierarchy (inherited members) and `--javap` checks |
| 26.2 / 26.3 | `identity` | unobfuscated: runtime name = canonical name; member existence verified against the real client jar |

### Caching and EULA

Raw sources land in `tools/mapping/cache/` and are **not** committed and **not**
packed into the jar. In particular the ProGuard `client.txt` carries a
no-redistribution header; only the generated JSON ships. Client jars downloaded
for hierarchy/verification live in `cache/` too.

## Adding a requirement or an alias

1. Add the canonical entry to `requirements.txt` (or, for a literal call site,
   just use it in the client source — `scan.py` picks it up).
2. Run `python tools/mapping/generate.py --report` and read the absent list.
3. For a legacy version add/fix the bridge rule in `aliases-<version>.toml`
   (`[methods.*]`, `[fields.*]`, `[overrides."<class>"]`, `[class-aliases]`);
   for a modern version the official mappings are authoritative — a genuinely
   renamed member stays absent.
4. Re-run; the `--report` list (and `--javap`) must be clean for the members
   you care about.

## Files

| File | Role |
|---|---|
| `generate.py` | CLI entry; builds/compares/verifies the tables |
| `sources.py` | the three backends + Mojang manifest/cache helper |
| `scan.py` | Java source → requirement set; `requirements.txt` parser |
| `javap.py` | cached `javap` introspection (identity checks + `--javap`) |
| `requirements.txt` | curated requirement list |
| `aliases-1.8.9.toml`, `aliases-1.12.2.toml` | legacy canonical → MCP bridge |
| `cache/` | downloaded sources/jars (gitignored) |
