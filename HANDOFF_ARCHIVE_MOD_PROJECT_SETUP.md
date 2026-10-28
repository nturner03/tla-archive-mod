# Handoff: archive mod project setup (roadmap Phase 1, archive track step 1)

## Goal

Turn this empty repository into a buildable NeoForge 1.21.1 mod project
(`tla_archive`) that compiles against the pack's pinned AE2 and FTB Teams,
with a reproducible `./gradlew build`. This is the foundation for the API
spikes that follow. Plan: `~/tla-packwiz/docs/roadmap.md` → *Phase 1* →
*Archive track*.

## Current state (2026-10-28)

- `git init` on `main` with `AGENTS.md`, `CLAUDE.md` (→ `AGENTS.md`),
  `README.md`, `.gitignore` (jars and build/run output ignored, except the
  Gradle wrapper jar), and `.gitattributes` (LF). No
  build files and no source yet.
- No remote. The owner approved a **public** GitHub repo
  `nturner03/tla-archive-mod`, and jars ship as its release assets. Create it
  when the owner asks (`gh repo create`).
- Pack side (`~/tla-packwiz`, branch `content/archive-prototype`): spark
  added and deployed to TLADev; TLADev backups set up
  (`~/minecraft/TLADev/backup.sh`, `restore.sh`). Cross-repo status lives in
  `~/tla-packwiz/HANDOFF_FOUNDATION_ARCHIVE_PROTOTYPE.md`.
- Host: JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (the default `java` is
  1.8); system Gradle `9.2.0` at `/opt/gradle` (use it only to generate the
  wrapper, if at all).

## Decisions already made (do not reopen without the owner)

- Location `~/tla-archive-mod`, mod id `tla_archive`, public GitHub repo,
  jar delivery through GitHub release assets with `packwiz url add`.
- Compile against the pack pins: NeoForge `21.1.265`, Minecraft `1.21.1`,
  AE2 `19.2.18`, FTB Teams `2101.1.11`.
- TLADev world is disposable; confirm with the owner before breaking it.
- Reconstruction stays behind a test flag until interrupted-extraction
  recovery is proven.

## Next steps

1. **Ask the owner** the open questions below before generating files.
2. **Quick existing-mod check** (design deferred decision): is there a
   maintained 1.21.1 NeoForge mod that already meets the archive requirements
   (current-stock counting across AE2 grids, team credit, matching policies,
   safe consumption)? Spend little time on it and record the finding.
3. **Project from the MDK:** start from the official NeoForge 1.21.1 MDK
   (find the current ModDevGradle template from NeoForged's docs; don't
   assume it). Set the mod id, group, and version; pin NeoForge `21.1.265`.
4. **Pin the wrapper:** a fixed Gradle version with `distributionSha256Sum`
   in `gradle/wrapper/gradle-wrapper.properties`; commit the wrapper jar.
   Pin every plugin and dependency version.
5. **Dependencies:** look up the Maven repositories and coordinates for AE2
   `19.2.18` (API artifact if there is one) and FTB Teams `2101.1.11` (plus
   FTB Library / Architectury if needed at compile time), using each
   project's own docs. Record the coordinates and their source here or in
   `docs/`. Use compile-only/API scope; at runtime the pack supplies them.
   Make dev runs (`runClient`/`runServer`) load AE2 and FTB Teams so spikes
   can run outside TLADev.
6. **Build:** `./gradlew build` from a fresh clone produces
   `tla_archive-<version>.jar`. Record its SHA-256.
7. Then the **API spikes** (roadmap step 2), each throwaway with its finding
   recorded: grid stock via AE2's storage service; simulated extraction
   through storage buses; atomic multi-item extraction (or a staging/
   compensation prototype); FTB Teams identity and team-change behaviour;
   incremental stock observation without per-tick scans.

## Open owner questions

- **Java package / Maven group**, e.g. `io.github.nturner03.tlaarchive`.
- **License** for the public repo, e.g. MIT, LGPL-3.0 (AE2 is LGPL-3.0), or
  all rights reserved.
- **When to create the GitHub repo:** at the first buildable commit, or
  later?

## Checks to run

- `./gradlew build` with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.
- A clean clone into a scratch directory builds the same way.
- `git status` shows no build output or jars apart from the wrapper.

## Known risks

- Atomic extraction across several AE2 keys may not be possible. This is the
  top project risk (roadmap *Main risks*).
- AE2 Things `1.4.2-beta` is in the pack; its DISK behaviour with AE2
  `19.2.18` is untested and affects how the matcher counts storage.

## Next handoff file

When the project builds and the first spike starts, write
`HANDOFF_ARCHIVE_MOD_API_SPIKES.md` here.
