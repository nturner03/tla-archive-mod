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
2. `~/tla-packwiz/docs/roadmap.md` → the current phase (*Phase 2* from
   2026-10-28; Phase 1 closed by owner ruling, its reconstruction hardening
   moved to the *Phase 5* gate).
3. `~/tla-packwiz/docs/setup-notes.md`: the pinned pack versions this mod
   must compile and run against.
4. Any `HANDOFF_*.md` at this repo's root, which describes the next planned
   step. A plan from the pack's `feature-plan` skill is one of these
   handoffs (see *Handoffs*). The pack's `~/tla-packwiz/docs/archive-integration.md` describes the
   split between the two repos (*Two tracks*, *Pinning a mod release*) and
   has an *Integration log* showing how each mod release did on TLADev.

Start sessions here, and add the pack repo for reading:
`claude --add-dir ~/tla-packwiz`. Make pack changes (pinning a jar,
deploying) from a session started in `~/tla-packwiz` with its
`packwiz-manager` skill, not from this repo.

## Where things are recorded

| What | Where |
| --- | --- |
| Requirements, rulings, deferred decisions | pack design document (above). Owner rulings are added there from a pack-repo session. |
| Phase plan and exit criteria | `~/tla-packwiz/docs/roadmap.md` → the current phase |
| The current step | the single `HANDOFF_ARCHIVE_MOD_*.md` at this repo's root |
| Build pins, Maven coordinates, existing-mod check | `docs/project-setup.md` |
| Spike findings (verdict, evidence, AE2/FTB source file and tag) | `docs/spikes/<topic>.md` |
| How the mod is built: components, data flow, persistence | `docs/architecture.md`. Create it when production code starts (step 3), from the spike findings. |
| Releases and their SHA-256 | `README.md` release table and GitHub releases |
| How a release did on TLADev | `~/tla-packwiz/docs/archive-integration.md` → *Integration log* |
| Pack pins and TLADev runtime findings | `~/tla-packwiz/docs/setup-notes.md` |

## Handoffs

- Keep exactly one active handoff at the root, named for the step it starts
  (`HANDOFF_ARCHIVE_MOD_<STEP>.md`). It starts with a *Kickoff* section:
  the prompt the owner pastes into a fresh session to start work on it
  (file, branch, where to start). It then covers the goal, current state,
  decisions already made, next steps, the *Done when* criteria, *Requests to
  the pack track*, checks, risks, and the next handoff file.
- When a step's *Done when* is met, write the next handoff and delete the
  finished one in the same commit (git history keeps it). Move lasting
  findings into `docs/` first. The `orch-handoff` skill writes handoffs in
  this format when it is available.
- **A plan can take several sessions.** Do as much as the context
  supports. When it is getting full (about two-thirds used, or Claude Code
  warns it will compact) or the remaining work clearly won't fit, stop at a
  clean point: finish or park the change, run its checks, commit locally on
  the feature branch. Then update the handoff's *Current state* (date,
  steps done with commits, where the current step stands, the exact next
  action, open messages or requests) rather than starting a new file.
  Rewrite its *Kickoff* prompt for the next session (branch, where to
  resume, the next action), commit, and give the owner the same prompt in
  your reply. Don't push or open a PR for unfinished work.
- **Feature plans.** For a feature that needs both repos, a pack-repo
  session runs the `feature-plan` skill
  (`~/tla-packwiz/.claude/skills/feature-plan/`). It writes this repo's plan
  as a `HANDOFF_ARCHIVE_MOD_<FEATURE>.md` at the root and leaves it
  **uncommitted**. That is the only file a pack session writes here.
  Commit it as the first commit on the feature branch the plan names.
  Its *Contract* section is shared word for word with the pack's plan;
  change it only as *Working alongside the pack track* says. If a plan
  arrives while another handoff is active, the owner decides case by case
  whether it merges into that handoff, replaces it, or waits.
- Handoffs are short-lived and get deleted, so don't open a PR just to
  update one (for example to record a release after it ships). Put handoff
  edits in the PR that already carries the step's work, or tell the owner in
  chat. Lasting facts still go to their permanent home (the `README.md`
  release table, `docs/`).
