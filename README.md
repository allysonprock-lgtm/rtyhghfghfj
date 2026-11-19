# Base Finder 2.0 (Fabric, Minecraft Java 26.1.2, client-side)

A **passive** monitoring mod. It only analyses information the game client has already received. It never moves you or
your camera, never mines, breaks or interacts with blocks, never opens containers and never uses information the server
did not send you.

What it does:

- Finds likely player bases from loaded chunks and scores them with **three independent scores**: *Base likelihood*,
  *Structure condition* and *Functional infrastructure*, each with written reasons.
- Routes every qualifying base to **exactly one of three Discord channels**: Intact, Suspicious or Likely Griefed
  (rejected detections are saved locally and never sent).
- Sends **TOP PRIORITY** alerts, on their own channels, for other players and live ender pearl projectiles, and a
  notification when your own character dies.
- Builds 2D floor plans, 3D voxel and isometric **reconstructions** (clearly labelled, never presented as screenshots).
- A winter night-mountain menu with a dashboard, detection browser, Discord setup, storage statistics, alert history,
  map viewer and settings.

---------------------------------------------------------------------------------------------------------------------

## 1. Get the jar (GitHub Actions)

1. Upload everything in this folder to your GitHub repository (replace the old files; keep the hidden `.github` folder).
2. Open the **Actions** tab. The **Build mod** workflow runs on every push (or press *Run workflow*).
3. When it is green, download `basefinder-2.0.0.jar` from **Releases -> Latest build** or from the run's
   **Artifacts -> basefinder-jar**.
4. A red X means the build failed: open the failed step, copy the red error lines, and send them to whoever maintains the
   project. The `selftest-samples` artifact contains sample reconstruction PNGs and Discord payloads from the tests.

The workflow uses Java 25, Gradle 9.9.1 (the version that built your working 1.1.0 jar), Loom 1.15.x, Fabric Loader 0.20.1
and Fabric API 0.146.1+26.1.2.

## 2. Install

1. Fabric Loader **0.20.1 or newer** for Minecraft **26.1.2**, and Fabric API (0.146.1+26.1.2 or newer), on **Java 25**.
2. Put `basefinder-2.0.0.jar` in the `mods` folder.
3. Start the game. `config/basefinder/config.json` is created (or upgraded, see Migration).

## 3. Upgrading from 1.x (migration)

- Your old `config.json` is **backed up** first (`config/basefinder/config.backup-v<N>-<time>.json`). The backup contains
  your old webhook URL in plain text, so keep it private.
- **Nothing is deleted or overwritten**: all old settings stay, and your old single `discordWebhookUrl` is kept.
- The old webhook is **not used for routing** any more, so nothing is silently sent to the wrong channel. The menu
  (Discord page) shows an "Old single webhook found" card with buttons to copy it into the Intact, Suspicious or
  Griefed channel. You will also get one chat message explaining this.
- Old detections in `detections.json` load as "Legacy". They are analysed quietly (no duplicate Discord report) when
  you see the area again.
- `excludeRespawnAnchors` was renamed `rejectRespawnAnchors`; your value is carried over.

## 4. Discord setup

Create a channel -> **Edit Channel -> Integrations -> Webhooks -> New Webhook -> Copy Webhook URL**.

In the game open the menu (**Right Shift**, rebindable in *Options -> Controls -> Key Binds -> Base Finder*) ->
**Discord** page:

| Card | Receives |
|---|---|
| Intact Bases | promising, intact or mostly intact bases (green / ice blue) |
| Suspicious Bases | uncertain, rough, partly damaged or not-enough-data bases (amber) |
| Likely Griefed Bases | bases with strong multi-indicator damage evidence (dark red) |
| Player Alerts | TOP PRIORITY: another player is visible (orange) |
| Ender Pearl Alerts | TOP PRIORITY: a live ender pearl projectile (purple) |
| Death Alerts | your own character died |
| Priority Fallback | used only for player/pearl/death alerts whose own webhook is not set |

Copy a webhook URL, then press **Paste** on the card. Use **Enabled** to switch a destination off, **Clear** to remove
it and **Test** to send a test message to exactly that destination. Each card shows its status
(Not configured / Invalid / Ready / Sending / OK / error). URLs are masked on screen and are never written to the log.

Routing rules (these are enforced in one place, `DiscordManager`):

- Base channels only use their **own** webhook. No fallback, no duplicate copies.
- Priority alerts use their own webhook, else the optional Fallback webhook, else they stay local. They can never reach
  a base channel.
- A webhook you explicitly disabled or that is invalid is never silently re-routed.
- If a destination is not configured, the report is kept locally and you get one in-game hint.

## 5. The menu

| Page | What it shows |
|---|---|
| Dashboard | status, Pause/Resume, counts per class, nearby players, pearl/death counters, storage totals, recent activity |
| Detections | filter (All/Intact/Suspicious/Griefed/Rejected), sort, cards; click one for the full explanation, Copy coords, View on map |
| Discord | the seven destination cards above, legacy-webhook import, message options |
| Storage | chests (double = 1), barrels, shulkers, clusters, largest cluster, density, distribution |
| Alerts | history of player, pearl, death and base events with reasons |
| Map | floor plan, overhead and 3D voxel (cut-away) views, switch between recent detections |
| Settings | General, Detection, Storage, Filtering, Condition, Players, Pearls, Death, Screenshots, Performance, Appearance |

