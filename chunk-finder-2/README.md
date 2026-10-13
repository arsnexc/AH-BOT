# Chunk Finder 2.3

Standalone Minecraft **1.21.11 Fabric** mod project. The original Chunk Finder 2.2 sources are preserved and extended here; this directory is **not** AH-BOT. Requires Java 21 to run Minecraft, Fabric Loader >=0.19.5 and Fabric API. Build using **JDK 25** for the Gradle/Fabric Loom toolchain.

## Build

From **this directory** (not the AH-BOT repository root):

```sh
cd chunk-finder-2
./gradlew --no-daemon clean build
```

On Windows use `gradlew.bat --no-daemon clean build`. Install `build/libs/chunk-finder-2-2.3.0.jar` in your instance's `mods/` folder. Do not install sources or development JARs, or an older Chunk Finder JAR alongside it. Building requires normal access to Gradle, Fabric, Mojang and Maven repositories.

## Controls and modules

- **K** opens the redesigned Module Studio. Select one of seven modules from the paginated library and use the paginated details pane to adjust its controls. **ESC** closes it. **G** toggles Freecam.
- **Chunk Finder**: budding-amethyst geode clues and activity-block heuristics in nearby loaded chunks. Work is budgeted at 4,096 blocks/tick; scans refresh after a cooldown. Sensitivity and radius are configurable.
- **Ancient Debris Finder**: scans loaded Nether chunks, traces/outlines found debris, shows coordinates. Radius, traces, outlines and manual rescan available.
- **Block ESP**: select a block by name/registry ID and highlight matches in loaded chunks. Radius, traces, outlines and rescan available.
- **Storage ESP**: highlights chests, trapped chests, barrels, ender chests, shulker boxes of every color, hoppers, dispensers and droppers in loaded chunks. Radius, traces, outlines and rescan available. Up to 128 matches per chunk are retained and the nearest 48 recorded matches are drawn.
- **Trajectories**: approximate predicted flight lines for moving projectile entities and, optionally, dropped-item entities. Traces stop at client-visible block collisions; prediction steps are adjustable. The physics and impact location are **estimates**, not a promise of the server result.
- **Target Renderer**: outlines the entity or (optionally) block under the vanilla crosshair, with a small target label. The max-distance filter does not extend vanilla targeting reach.
- **Freecam**: local detached camera view, with speed slider and Sprint boost. Your player remains in place; it cannot load new chunks.

Module state/config is local to your Minecraft instance's `config/` directory. New files: `storage_esp.json`, `chunkfinder-trajectories.json`, `chunkfinder-target.json`. The old `chunkfinder.json`, `ancient_debris_finder.json`, `item_esp.json` names remain compatible. All modules default to off on a clean install.

**Limits:** only information already sent to the client can be scanned. Server-side anti-xray can hide ore/storage data. Searches are local visual aids; the code does not automate mining or movement. The included server `/chunkfinder` commands only send a permission advisory: the current client implementation does not enforce it. The server cannot force an untrusted client to honor a visual permission payload. Respect multiplayer server rules.

## Project structure

`src/main/java` contains the common server payload and commands. `src/client/java` contains the module manager, UI, scanning, prediction and rendering. `src/main/resources` contains Fabric metadata/key translations. This project uses Java 21 language level, the 1.21.11 official Mojang mappings, Fabric Loom and a Gradle wrapper.

## Verification note

The source archive includes historical 2.2 build outputs. They **do not** contain these 2.3 changes. Build the 2.3 JAR on your machine using the command above. If a build fails, share the first compiler error and relevant lines rather than a truncated log.
