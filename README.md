# tla-archive — The Last Archive controller mod

Custom Applied Energistics 2 integration mod for
[The Last Archive](https://github.com/nturner03/tla) modpack (Minecraft
1.21.1, NeoForge): the archive controller, collection matcher, catalog
screen, and reconstruction.

Status: project setup done (roadmap Phase 1, step 1). The mod loads but has
no features yet. Next: API spikes (`HANDOFF_ARCHIVE_MOD_API_SPIKES.md`).

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
| (none released) | |

## License

LGPL-3.0-only. See [`COPYING.LESSER`](COPYING.LESSER) and [`COPYING`](COPYING).
