# Handoff: representative manifest and minimal controller (roadmap Phase 1, archive track steps 3–4)

## Goal

Build the first production code: a test-only representative manifest, the
matcher that maps AE2 keys to manifest entries, and a minimal archive
controller block with a grid node that shows current/required counts and
goes stale/unavailable on power loss or chunk unload. Plan:
`~/tla-packwiz/docs/roadmap.md` → *Phase 1* → *Archive track* steps 3–4.
How the pieces fit together: `docs/architecture.md`.

## Current state (2026-10-28)

- Step 2 (API spikes) is done. Findings are in `docs/spikes/`:

  | Spike | Verdict | Doc |
  | --- | --- | --- |
  | Grid stock | works. The cache counts cells and storage buses; simulation matched real extraction. Storage-bus listings lag; an unpowered grid reads as empty. | `grid-stock-and-extraction.md` |
  | Atomic multi-key extraction | not in AE2. Journalled staging with compensation, never voiding, works in gametests. | `grid-stock-and-extraction.md` |
  | FTB Teams identity | works. Key on `team.getId()`; listen to `PLAYER_CHANGED` and `DELETED`. | `ftb-teams.md` |
  | Incremental observation | partly. A stack watcher rebuilds the whole cache every tick. Use stale marking plus a bounded per-tick sweep. | `incremental-observation.md` |
  | MEGA Cells | normal cells work; bulk compression overlaps (ruled out, see below) | `storage-addons.md` |
  | AE2 Things DISKs | work; same-UUID copies double-count (accepted, see below) | `storage-addons.md` |

- Spike code is throwaway and lives only on its branches:
  `spike/grid-extraction` (AE2 gametest harness, `StagedExtraction`),
  `spike/ftb-teams`, `spike/incremental-observation`, `spike/storage-addons`
  (dev-run coordinates for MEGA Cells and AE2 Things). Reuse ideas from them,
  but review any code before it moves to production (`AGENTS.md` → *Rules*).
- `main` still contains only the `@Mod` entry point. The skeleton `0.1.0`
  (no features) was released on 2026-10-28 with the owner's approval:
  <https://github.com/nturner03/tla-archive-mod/releases/tag/v0.1.0>,
  SHA-256 in `README.md`. Pinning it is pack-track work (request 3).

## Owner rulings (2026-10-28)

Given in a mod-track session after the owner read the spike findings. They
still have to be added to the pack's design document from a pack-repo
session (see *Requests to the pack track*). Until then, this table is the
record.

| # | Decision | Ruling | What it means for the code |
| --- | --- | --- | --- |
| 1 | Power loss and chunk unload | **Stale.** Show the last known counts labelled stale with a time, and disable reconstruction until the network is active again. | Never write zeros from an inactive grid into the cache. A partly unloaded network may only lower readiness. Don't try to guess what is missing. |
| 2 | Team keying and ally access | **Team id of the placer; allies denied.** | The controller stores `team.getId()` at placement. Every interaction checks the player's current team id. A controller counts only the AE2 network it is physically on. |
| 3 | Interrupted final consumption | **Roll back.** A fully staged operation found on load is returned to storage, and the player must start again. | `recover` always rolls back. Stock that won't fit back stays in the journal as a visible overflow and is never voided. |
| 4 | Hard-crash window | **Accept** the risk, the same as AE2's crafting CPU. No forced world saves. | The journal is ordinary SavedData, saved with the world. |
| 5 | MEGA Cells compression | **Disable compression in the pack.** | The mod does not special-case bulk cells. The pack track disables the feature (see requests). |
| 6 | Duplicated AE2 Things DISKs | **Ignore.** | No duplicate-UUID guard. |
| 7a | Solo player joins or creates a party | The archive stays with the personal team. To move it, break and re-place it while in the party. | Ownership is set only at placement. No automatic transfer. |
| 7b | Player who has an archive joins another party | Theirs goes dormant until they leave or re-place it. No merging. | as 7a |
| 7c | Someone leaves a party | They get back their personal team with its pre-party state. They lose no party archive data. | Nothing to do beyond FTB's behaviour. |
| 7d | Party disbands | Its controllers freeze: read-only, reconstruction off. A former member can claim one by breaking and re-placing it. | On `DELETED`, or a lazy `!team.isValid()` check, mark the controller frozen. Never reuse the id. |
| 7e | Team change during a reconstruction | Abort and roll back. | The reconstruction step (5) listens to `PLAYER_CHANGED` and `DELETED`. |

## Next steps

1. **Gametest home.** Production gametests shouldn't ship in the release
   jar. Recommended: a separate `gametest` source set that only the dev
   runs load (ModDevGradle `mods { sourceSet(...) }` plus
   `neoforge.enabledGameTestNamespaces`). Port the harness from
   `spike/grid-extraction` `SpikeGrid` after review. Keep its two
   requirements: force-load the test chunks, and look up block-entity nodes
   through a real side, never `null`.
