# tla-archive — The Last Archive controller mod

Custom Applied Energistics 2 integration mod for
[The Last Archive](https://github.com/nturner03/tla) modpack (Minecraft
1.21.1, NeoForge): the archive controller, collection matcher, catalog
screen, and reconstruction.

Status: roadmap Phase 1, steps 1–4 done. The archive controller block
counts an AE2 network's stock against a **test-only** representative
manifest and shows live or stale counts. How it works:
[`docs/architecture.md`](docs/architecture.md). Next: the reconstruction
prototype (`HANDOFF_ARCHIVE_MOD_RECONSTRUCTION.md`).

Gametests live in the `gametest` source set and run with
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew runGameTestServer`
(binds no port). They are not part of the release jar.

## Build

Requires JDK 21. Always use the pinned wrapper:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew build
```

Output: `build/libs/tla_archive-<version>.jar`. Builds are reproducible. Two
clean builds of the same commit give the same SHA-256.

Dev runs (`runClient`, `runServer`, `runGameTestServer`) load the pack's
pinned AE2, GuideME, FTB Teams, FTB Library, and Architectury. Run data goes
to the ignored `run/` directory. `runServer` binds a game port. Allocate one
in the host port inventory before using it (see `AGENTS.md`).

Pins, Maven coordinates, and verification: [`docs/project-setup.md`](docs/project-setup.md).

## Releases

Built jars are published as GitHub release assets and pinned into the pack
with `packwiz url add`. Jars are never committed. Each release records its
jar's SHA-256 here.

| Version | Jar SHA-256 |
| --- | --- |
| [`0.1.0`](https://github.com/nturner03/tla-archive-mod/releases/tag/v0.1.0) (skeleton, no features) | `f686a8884889c350d657ca8fe243d04ad7ca31deda4d7ec70764ee1ab9b315e4` |

## License

LGPL-3.0-only. See [`COPYING.LESSER`](COPYING.LESSER) and [`COPYING`](COPYING).
