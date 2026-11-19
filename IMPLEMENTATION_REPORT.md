# Base Finder 2.0 - Implementation Report

Target: Minecraft 26.1.2, Fabric Loader 0.20.1+, Fabric API 0.146.1+26.1.2, Java 25, Gradle + Loom 1.15.x.

## 1. Build verification (honest summary)

| Check | Result |
|---|---|
| Real Gradle build / JAR | **NOT done in my environment** (no network, cannot download Minecraft/Loom). GitHub Actions must perform the real compile. |
| Evidence the toolchain works | The `basefinder-1.1.0.jar` you uploaded was built by your GitHub Actions: class version 69 (Java 25), Loom 1.15.5, Gradle 9.9.1, Loader 0.19.2. The workflow now uses Gradle 9.9.1 to match. |
| Analysis core (no Minecraft types) | Compiled with `javac -Xlint:all` with no warnings. **52/52** self tests pass (`dev.basefinder.core.SelfTest`). |
| Minecraft-side code | Compiled with `javac -Xlint:all` against **hand-written API stubs** (not the real game). This proves my own code is type-correct, not that Minecraft's real signatures match my assumptions. **65/65** behavioural tests pass against that build. |
| GUI | All 7 pages + detail + 3 map modes rendered to 28 images at two window sizes through a software stand-in for the graphics class and reviewed. Real in-game rendering was not seen. |

### API risk register (what could still fail to compile on GitHub)
I compared every Minecraft/Fabric/LWJGL member my code calls with the symbols inside your working 1.1.0 jar.
That comparison found a real bug: my code called `Player.displayClientMessage`, which does not exist in 26.1.2 (your
working build uses `LocalPlayer.sendSystemMessage` / `sendOverlayMessage`). Fixed.

- 44 members used by 2.0 are **not proven** by the 1.1.0 build. Most are long-stable core API (`BlockPos`, `Entity`,
  `ChatFormatting` constants, `GLFW` functions, `BlockState.is/hasProperty/getValue`, `ChestBlock.TYPE`).
- The riskiest ones are the key-mapping API (`KeyMappingHelper`, `KeyMapping.Category`, `Identifier`), taken from the
  official 26.1.2 Fabric docs, and `ClientLevel.players()`.
- To avoid unverified signatures I **removed** dependencies on: `Screen.mouseClicked/MouseButtonEvent` (clicks are now
  polled via GLFW), `GuiGraphicsExtractor.text(Component)` (bold is drawn twice), and made these **reflective with safe
  fallbacks**: GUI scissor, the client entity list, and the respawn-anchor chunk palette scan.
- If the build still fails, send the red error lines; each is a one-line fix.

## 2. Features implemented

- **Detection engine**: two stages. Stage 1 (unchanged 1.x idea): weighted signal clusters from loaded chunks, wide scan,
  village-bell filter. Stage 2 (new): a snapshot of the surrounding blocks is analysed on a background thread.
- **Storage analysis**: chests, trapped chests, barrels, shulker boxes; double chests counted once; single-linkage
  clusters with configurable distance; sizes, centres, bounding boxes, density, isolated storage, concentrated rooms,
  mixed clusters, nearest-neighbour distance, largest cluster, distribution; concentrated storage scores higher.
- **Container contents**: investigated; reported as "Inventory contents unavailable" (never opened, never claimed).
- **Three scores** with reasons and configurable weights: Base likelihood, Structure condition (7 labels), Functional
  infrastructure.
- **Suspicious/damage analysis**: live end-crystal *entities* (never items), isolated single obsidian (portal frames are
  not isolated), respawn anchors (blocks + chunk scan), TNT, fire, lava; roof/wall/floor/fragment/crater indicators.
  Settings: `rejectRespawnAnchors`, `rejectIsolatedObsidian`, `penalizeEndCrystals`, `detectExplosionDamage`,
  `minimumBaseQuality`, `damagePenalty`, `suspiciousDetectionMode`.
- **Centralised three-way classification** (Intact / Suspicious / Likely Griefed + Rejected), deterministic, explained,
  conservative when data is missing; classification history; optional reclassification notifications; no duplicate reports.
- **Rejected detections** saved locally with exact reasons; never sent to base channels.
- **Discord**: seven independent destinations, per-destination enable/URL/status/test, validation, masked URLs, no URLs in
  logs, async workers (separate pool for priority alerts), rate-limit handling (`Retry-After`, bucket headers), retries,
  oversized-attachment guard, bounded queues, `allowed_mentions` disabled.
- **Migration**: automatic backup, nothing deleted, old webhook preserved but never used for routing, import buttons.
- **Top-priority alerts**: other players (local player excluded by UUID/name/object, survives respawn/reconnect/dimension
  change, per-player cooldown, fail-safe until identity known), live ender pearl projectiles only (dropped item entities
  ignored, one alert per projectile UUID, cleanup, optional ignore-own), own-death notification (one per death,
  history, GUI + config toggle).
- **Reconstructions**: floor plan, overhead, cut-away 3D voxel and isometric PNGs (attached to Discord, saved locally) plus
  an in-game map viewer. Unknown/unloaded cells hatched; every image states it is a reconstruction.
