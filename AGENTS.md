# tla-archive — agent guide

This repository is the source for `tla_archive`, the custom AE2 integration
mod for The Last Archive (Minecraft 1.21.1, NeoForge). It is a separate
project from the pack. The pack repo is `~/tla-packwiz` (packwiz
distribution); the dev server is `~/minecraft/TLADev`.

## Read first

The design and plan live in the pack repo. Read them there, don't copy them
here:

1. `~/tla-packwiz/docs/the-last-archive-design.md`: requirements,
   recommendations, and deferred decisions. Keep these three apart, and never
   quietly turn a proposal into a requirement. Sections that matter most for
   this mod: "Archive controller and current holdings", "Reconstruction and
   consumption", "Completion model", "Team state, persistence, and
   performance", "Archive implementation is a separate workstream", and
   "Required acceptance examples".
2. `~/tla-packwiz/docs/roadmap.md` → *Phase 1*, archive track steps 1–6.
3. `~/tla-packwiz/docs/setup-notes.md`: the pinned pack versions this mod
   must compile and run against.
4. Any `HANDOFF_*.md` at this repo's root, which describes the next planned
   step. The cross-repo Phase 1 handoff is
   `~/tla-packwiz/HANDOFF_FOUNDATION_ARCHIVE_PROTOTYPE.md`.

Start sessions here, and add the pack repo for reading:
`claude --add-dir ~/tla-packwiz`. Make pack changes (pinning a jar,
deploying) from a session started in `~/tla-packwiz` with its
`packwiz-manager` skill, not from this repo.

## Fixed facts

| Thing | Value |
| --- | --- |
| Mod id | `tla_archive` |
| Platform | Minecraft `1.21.1`, NeoForge `21.1.265` (pack pin) |
| Compile against | AE2 `19.2.18`, FTB Teams `2101.1.11` (pack pins); Maven coordinates are recorded once found, not assumed |
| JDK | `/usr/lib/jvm/java-21-openjdk-amd64` (the host default `java` is 1.8) |
| Build | the project's pinned Gradle wrapper (`./gradlew`), never the system Gradle in `/opt/gradle` |
| Remote | public GitHub repo `nturner03/tla-archive-mod` (owner-approved; create it only when the owner asks) |
| Release | jar attached to a GitHub release `v<version>`; the pack pins that asset URL |

## Rules

- **No built jars in git.** The only exception is
  `gradle/wrapper/gradle-wrapper.jar`, and its distribution is pinned by
  `distributionSha256Sum`.
- **Reproducible builds:** pin the Gradle wrapper, the plugin versions, and
  every dependency version, with no dynamic (`+`, `latest`) versions. A clean
  `./gradlew build` from a fresh clone must produce the jar. Record the
  release jar's SHA-256 with its version.
- **Prove the risky parts before exposing them.** Reconstruction (destructive
  extraction) stays behind a dev/test flag until recovery after an interrupted
  extraction has been shown to work. Prototype manifests and quantities are
  test-only and labelled that way.
- **Spikes are throwaway.** Keep each spike small, write its finding down
  (handoff or `docs/`), and don't let spike code turn into production code
  without review.
- **Team credit never grants access to another team's inventory.**
- **Mutable data stays out of the repo.** Dev-run worlds, logs, and configs
  live in ignored run directories.
- **Ports:** dev runs and tools that bind a host port follow the global rules
  in `~/.claude/CLAUDE.md`. Check
  `/home/nickt/stacks/docs/docs/04-networking/ports.md` first. Don't bind
  TLADev's ports (`18000`–`18002`).
- **TLADev:** deploy only through the pack. Back up first with
  `~/minecraft/TLADev/backup.sh <label>`. The world is disposable, but
  confirm with the owner before a breaking change or a reset.

## Modding reference MCP (`mcmodding`)

`.mcp.json` registers the project-scoped MCP server
[`mcmodding-mcp`](https://github.com/OGMatrix/mcmodding-mcp), pinned to
`0.5.0` and run with `npx` over stdio (it binds no host port). Claude Code
asks to approve it the first time a session starts here. It needs Node 20 or
newer.

- **Use it for:** looking up docs and examples (`get_example` takes a
  `loader` filter, so pass `neoforge`; `search_fabric_docs` has no loader
  filter despite the indexed NeoForge docs), and Parchment mappings (`search_mappings`, `get_class_details`,
  `get_method_signature`, `browse_package`) to find vanilla class and method
  names.
- **Don't use it for:** AE2 or FTB Teams APIs, which it doesn't index. Read
  those from the pinned dependency sources. Its examples and concept
  explanations are mostly Fabric, so don't copy Fabric patterns (mixins,
  `fabric.mod.json`, Fabric API events) into this NeoForge mod. Check any
  answer against the pinned versions in "Fixed facts", because the docs can
  be for a newer Minecraft or NeoForge.
- **Data:** the server downloads its databases to
  `~/.local/share/mcmodding-mcp/`, outside the repo, and checks GitHub for
  updates when it starts. The docs database is about 520 MB. To add the
  optional mappings (~180 MB) or mod-examples (~30 MB) databases, run
  `npx mcmodding-mcp@0.5.0 manage`. Don't set `DB_PATH` to a location inside
  the repo.
- **Upgrading:** bump the version in `.mcp.json` on purpose and update this
  section. Never use an unpinned `mcmodding-mcp` or `@latest`.

## Looking things up online

The MCP is only one source. Search the web too (WebSearch/WebFetch)
whenever its answers are thin, Fabric-specific, or don't cover the
question, and always do so for AE2 and FTB Teams. Good places to look:

- NeoForge docs (`docs.neoforged.net`, on the 1.21.1 version) and the
  NeoForged blog's version primers and release notes.
- The source and issues of the mods we compile against:
  `github.com/AppliedEnergistics/Applied-Energistics-2` and
  `github.com/FTBTeam/FTB-Teams`. Use the tag or branch that matches the
  pinned version.
- Other open-source NeoForge 1.21.1 mods that use the same APIs, to see
  working patterns.

Treat what you find online as a lead, not as fact. Check it against the
pinned versions in "Fixed facts" and the actual dependency sources, because
older or newer versions of an API often differ. Cite the URL in the handoff
or `docs/` when something you found shapes a design decision.

## Versioning

The mod has its own semantic version, independent of the pack's. The owner
decides releases. Each release is tagged `v<version>`, and the jar is a
release asset.

## Git

- Branch from `main` for work; keep commits small and focused.
- LF line endings are set in `.gitattributes`. Don't override them.
- Don't add remotes, push, create releases, or rewrite history unless the
  owner asks.
