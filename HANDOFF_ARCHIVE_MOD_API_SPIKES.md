# Handoff: archive mod API spikes (roadmap Phase 1, archive track step 2)

## Goal

Answer the archive mod's riskiest API questions with small throwaway spikes,
and record each finding before any production code is written. Plan:
`~/tla-packwiz/docs/roadmap.md` → *Phase 1* → *Archive track* step 2. The
design requirements are in `~/tla-packwiz/docs/the-last-archive-design.md`
(see `AGENTS.md` → *Read first* for the sections).

## Current state (2026-10-28)

- Step 1 (project setup) is done. ModDevGradle project from the official
  1.21.1 MDK, Gradle 9.2.1 wrapper pinned by checksum, every plugin and
  dependency pinned, and a reproducible `./gradlew build` that produces
  `tla_archive-0.1.0.jar`. Pins, coordinates, verification, and the
  existing-mod check are in `docs/project-setup.md`.
- Owner decisions: package/group `io.github.nturner03.tlaarchive`, license
  `LGPL-3.0-only`.
- Remote: public `nturner03/tla-archive-mod`, created 2026-10-28 at the first
  buildable commit. CI passed on the setup commit. `main` was fast-forwarded
  to `7027d65` (setup) with the owner's approval on 2026-10-28. Start work
  from `origin/main` (`AGENTS.md` → *Git*, *Several agents at once*).
- Mod content: only `TlaArchive` (the `@Mod` entry point). It declares
  required dependencies on AE2 `[19.2.18,20)` and FTB Teams `[2101.1.11,2102)`.
- Dev runs load the pack's exact AE2, GuideME, FTB Teams, FTB Library, and
  Architectury. `runGameTestServer` was verified to load them.
  `runClient` and `runServer` have not been launched.
- The `mcmodding` MCP server (`.mcp.json`) timed out connecting in the setup
  session, probably during its first database download. Check it with `/mcp`.
- Not deployed to TLADev and not in the pack.

## Next steps

1. **Decide where spikes run.** Gametests (`runGameTestServer`, no port) can
   build a grid in a test structure and assert on it, so prefer them for
   repeatable checks. If a spike needs `runServer` or a client join, first
   allocate a port in `/home/nickt/stacks/docs/docs/04-networking/ports.md`
   and set it in `run/server.properties`. Never use `18000`–`18002`.
2. **Keep spike code separate:** for example, a `spike` package or a branch per
   spike, so that it can be deleted. Record each finding in `docs/spikes/`
   with the AE2/FTB source file and tag that it relies on.
3. **Spikes** (roadmap step 2):
   - **Grid stock:** read stock through `IStorageService`
     (`getCachedInventory()` and/or `getInventory()`). Check that a simulated
     extraction (`Actionable.SIMULATE` with an `IActionSource`) matches what a
     real extraction removes, including through storage buses on external
     inventories.
   - **Atomic multi-key extraction:** does the API support it? If not,
     prototype staging or compensation that recovers after a forced
     interruption. This is the top project risk. Reconstruction stays behind
     a dev/test flag until recovery is proven.
   - **FTB Teams identity:** read a player's team (`FTBTeamsAPI.api()`), and
     observe team create, join, leave, and disband events and what they do to
     ownership. Team credit never grants access to another team's inventory.
   - **Incremental observation:** find a way to see stock changes without
     scanning every tick. Measure with spark.
   - **AE2 Things DISKs** (`AE2-Things-1.4.2-beta`, in the pack): its runtime
     behaviour with AE2 19.2.18 is untested and affects counting. It is not
     on the dev classpath yet. Adding it to dev runs needs its own pinned
     coordinates (it is a CurseForge-only mod).
   The spikes are independent of each other, so they can run in parallel:
   one agent per spike, each on its own `spike/<topic>` branch and worktree,
   each reporting in `docs/spikes/<topic>.md`. Do atomic extraction first or
   alongside the others, because its answer shapes reconstruction (roadmap
   step 5) more than anything else.
4. **Early skeleton release (suggested; owner approval).** Releasing the
   current near-empty mod as `0.1.0` lets the pack track test the
   release → `packwiz url add` → TLADev → client path before real features
   depend on it. Follow `AGENTS.md` → *Releases*. Note in the release that it
   has no features.
5. When the spikes are done, write the next handoff (representative manifest,
   roadmap step 3), and list in it any options for the deferred design
   decisions (power loss, chunk unload, interrupted consumption) that the
   owner must rule on.

## Requests to the pack track

Things this track needs done in `~/tla-packwiz` or on TLADev. Tell the owner
when you add one; a pack session does it and reports in the pack handoff's
*Integration log*.

- None yet.

## Checks to run

- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew build`
- `./gradlew runGameTestServer` once gametests exist (with none, it stops with
  `No test functions were given!`).
- `git status` shows no jars apart from the wrapper and no `run/` data.

## Known risks

- Atomic extraction across several AE2 keys may not be possible (roadmap
  *Main risks*).
- AE2's own source is the reference for its API. The `mcmodding` MCP does not
  index AE2 or FTB Teams (`AGENTS.md`).