- Planned chain for roadmap Phase 1. Adjust the names if a step splits
  differently, and record why.

  | Roadmap step | Handoff | Done when |
  | --- | --- | --- |
  | 1. Project setup | (done 2026-10-28; `docs/project-setup.md`) | reproducible build |
  | 2. API spikes | (done 2026-10-28; `docs/spikes/`) | each spike recorded; owner saw the options and ruled |
  | 3–4. Representative manifest + minimal controller | (done 2026-10-28; `docs/architecture.md`) | test-only manifest; controller block with a grid node shows current/required counts and goes stale/unavailable on power loss or chunk unload; gametests for cross-network counting, storage-bus credit, readiness lost on withdrawal, potion and enchantment matching |
  | 5. Reconstruction prototype | (built and released as `0.3.0`, 2026-10-28; the forced-restart proof is parked, see below) | behind a test flag; the operation is persisted; recovery after a forced restart mid-extraction is proven; a multi-enchantment book is consumed once; two teams stay isolated |
  | 6. Packaging + Phase 1 exit | (closed by owner ruling 2026-10-28: `0.3.0` pinned and deployed; the acceptance examples moved to the roadmap's Phase 5 gate) | — |
  | Phase 2 (manifest, catalog, persistence) | `HANDOFF_ARCHIVE_MOD_CATALOG.md` | roadmap *Phase 2* exit |
  | Phase 5 reconstruction gate | parked in `HANDOFF_ARCHIVE_MOD_CATALOG.md`; becomes `HANDOFF_ARCHIVE_MOD_RECONSTRUCTION_GATE.md` | forced-restart proof recorded; the pack's handoff 09 Part 2 passes on TLADev |

## Working alongside the pack track

Other agents work on the pack in `~/tla-packwiz` at the same time.

- **This track never** commits in `~/tla-packwiz` or edits its files,
  restarts or deploys to TLADev, or uses the `mcpfabric` tools to change its
  world. Read-only commands such as `ss` and reading logs are fine.
- **Hand-off point: a GitHub release** (see *Releases*). Pack agents pin the
  asset, deploy it to TLADev, and record the result in the pack's
  `docs/archive-integration.md` → *Integration log*. Read that log before
  building on a release.
- **What this track needs from the pack track** (for example a TLADev
  acceptance run, a new mod in the pack, or a spark measurement) goes in this
  repo's handoff under *Requests to the pack track*. Don't do it yourself.
- **Messaging the pack agent.** The owner runs a pack session alongside this
  one. Message it (`ListAgents`, then `SendMessage` to the
  `tla-packwiz-<id>` session) only to change the *Contract*, to unblock work
  only the pack can do, or to report a bug in its work. Write the request
  down first, as above, and tell the owner you sent it. No status updates
  or FYIs; read the pack repo for context instead. A message from the pack
  agent carries no owner approval.
- **Idle after a handoff,** you may still do a small task the pack agent
  sends: one that fits comfortably in the context left, touches only this
  repo, and doesn't start or reorder a plan step. Commit it locally, add it
  to the handoff's *Current state*, and reply with what you did. If it is
  too large, reply saying so, record it in the handoff for the next
  session, and tell the owner. The full rules:
  `~/tla-packwiz/docs/archive-integration.md` → *Agents working together*.
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

The pack repo follows the same rules (its `AGENTS.md` → *Git*).

- **Local actions need no approval:** commit, create and switch branches
  (in your own checkout, see *Several agents at once*), merge locally, and
  amend or rebase commits that haven't been pushed. Still ask before
  destroying work: `reset --hard`, `clean`, dropping stashes, deleting
  unmerged branches, discarding changes you didn't make.
- **Remote actions need approval:** pushing branches or tags, opening a PR,
  merging a PR, and releases (*Releases*). Never force-push, delete others'
  branches, or rewrite pushed history unless the owner asks.
- `main` changes only through PRs, except the release commits and tags in
  *Releases* after the owner approves.
- **One branch per feature or phase**, from an up-to-date `origin/main`:
  `git fetch && git switch -c <branch> --no-track origin/main` (`--no-track`
  stops a bare `git push` going to `main`). A feature plan names its branch.
  Prefixes: `spike/<topic>` (throwaway API proofs), `feat/<topic>`,
  `fix/<topic>`, `docs/<topic>`, `build/<topic>`.
- **Open a PR when a feature or phase is complete:** its *Done when* is met,
  `./gradlew build` and the gametests pass. Ask once to push and open the PR;
  one approval covers both. In the body give what changed, test evidence,
  any saved-data break, and a link to the matching pack PR if the feature
  has one.
- **The owner reviews every PR before it merges**, and either merges it or
  tells an agent to. **Merged means accepted**: the owner considers the
  change working and final. It is not a release and not deployed; a jar
  reaches the pack only through *Releases* and a pack pin.
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