Mouse wheel / Up / Down / Page keys scroll, **Tab** and **1-7** switch pages, **Esc** closes. Every setting is saved
automatically a moment after you change it, and edits you make to `config.json` while playing are picked up within a few
seconds (a half-edited, invalid file is ignored and never overwritten). Turn off *Animations* and *Falling snow* in
Settings -> Appearance for a static menu.

## 6. How the scores work

**Base likelihood (0-100)** - how likely this is a player base. Weighted sum of: storage amount, storage
concentration (clustered storage counts more than scattered storage), workstations/machines, farms and animals,
enclosure (roof + walls around storage), underground placement, player-style building blocks, connected buildings.
Weights are configurable (`weightStorage` ... `weightConnected`). Suspicious blocks subtract a configurable penalty.
It does **not** depend on damage: a heavily damaged base can still be 95%.

**Structure condition (0-100 + label)** - how preserved the structure looks. Labels: Intact, Mostly intact, Rough
condition, Partially damaged, Significantly damaged, Likely griefed, Uncertain. Damage indicators (each is observed or
inferred, and listed with the reason): roof missing above walled storage, walls missing around roofed storage,
floating storage, loose building fragments, possible explosion craters, fire, lava next to storage. **Rough is not
griefed**: unfinished or messy bases score "Rough" without any damage. A roofless walled area only counts as damage
when other damage evidence agrees.

**Functional infrastructure (0-100)** - useful machinery: furnaces/smokers/blast furnaces, workstations, enchanting and
brewing, hoppers/droppers/dispensers/crafters, farmland and crops, beds, animals nearby, beacons/conduits.

## 7. How the three-way classification works

One engine (`ClassificationEngine`) decides, deterministically, using your thresholds:

1. **REJECTED** (saved locally with exact reasons, never sent): a hard rule matched (respawn anchor, isolated obsidian,
   live end crystal within the radius) or the likelihood is below `minimumBaseLikelihood` (only when enough of the area
   was observed).
2. **Likely Griefed**: damage score >= `griefedDamageThreshold`, at least `griefedMinIndicators` independent indicators
   including a structural one, and enough of the area observed. One missing wall, one odd block or a rough look is never
   enough.
3. **Intact**: likelihood >= `intactMinLikelihood`, condition Intact/Mostly intact, condition score >=
   `minimumBaseQuality`, enough area observed, damage within the mode limit, no suspicious blocks.
4. **Suspicious**: everything else, including every case with too little information. The reasons list says exactly which
   criterion was not met.

`suspiciousDetectionMode` (STRICT / BALANCED / LENIENT) shifts the thresholds, never the facts. Reclassification of an
already-reported base is recorded in the history and only sent to Discord when `notifyReclassification` is on.

## 8. What the client cannot know (and how Base Finder reports it)

- **Container contents**: the client only receives contents when a container is opened. Base Finder never opens
  containers, so it reports **"Inventory contents unavailable"**. This is not the same as empty.
- **Unloaded areas**: blocks outside loaded chunks are *unknown*. They are shown hatched on maps and are never treated as
  open space or damage. Every report states the observed percentage.
- **Who built a block / when it was destroyed**: not available. "Built" means block types players typically build with;
  damage is *inferred* from shapes. Reports label each reason as `[observed]`, `[inferred]` or `[unknown]`.
- **Death message text**: the client does not receive it as data. The notification says so.
- **Other players' positions** are only known while the server sends their entity to you (within entity tracking range).
- **Chests/entities outside render range** are not seen. Travel to cover more ground.

## 9. Configuration reference

`config/basefinder/config.json`. Unknown or missing fields are handled and invalid numbers are clamped. Highlights:

| Option | Meaning |
|---|---|
| `enabled`, `sensitivity`, `minStrongStorage`, `minScore`, `clusterRadius`, `wideScan`, ... | candidate stage (unchanged from 1.x) |
| `intactBasesWebhook` / `suspiciousBasesWebhook` / `griefedBasesWebhook` | `{ "enabled": true, "url": "..." }` |
| `playerAlertsWebhook` / `enderPearlAlertsWebhook` / `deathAlertsWebhook` / `priorityFallbackWebhook` | same shape |
| `rejectRespawnAnchors`, `rejectIsolatedObsidian`, `penalizeEndCrystals`, `excludeEndCrystals`, `detectExplosionDamage` | filtering |
| `minimumBaseLikelihood`, `intactMinLikelihood`, `minimumBaseQuality`, `damagePenalty`, `suspiciousDetectionMode` | scoring/routing |
| `griefedDamageThreshold`, `griefedMinIndicators`, `minCoverage`, `craterDepth` | condition |
| `weightStorage` ... `weightConnected` | likelihood weights |
| `playerAlerts`, `playerAlertCooldownSeconds`, `pearlAlerts`, `pearlAlertCooldownSeconds`, `ignoreOwnPearls`, `deathNotifications` | entity alerts |
| `analysisRadius`, `reanalyzeSeconds`, `rejectedRecheckSeconds`, `scanBudget`, `snapshotCacheSize`, `maxHistory` | performance |
| `snowAnimation`, `guiAnimations`, `snowDensity`, `starTwinkle` | appearance |
| `customSoundFile` | WAV in `config/basefinder/` for the in-game alert sound |

Local files: `detections.json` (all detections incl. rejected), `events.json` (alert history),
`reconstructions/<id>-plan|voxel|iso.png`.

## 10. Custom alert sound

Put an uncompressed `alert.wav` in `config/basefinder/` (or set `customSoundFile`). Otherwise a built-in sound plays.

## 11. Known limitations

See `IMPLEMENTATION_REPORT.md`. Notably: real screenshots and the remote Discord bot are **not** implemented.
