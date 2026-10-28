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
   this mod:
   - "Archive controller and current holdings", "Reconstruction and
     consumption", "Completion model": what the mod does.
   - "Edge-case register": matching rules for potions, enchantments, item
     data, containers, and other variants. The matcher and every manifest
     depend on it.
   - "Team state, persistence, and performance", "Archive implementation is
     a separate workstream".
   - "Storage addon research and recommendation": MEGA Cells and AE2 Things
     change how stock is counted.
   - "Deferred decisions and when they matter": several of these are this
     mod's to inform (power and chunk interruption, recovery from
     interrupted final consumption, virtual compression and infinite-source
     counting, team changes).
   - "Required acceptance examples": the tests the prototype must pass.

   To read one section:
   `python3 -I ~/.claude/scripts/md_section.py ~/tla-packwiz/docs/the-last-archive-design.md "<heading>"`.
2. `~/tla-packwiz/docs/roadmap.md` → *Phase 1*, archive track steps 1–6.
3. `~/tla-packwiz/docs/setup-notes.md`: the pinned pack versions this mod
   must compile and run against.
4. Any `HANDOFF_*.md` at this repo's root, which describes the next planned
   step. The pack track's Phase 1 handoff,
   `~/tla-packwiz/HANDOFF_ARCHIVE_PROTOTYPE_PACK_TRACK.md`, describes the
   split between the two repos and has an *Integration log* showing how each
   mod release did on TLADev.

Start sessions here, and add the pack repo for reading:
`claude --add-dir ~/tla-packwiz`. Make pack changes (pinning a jar,
deploying) from a session started in `~/tla-packwiz` with its
`packwiz-manager` skill, not from this repo.

## Where things are recorded

| What | Where |
| --- | --- |
| Requirements, rulings, deferred decisions | pack design document (above). Owner rulings are added there from a pack-repo session. |
| Phase plan and exit criteria | `~/tla-packwiz/docs/roadmap.md` → *Phase 1* (archive track steps 1–6) |
| The current step | the single `HANDOFF_ARCHIVE_MOD_*.md` at this repo's root |
| Build pins, Maven coordinates, existing-mod check | `docs/project-setup.md` |
| Spike findings (verdict, evidence, AE2/FTB source file and tag) | `docs/spikes/<topic>.md` |
| How the mod is built: components, data flow, persistence | `docs/architecture.md`. Create it when production code starts (step 3), from the spike findings. |
| Releases and their SHA-256 | `README.md` release table and GitHub releases |
| How a release did on TLADev | `~/tla-packwiz/HANDOFF_ARCHIVE_PROTOTYPE_PACK_TRACK.md` → *Integration log* |
| Pack pins and TLADev runtime findings | `~/tla-packwiz/docs/setup-notes.md` |

## Handoffs

- Keep exactly one active handoff at the root, named for the step it starts
  (`HANDOFF_ARCHIVE_MOD_<STEP>.md`). It covers the goal, current state,
  decisions already made, next steps, the *Done when* criteria, *Requests to
  the pack track*, checks, risks, and the next handoff file.
- When a step's *Done when* is met, write the next handoff and delete the
  finished one in the same commit (git history keeps it). Move lasting
  findings into `docs/` first. The `orch-handoff` skill writes handoffs in
  this format when it is available.
- A step that ends with work left (session ending, context full) updates its
  handoff's *Current state* rather than starting a new file.
- Planned chain for roadmap Phase 1. Adjust the names if a step splits
  differently, and record why.

  | Roadmap step | Handoff | Done when |
  | --- | --- | --- |
  | 1. Project setup | (done 2026-10-28; `docs/project-setup.md`) | reproducible build |
  | 2. API spikes | `HANDOFF_ARCHIVE_MOD_API_SPIKES.md` | see that file's *Done when* |
  | 3–4. Representative manifest + minimal controller | `HANDOFF_ARCHIVE_MOD_CONTROLLER.md` | test-only manifest; controller block with a grid node shows current/required counts and goes stale/unavailable on power loss or chunk unload; gametests for cross-network counting, storage-bus credit, readiness lost on withdrawal, potion and enchantment matching |
  | 5. Reconstruction prototype | `HANDOFF_ARCHIVE_MOD_RECONSTRUCTION.md` | behind a test flag; the operation is persisted; recovery after a forced restart mid-extraction is proven; a multi-enchantment book is consumed once; two teams stay isolated |
  | 6. Packaging + Phase 1 exit | `HANDOFF_ARCHIVE_MOD_PHASE1_EXIT.md` | a release is pinned by the pack track, and the pack track has run the prototype's acceptance examples on TLADev and logged them as passing |
  | Phase 2 (manifest, catalog, persistence) | `HANDOFF_ARCHIVE_MOD_CATALOG.md` | roadmap *Phase 2* exit |

