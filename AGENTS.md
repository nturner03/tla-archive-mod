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

## Versioning

The mod has its own semantic version, independent of the pack's. The owner
decides releases. Each release is tagged `v<version>`, and the jar is a
release asset.

## Git

- Branch from `main` for work; keep commits small and focused.
- LF line endings are set in `.gitattributes`. Don't override them.
- Don't add remotes, push, create releases, or rewrite history unless the
  owner asks.
