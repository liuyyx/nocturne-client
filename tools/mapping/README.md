# Mapping table generator

Produces every `client/src/main/resources/mappings-<version>.json` table that
the injected client uses to translate canonical (Mojmap) class/member names to
whatever the running game actually exposes.

```
python tools/mapping/generate.py            # (re)write every table
python tools/mapping/generate.py --check    # exit 1 if any table is stale
python tools/mapping/generate.py --javap    # also verify against local client jars
python tools/mapping/generate.py --only 1.20.1 --report
```

Output is byte-stable (sorted keys, `indent=4`, UTF-8, trailing newline), so
`--check` is a reliable CI gate. Python 3.12 stdlib only.

## Why four namespaces

The same game version exposes *different* names depending on which loader
launched it — every loader remaps the obfuscated jar before the client ever
sees a class:

| namespace | who runs like that | class name | member name |
|---|---|---|---|
| `vanilla` | no loader | obfuscated (`enn`) | obfuscated (`N`, `f_90977_`) |
| `fabric` | Fabric / Quilt | intermediary (`net.minecraft.class_1657`) | intermediary (`method_1551`) |
| `forge` | Forge | readable/Mojmap (`net.minecraft.client.Minecraft`) | SRG (`func_` / `field_` <= 1.15.2, `m_` / `f_` >= 1.16.5) |
| `neoforge` | NeoForge | readable/Mojmap | SRG on 1.20.1, **Mojang names from 1.20.2** |

Measured, not assumed:

* **Forge 1.20.1** — `forge-1.20.1-47.4.21-client.jar` exposes
  `net.minecraft.client.Minecraft`, `m_91087_()` (returns
  `net.minecraft.client.Minecraft`), `f_90981_` (`instance`). The generator's
  `forge` namespace reproduces all of those names, descriptor included.
* **NeoForge 1.21.11** — `minecraft-client-patched-21.11.42.jar` exposes
  `net.minecraft.client.Minecraft.getInstance()`: unobfuscated, hence the
  identical-to-canonical `neoforge` namespace from 1.20.2 on.
* **Forge 1.8.9 / 1.12.2** — the vendored MCP `joined.srg` right-hand side is
  the readable class + `func_`/`field_` pair the loader runs with.
* **Fabric** — `net/minecraft/class_310` / `method_1551` come straight from the
  intermediary files; 26.x has no intermediary at all (unobfuscated), so its
  `fabric` namespace equals the canonical name.

At runtime the client tries the names in the order
`vanilla → fabric → forge → neoforge → canonical`, so one table serves all four
launchers without the agent having to detect which one it is.

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

Anything that resolves is written per namespace; anything that does **not**
resolve in a given version is written as `"absent": true` and listed by
`--report` — that list is the to-do list for adding an alias or a requirement.

## JSON schema

```json
{
    "version": "1.20.1",
    "classes": {
        "net/minecraft/client/Minecraft": {
            "names": {
                "vanilla": "enn",
                "fabric": "net.minecraft.class_310",
                "forge": "net.minecraft.client.Minecraft",
                "neoforge": "net.minecraft.client.Minecraft"
            },
            "methods": {
                "getInstance": {
                    "names": {
                        "vanilla": "N", "fabric": "method_1551",
                        "forge": "m_91087_", "neoforge": "m_91087_"
                    },
                    "signatures": {
                        "vanilla": "()Lenn;",
                        "fabric": "()Lnet/minecraft/class_310;",
                        "forge": "()Lnet/minecraft/client/Minecraft;",
                        "neoforge": "()Lnet/minecraft/client/Minecraft;"
                    }
                }
            },
            "fields": {
                "player": {
                    "names": {
                        "vanilla": "t", "fabric": "field_1724",
                        "forge": "f_91074_", "neoforge": "f_91074_"
                    },
                    "descriptors": {
                        "vanilla": "Lfiy;",
                        "fabric": "Lnet/minecraft/class_746;",
                        "forge": "Lnet/minecraft/client/player/LocalPlayer;",
                        "neoforge": "Lnet/minecraft/client/player/LocalPlayer;"
                    }
                }
            }
        },
        "com/mojang/blaze3d/platform/Window": {
            "absent": true, "names": {}, "methods": {}, "fields": {}
        }
    }
}
```

* Keys are canonical (Mojmap) internal names — `/` separated, `$` for nested
  classes; dotted forms are accepted when looking up.
* A namespace is **omitted** when it does not exist in that version (no
  intermediary file, no Forge for 26.x, no NeoForge before 1.20.1); on the
  unobfuscated 26.x all namespaces collapse onto the same canonical name.
* Descriptors are JNI descriptors written in the *same* namespace as the name
  they accompany, so a reflection lookup can use them as-is under any loader.
* `"absent": true` at class or member level means "this version genuinely has
  no counterpart"; the runtime logs once and falls back to the canonical name
  instead of pretending the canonical name is a runtime name.

## Per-version sources