## Working alongside the pack track

Other agents work on the pack in `~/tla-packwiz` at the same time.

- **This track never** commits in `~/tla-packwiz` or edits its files,
  restarts or deploys to TLADev, or uses the `mcpfabric` tools to change its
  world. Read-only commands such as `ss` and reading logs are fine.
- **Hand-off point: a GitHub release** (see *Releases*). Pack agents pin the
  asset, deploy it to TLADev, and record the result in the pack handoff's
  *Integration log*. Read that log before building on a release.
- **What this track needs from the pack track** (for example a TLADev
  acceptance run, a new mod in the pack, or a spark measurement) goes in this
  repo's handoff under *Requests to the pack track*, and you tell the owner.
  Don't do it yourself.
- **Deferred design decisions** (power loss, chunk unload, interrupted
  consumption): present the options and evidence to the owner. The ruling is
  recorded in the pack's design document from a pack-repo session.

## Fixed facts

| Thing | Value |
| --- | --- |
| Mod id | `tla_archive` |
| Platform | Minecraft `1.21.1`, NeoForge `21.1.265` (pack pin) |
| Compile against | AE2 `19.2.18`, FTB Teams `2101.1.11` (pack pins). Coordinates, repositories, and how they were verified: `docs/project-setup.md` |
| Package / group | `io.github.nturner03.tlaarchive` |
| License | `LGPL-3.0-only` (`COPYING`, `COPYING.LESSER`) |
| JDK | `/usr/lib/jvm/java-21-openjdk-amd64`. The host default `java` is 1.8, so every Gradle command needs `JAVA_HOME` set to the JDK 21 path. Claude Code sessions here get it from the git-ignored `.claude/settings.local.json`. |
| Build | the project's pinned Gradle wrapper (`./gradlew`), never the system Gradle in `/opt/gradle` |
| Remote | `origin` = public GitHub repo `nturner03/tla-archive-mod`; CI (`.github/workflows/build.yml`) runs `./gradlew build` on every push and PR |
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
- **TLADev belongs to the pack track.** This mod reaches it only as a
  pinned release (see *Working alongside the pack track*). Test here with
  gametests and dev runs.

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

The mod has its own semantic version (`mod_version` in `gradle.properties`),
independent of the pack's. Before `1.0.0`, a minor bump may break saved
archive data. Say so in the release notes.

## Releases

Releases are the only way a jar reaches the pack. **Each release needs the
owner's approval**: ask first, then:

1. On `main`, with a clean tree and CI green, set `mod_version` and commit.
2. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew clean build`,
   then `sha256sum build/libs/tla_archive-<version>.jar`. Rebuild once more
   and confirm the hash is the same.
3. Add the version and SHA-256 to the release table in `README.md` and
   commit.
4. `git tag v<version>`, `git push origin main v<version>`, then
   `gh release create v<version> build/libs/tla_archive-<version>.jar
   --title "tla_archive <version>" --notes "<changes, test-only status,
   known risks, any saved-data break>"`. Mark pre-`1.0` releases with
   `--prerelease`.
5. Tell the owner the release URL so a pack session can pin it. Pinning is
   pack-track work.

## Git

- `main` is protected by convention: change it only through PRs that the
  owner merges. Never commit directly to `main`, except the release commits
  and tags in *Releases* after the owner approves.
- Branch from an up-to-date `main` (`git fetch && git switch -c <branch>
  origin/main`). Prefixes: `spike/<topic>` (throwaway API proofs),
  `feat/<topic>`, `fix/<topic>`, `docs/<topic>`, `build/<topic>`.
- Agents may push their own branches and open PRs (`gh pr create`) without
  asking. They don't merge, push to `main` (outside releases), force-push
  shared branches, delete others' branches, or rewrite pushed history.
- Keep commits small and focused. LF line endings are set in
  `.gitattributes`. Don't override them.

## Several agents at once

- **One checkout per agent.** If another agent may be working in
  `~/tla-archive-mod`, work in a separate git worktree
  (`git worktree add ../tla-archive-mod-<branch> -b <branch> origin/main`,
  or Claude Code's worktree isolation). Each worktree has its own ignored
  `run/`, so dev runs and gametests don't collide. Never run two dev runs
  from the same checkout.
- **Handoff ownership.** The newest `HANDOFF_ARCHIVE_MOD_*.md` is the plan.
  Agents working in parallel record findings in their own files (for
  example `docs/spikes/<topic>.md`) and report back. The agent the owner put
  in charge of the step updates the handoff, which avoids merge conflicts in
  a single file.
- Check `git log origin/main` and open PRs (`gh pr list`) before starting,
  so you don't redo work that is already in review.