- **Winter GUI** (new, not a recolour): night-mountain scene, twinkling stars, aurora, snow caps, falling snow, frosted
  glass cards, pill buttons and switches, hover easing, tab transitions, spinners, tooltips, scrolling, keyboard
  navigation, empty/loading/error states, compact layout for small windows, animations switchable off. All 7 pages use
  real data and every control writes the real config.
- **Config**: autosave, live reload (invalid file ignored, never overwritten), missing fields handled, values clamped.
- **Performance**: scan budgets, analysis off-thread, snapshot LRU cache, bounded history/queues/caches, chunk-unload
  cleanup, per-base re-analysis cooldowns.

## 3. Partially implemented

- **Infrastructure remnants / building foundations / hallways / entrances**: covered indirectly (built-block counts,
  connected components, doors counted, enclosure rays). No explicit hallway/foundation detector.
- **Animal pens**: counted as nearby livestock; pen enclosure is not verified.
- **Death message**: not provided by the client; the notification states that.
- **Keyboard navigation**: Tab / 1-7 / arrows / Page keys (polled). No per-button focus ring.
- **Map view**: oblique voxel preview in the menu; true isometric polygons only in the PNGs.

## 4. Not implemented

- **Screenshots (Part 16)**: not implemented. I could not verify the 26.1.2 screenshot API and will not guess. The settings
  page says so. Reconstructions are used instead.
- **Remote Discord control (Part 21)**: not implemented. A webhook is one-way; commands need an authenticated bot/interaction
  service. Nothing in the mod claims otherwise.
- **Container content analysis**: impossible without opening containers (which the mod must never do).

## 5. Known limitations

- Heuristics: "built" = block types players build with; craters, breached walls and fragments are inferred. A big
  natural structure (e.g. a bastion or trial chamber) can still look like a base; raise `minScore` or the preset.
- Snapshot capture (about 100k block reads) runs on the game thread, at most about twice a second.
- Only loaded chunks are seen; end crystals/animals only while their entities are tracked.
- Webhook URLs are stored in plain text in `config.json` (unavoidable) and in the migration backup.

## 6. Review passes

1. **Architecture/requirements**: checked every part of the spec; found screenshots and the bot missing (documented);
   confirmed Discord routing is centralised, classification is separate from likelihood, local player exclusion exists.
2. **Code correctness**: stub compilation found 5 bugs (access modifier, wrong argument, garbage expression, wrong return
   type, wrong generic); comparison with your jar found the `displayClientMessage` bug and 3 unsafe API dependencies,
   replaced as above.
3. **Functional/edge cases**: 52 core + 65 behavioural tests (storage, double chests, classification routing, griefed vs
   rough vs one missing wall, crystals/anchors/obsidian, low coverage, determinism, payload validity, webhook routing and
   isolation, migration, exclusion after respawn, player/pearl/death duplicates and cooldowns, rejected/reclassified
   reporting, legacy silence). Found and fixed: a roofless-but-unfinished build was counted as damage; low coverage could
   cause rejection instead of "suspicious".
4. **Performance/security/reliability**: found a live-reload path that could overwrite a half-edited config with defaults
   (fixed), an unbounded hover cache and an entity-id lookup per entity per tick (cached); verified bounded queues/maps,
   no URL logging, off-thread analysis, thread-confined Minecraft access.
5. **UX/visual**: rendered every page at two sizes; fixed low-contrast tags, too-translucent panels, clipped sidebar
   labels in compact mode, overflowing text, never-clearing status messages.
6. **Final independent audit**: re-ran every test and the strict compile, validated all generated Discord JSON against
   Discord's size limits, scanned for secrets, rechecked metadata/workflow/resources, and re-diffed against the v1.2
   backup.

## 7. GitHub Actions changes

Gradle pinned to 9.9.1 (matches the working build); Java 25; version printout; `gradle selfTest` step (non-blocking, shows
a warning); `gradle build`; jar artifact (error if missing); self-test sample artifact; build reports on failure;
release publishing made non-blocking. The `.github` folder is included.

## 8. Remaining manual configuration

Create the Discord webhooks and paste them in the menu (Discord page). Optionally add `alert.wav`.

## 9. File record (vs the 1.2 source, backed up in `backup/basefinder-v1.2-source.zip`)

**Added** (34): `Channel, DeathDetection, DetectionPipeline, DiscordManager, EnderPearlDetection, EntityView, HistoryStore,
LocalPlayerExclusion, PlayerDetection, SettingsRegistry, Theme, Ui, WinterBackground, WorldSampler` and the whole
`core/` package (`BaseReport, BaseScoringEngine, BlockKinds, Cat, ClassificationEngine, EmbedFactory, ImageRenderer, Json,
Multipart, Observation, Palette, Params, SelfTest, Snapshot, StorageAnalyzer, StoragePoint, StorageReport,
StructureAnalyzer, StructureReport, WebhookUtil`), `IMPLEMENTATION_REPORT.md`, `backup/`.

**Modified**: `.github/workflows/build.yml, build.gradle, gradle.properties, fabric.mod.json, Alerts, BaseFinderClient,
BaseFinderScreen (full redesign), Config, DetectionStore, Notifier, Scanner, SoundPlayer, README.md`.

**Removed**: none. **Unchanged**: `.gitignore (extended), LICENSE, settings.gradle, lang file`.