| Version | canonical <-> obf | obf <-> SRG (forge) | obf <-> intermediary (fabric) |
|---|---|---|---|
| 1.8.9 / 1.12.2 | `aliases-<version>.toml` + vendored MCP `joined.srg`/CSVs | same (joined.srg right half) | Legacy-Fabric/Legacy-Intermediaries |
| 1.9 – 1.14.3 | **bridged through intermediary** (see `AnchorResolver`) | MCP `joined.srg` (1.9 – 1.12.1, from the `de.oceanlabs.mcp:mcp` artifact) / MCPConfig `joined.tsrg` (1.13 – 1.14.3) | Legacy-Fabric (1.9 – 1.13.2) / FabricMC (1.14 – 1.14.3) |
| 1.14.4 – 1.21.11 | official `client_mappings` (`client.txt`) | MinecraftForge/MCPConfig `joined.tsrg` | FabricMC/intermediary |
| 26.1 – 26.3 | not obfuscated | — (no SRG file) | — (not obfuscated) |

The obf name is reached differently in each era, but it is always the pivot the
other namespaces hang off:

```
canonical --(client_mappings)-->  obf --(joined.tsrg)------> srg
       \--(alias bridge)-------->     --(intermediary)----> intermediary
        \--(intermediary anchor)->    --(client jar/javap)-> superclass chain
```

* **1.14.4+** — Mojang publishes the mappings, so canonical -> obf is one lookup.
* **1.8.9 / 1.12.2** — the alias TOML bridges canonical -> MCP human name, the
  CSVs give the SRG name, and `joined.srg` gives obf.
* **1.9 – 1.14.3** — no official mappings and no MCP human names for 1.13+, so
  neither route reaches them. Intermediary does: it is version-stable, and each
  version's own tiny file carries the obf name behind every intermediary name.
  `AnchorResolver` therefore learns `canonical -> intermediary` on 1.12.2 and
  looks it up in the target version's tiny file, then keys the Forge namespace
  off the resulting obf name. Point releases MCP never shipped an SRG for
  (1.9.1, 1.9.3, 1.10.1) get no `forge` namespace — the other three are still
  complete.

The class hierarchy comes from the real client jar (`javap`), because
requirements name members on the class the client talks to
(`Player#getHealth`) while the obf/SRG name only exists on the declaring class
(`LivingEntity`).

**Coverage: all 66 releases from 1.8.9 to 26.3** produce a table; `--report`
prints the per-version absent list and `--javap` verifies every version's
vanilla names against its real client jar.

### Local checkouts, mirrors, and offline runs

`raw.githubusercontent.com` is rate-limited and flaky, and a single file cannot
be fetched without cloning, so the generator has three routes to the same cache
file and picks whichever works:

1. a local checkout (`NOCTURNE_*_DIR`, below) — authoritative when set;
2. a mirror (`cdn.jsdelivr.net`, the NeoForged maven for MCP's SRG zips);
3. the upstream raw URL, with retries.

```
git clone --depth 1 https://github.com/MinecraftForge/MCPConfig           mcpconfig
git clone --depth 1 https://github.com/FabricMC/intermediary              intermediary
git clone --depth 1 https://github.com/Legacy-Fabric/Legacy-Intermediaries legacy-intermediary

NOCTURNE_MCPCONFIG_DIR=<abs path>/mcpconfig \
NOCTURNE_INTERMEDIARY_DIR=<abs path>/intermediary \
NOCTURNE_LEGACY_INTERMEDIARY_DIR=<abs path>/legacy-intermediary \
NOCTURNE_MCP_SRG_DIR=<abs dir holding srg-<version>.srg files> \
  python tools/mapping/generate.py
```

Once `cache/` is populated a run is fully offline. `Legacy-Intermediaries` has
no jsdelivr mirror and its clone is large; the individual `*.tiny` files are
reachable through `api.github.com` (`/repos/{owner}/{repo}/git/trees/…` +
`/git/blobs/…`), which is what seeded the pre-1.14 files.

### Caching and EULA

Raw sources land in `tools/mapping/cache/` and are **not** committed and **not**
packed into the jar. In particular the ProGuard `client.txt` carries a
no-redistribution header, and MCPConfig is zlib-derived; only the generated
JSON — names and descriptors, i.e. derived facts — ships.

## Adding a requirement or an alias

1. Add the canonical entry to `requirements.txt` (or, for a literal call site,
   just use it in the client source — `scan.py` picks it up).
2. Run `python tools/mapping/generate.py --report` and read the absent list.
3. For the legacy versions add/fix the bridge rule in
   `aliases-<version>.toml` (`[methods.*]`, `[fields.*]`,
   `[overrides."<class>"]`, `[class-aliases]`); for a modern version the
   official mappings are authoritative — a genuinely renamed member stays
   absent, and only a class/member that moved to another namespace needs a fix
   in `namespaces.py`.
4. Re-run; the `--report` list (and `--javap`) must be clean for the members
   you care about.

## Files

| File | Role |
|---|---|
| `generate.py` | CLI entry; builds/compares/verifies the tables |
| `namespaces.py` | the loader namespaces: ProGuard / TSRG2 / tiny parsers, the obf-pivot joins, and the per-version resolver |
| `sources.py` | Mojang manifest/download cache + the legacy MCP (`joined.srg` + forge CSVs) reader |
| `scan.py` | Java source -> requirement set; `requirements.txt` parser |
| `javap.py` | cached `javap` introspection (class hierarchy, `--javap` checks) |
| `requirements.txt` | curated requirement list |
| `aliases-1.8.9.toml`, `aliases-1.12.2.toml` | legacy canonical -> MCP bridge |
| `cache/` | downloaded sources/jars (gitignored) |