2. **Representative manifest (test-only).** Load it as data, so it can be
   edited independently of the matcher (design: *Completion model*). Label
   every quantity `TEST-ONLY placeholder`. Keys follow the pack's candidate
   format (`~/tla-packwiz/docs/manifest/README.md`):

   | Key | Covers | Rule | Edge case (`~/tla-packwiz/docs/edge-cases.md`) | Test quantity |
   | --- | --- | --- | --- | --- |
   | `diamond` | stackable item | `item_id` | EC-ordinary-id | 64 |
   | `iron_pickaxe` | damaged, renamed tool | `item_id`; ignore name and durability | EC-ordinary-id | 1 |
   | `blue_banner` | coloured banner with patterns | `banner_base_color` (the item id encodes the colour) | EC-banners | 1 |
   | `potion:strength` | drinkable, splash, lingering; any strength or duration | `potion_effect` | EC-potion-forms | 1 |
   | `potion:swiftness` | an unrelated effect, to check that strength doesn't satisfy it | `potion_effect` | EC-potion-forms | 1 |
   | `enchanted_book:efficiency` | multi-enchantment book (with fortune) | `enchantment_on_book`; any level | EC-enchanted-books | 1 |
   | `enchanted_book:fortune` | the same book satisfies both | `enchantment_on_book` | EC-enchanted-books | 1 |

   Effectless and multi-effect potions are still a deferred case
   (EC-potion-bases), so leave them out.
3. **Matcher.** Maps each `AEItemKey` in the grid to the manifest entries it
   satisfies, using the item id and the data components for potion
   contents and stored enchantments. One key can satisfy several entries (a
   multi-enchantment book). Matching never alters item data.
4. **Controller block.** A block and block entity with an AE2 managed grid
   node; recording the placer's team (ruling 2); and stale/unavailable/frozen
   states from the node listener and `isActive()`/`isPowered()` (rulings 1
   and 7d). Readiness comes from `getCachedInventory()` through the matcher,
   recalculated in bounded slices per tick (`incremental-observation.md` →
   *Recommended observation design*). Don't use a whole-grid stack watcher.
   The display can be minimal (a text screen or a chat/command readout). The
   full catalog UI is Phase 2.
5. **Persistence.** Store controller identity, owner team and settings
   in the block entity or SavedData. Cached counts are a cache only and are
   never earned credit (design: *Team state, persistence, and performance*).
6. Write `HANDOFF_ARCHIVE_MOD_RECONSTRUCTION.md` when *Done when* is met.

## Done when

- The test-only manifest above loads, and every quantity in it is labelled
  test-only.
- A controller block with a grid node shows current/required counts per
  entry. It goes stale (last counts kept and labelled, reconstruction
  marked unavailable) on power loss and chunk unload, and returns to live
  when the network is active again.
- Gametests pass for:
  - cross-network counting: production stock on the same network counts,
    an unrelated network doesn't;
  - storage-bus credit, and its loss when the bus is disconnected;
  - readiness lost on withdrawal and restored on restocking;
  - potion matching (a splash strength II satisfies `potion:strength`, and
    swiftness doesn't);
  - enchantment matching (one efficiency + fortune book satisfies both);
  - a damaged, renamed pickaxe and a patterned blue banner satisfying their
    entries;
  - stale state on power loss.
- `docs/architecture.md` describes what was built, not only the plan.

## Requests to the pack track

Things this track needs done in `~/tla-packwiz` or on TLADev. A pack
session does them and reports in the pack handoff's *Integration log*.

1. **Record the owner rulings above in the design document** (and in
   `docs/edge-cases.md` where they touch matching): power/chunk policy,
   team keying, interrupted final consumption, the hard-crash window, MEGA
   compression, AE2 Things duplicates, and team changes 7a–7e.
2. **Disable MEGA Cells compression in the pack** (ruling 5). Find out
   whether MEGA Cells `4.11.0` can turn it off by config, or whether the
   compression card's recipe has to be removed. Record how it was done in
   `setup-notes.md`.
3. **Pin the `0.1.0` skeleton release**
   (<https://github.com/nturner03/tla-archive-mod/releases/tag/v0.1.0>,
   released 2026-10-28). See the pack's
   `docs/local/handoffs/04-archive-mod-integration.md`.
4. **Deferred, by the owner's ruling on performance tests:** a spark
   baseline of AE2's cache rebuild on TLADev
   (`docs/spikes/incremental-observation.md` → *Requests to the pack
   track*). Ask for it when optimisation work begins.

## Checks to run

- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew build`
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew runGameTestServer`
  (no port): all gametests pass.
- The release jar contains no gametest classes or spike packages
  (`unzip -l build/libs/tla_archive-*.jar`).
- `git status` shows no jars apart from the wrapper and no `run/` data.

## Known risks

- Per-entry matching across many data-bearing keys (potions, books) costs
  more than looking up exact keys. Keep the sweep bounded and measure it
  in gametests.
- Storage-bus listings lag behind the external inventory. Readiness can
  briefly overstate or understate; reconstruction (step 5) must revalidate
  with simulated extraction.
- A network split can leave the controller on a new, empty grid without
  zero callbacks (`incremental-observation.md`). Reset the counts on the
  grid-change callback.
- Real chunk unload and reload, and a real server restart, have only been
  reasoned about from source or modelled, not run.

## Next handoff file

`HANDOFF_ARCHIVE_MOD_RECONSTRUCTION.md` (roadmap step 5): reconstruction
behind a test flag, using the staging journal (`StagedExtraction` on
`spike/grid-extraction`, after review) with ruling 3 (roll back) and
ruling 7e. Its *Done when*: the operation is persisted; recovery after a
forced restart mid-extraction is proven; a multi-enchantment book is
consumed once; two teams stay isolated.
