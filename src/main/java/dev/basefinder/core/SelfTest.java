package dev.basefinder.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-Java tests for the Minecraft-independent analysis core (no JUnit needed).
 * Run: java -cp <classes> dev.basefinder.core.SelfTest [outputDir]
 */
public final class SelfTest {
    private static int pass, fail;
    private static final List<String> failures = new ArrayList<>();

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("PASS  " + name);
        } else {
            fail++;
            failures.add(name);
            System.out.println("FAIL  " + name);
        }
    }

    // ------------------------------------------------------------------ scene builders

    public static final int SX = 49, SY = 41, SZ = 49, OX = 976, OY = 48, OZ = 976;

    public static Observation newScene() {
        Observation o = new Observation();
        o.snap = new Snapshot("minecraft:overworld", OX, OY, OZ, SX, SY, SZ);
        for (int z = 0; z < SZ; z++)
            for (int x = 0; x < SX; x++)
                for (int y = 0; y < SY; y++) o.snap.set(x, y, z, y <= 15 ? Cat.NATURAL : Cat.AIR);
        o.centerX = OX + 24;
        o.centerY = OY + 17;
        o.centerZ = OZ + 24;
        return o;
    }

    static void fill(Snapshot s, int x1, int y1, int z1, int x2, int y2, int z2, Cat c) {
        for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) for (int x = x1; x <= x2; x++) s.set(x, y, z, c);
    }

    static void addStorage(Observation o, int x, int y, int z, StoragePoint.Kind k, boolean dbl) {
        Cat c = k == StoragePoint.Kind.BARREL ? Cat.BARREL : k == StoragePoint.Kind.SHULKER ? Cat.SHULKER : Cat.CHEST;
        o.snap.set(x, y, z, c);
        if (dbl) o.snap.set(x + 1, y, z, c);
        o.storage.add(new StoragePoint(OX + x, OY + y, OZ + z, k, dbl));
    }

    /** A solid 15x15 stone-brick storage hall with a roof, door, windows, 18 storage units, workstations and a farm. */
    public static Observation intactBase() {
        Observation o = newScene();
        Snapshot s = o.snap;
        fill(s, 17, 16, 17, 31, 16, 31, Cat.BUILT);              // floor
        fill(s, 17, 17, 17, 31, 19, 31, Cat.BUILT);              // solid block
        fill(s, 18, 17, 18, 30, 19, 30, Cat.AIR);                // hollow
        fill(s, 17, 20, 17, 31, 20, 31, Cat.BUILT);              // roof
        fill(s, 24, 17, 17, 24, 18, 17, Cat.DOOR);               // door
        s.set(17, 18, 24, Cat.GLASS);
        for (int x = 18; x <= 25; x += 2) addStorage(o, x, 17, 18, StoragePoint.Kind.CHEST, true); // 4 double chests
        for (int x = 26; x <= 29; x++) addStorage(o, x, 17, 18, StoragePoint.Kind.CHEST, false);   // 4 single
        for (int x = 18; x <= 23; x++) addStorage(o, x, 17, 30, StoragePoint.Kind.BARREL, false);  // 6 barrels
        for (int z = 20; z <= 23; z++) addStorage(o, 30, 17, z, StoragePoint.Kind.SHULKER, false); // 4 shulkers
        for (int x = 26; x <= 28; x++) s.set(x, 17, 30, Cat.INFRA);                               // furnaces
        s.set(25, 17, 30, Cat.INFRA);                                                              // crafting table
        s.set(20, 17, 26, Cat.BED);
        s.set(21, 17, 26, Cat.BED);
        fill(s, 36, 16, 20, 41, 16, 25, Cat.FARMLAND);
        fill(s, 36, 17, 20, 41, 17, 25, Cat.CROP);
        o.blockEntities.put("minecraft:furnace", 3);
        o.blockEntities.put("minecraft:hopper", 2);
        o.livestock = 4;
        return o;
    }

    static void crater(Snapshot s, int cx, int cz, int r) {
        for (int z = cz - r; z <= cz + r; z++)
            for (int x = cx - r; x <= cx + r; x++)
                for (int y = 8; y <= 15; y++) {
                    double dx = x - cx, dy = y - 15, dz = z - cz;
                    if (dx * dx + dy * dy + dz * dz <= r * r + 1) s.set(x, y, z, Cat.AIR);
                }
    }

    public static Observation griefedBase() {
        Observation o = intactBase();
        Snapshot s = o.snap;
        fill(s, 17, 20, 17, 26, 20, 31, Cat.AIR);                // roof blown off over most of the hall
        fill(s, 17, 17, 22, 17, 19, 31, Cat.AIR);                // west wall breached
        fill(s, 17, 17, 17, 31, 19, 17, Cat.AIR);                // north wall gone
        fill(s, 18, 16, 18, 25, 16, 18, Cat.AIR);                // floor under the chest row gone -> floating
        crater(s, 13, 24, 5);                                    // two craters beside the hall
        crater(s, 35, 12, 5);
        // scattered rubble from the explosion
        s.set(14, 17, 20, Cat.BUILT); s.set(33, 19, 15, Cat.BUILT); s.set(38, 17, 30, Cat.BUILT);
        return o;
    }

    public static Observation roughBase() {
        Observation o = newScene();
        Snapshot s = o.snap;
        fill(s, 18, 16, 18, 26, 17, 18, Cat.BUILT);              // unfinished low walls on the ground, no roof
        fill(s, 18, 16, 26, 26, 17, 26, Cat.BUILT);
        fill(s, 18, 16, 18, 18, 17, 26, Cat.BUILT);
        fill(s, 26, 16, 18, 26, 17, 26, Cat.BUILT);
        for (int x = 19; x <= 25; x++) addStorage(o, x, 16, 19, StoragePoint.Kind.CHEST, false);
        for (int x = 19; x <= 22; x++) addStorage(o, x, 16, 25, StoragePoint.Kind.BARREL, false);
        s.set(24, 16, 25, Cat.INFRA);
        o.blockEntities.put("minecraft:furnace", 1);
        return o;
    }

    // ------------------------------------------------------------------ tests

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "selftest-out");
        Files.createDirectories(out);
        Params p = new Params();

        // ---- storage ----
        List<StoragePoint> pts = new ArrayList<>();
        pts.add(new StoragePoint(0, 0, 0, StoragePoint.Kind.CHEST, true));
        pts.add(new StoragePoint(3, 0, 0, StoragePoint.Kind.CHEST, true));
        pts.add(new StoragePoint(5, 0, 0, StoragePoint.Kind.TRAPPED_CHEST, false));
        pts.add(new StoragePoint(5, 0, 3, StoragePoint.Kind.BARREL, false));
        pts.add(new StoragePoint(2, 1, 4, StoragePoint.Kind.SHULKER, false));
        pts.add(new StoragePoint(100, 0, 100, StoragePoint.Kind.CHEST, false));
        StorageReport sr = StorageAnalyzer.analyze(pts, 6);
        check("storage: double chest counted as one unit (4 chests incl. 2 double, trapped included)", sr.chests == 4 && sr.doubleChests == 2 && sr.trappedChests == 1);
        check("storage: totals", sr.total == 6 && sr.barrels == 1 && sr.shulkers == 1);
        check("storage: 2 clusters (5 + 1 isolated)", sr.clusters.size() == 2 && sr.largest.count == 5 && sr.isolated == 1);
        check("storage: mixed cluster detected", sr.mixedClusters == 1);
        check("storage: contents never claimed available", !sr.contentsAvailable);
        check("storage: concentration reflects clustering", sr.concentration > 0.7 && sr.concentration < 0.9);
        check("storage: empty list is safe", StorageAnalyzer.analyze(new ArrayList<>(), 6).total == 0);

        // ---- block classification ----
        check("blocks: chest/barrel/shulker", BlockKinds.classify("minecraft:chest") == Cat.CHEST
                && BlockKinds.classify("minecraft:trapped_chest") == Cat.CHEST
                && BlockKinds.classify("minecraft:barrel") == Cat.BARREL
                && BlockKinds.classify("minecraft:lime_shulker_box") == Cat.SHULKER);
        check("blocks: respawn anchor / obsidian / tnt", BlockKinds.classify("minecraft:respawn_anchor") == Cat.ANCHOR
                && BlockKinds.classify("minecraft:obsidian") == Cat.OBSIDIAN && BlockKinds.classify("minecraft:tnt") == Cat.TNT);
        check("blocks: built vs natural vs passable", BlockKinds.classify("minecraft:stone_bricks") == Cat.BUILT
                && BlockKinds.classify("minecraft:oak_planks") == Cat.BUILT && BlockKinds.classify("minecraft:dirt") == Cat.NATURAL
                && BlockKinds.classify("minecraft:short_grass") == Cat.PLANT && BlockKinds.classify("minecraft:oak_door") == Cat.DOOR
                && BlockKinds.classify("minecraft:grass_block") == Cat.NATURAL && BlockKinds.classify("minecraft:campfire") == Cat.INFRA
                && BlockKinds.classify("minecraft:wheat") == Cat.CROP && BlockKinds.classify("minecraft:red_bed") == Cat.BED
                && BlockKinds.classify("minecraft:blast_furnace") == Cat.INFRA && BlockKinds.classify("minecraft:glass_pane") == Cat.GLASS);

        // ---- scenarios ----
        Observation intact = intactBase();
        BaseReport ri = BaseScoringEngine.analyze(intact, p);
        dump("INTACT", ri);
        check("intact base: routed to INTACT", ri.classification == BaseReport.BaseClass.INTACT);
        check("intact base: outcome GOOD_POTENTIAL", ri.outcome == BaseReport.Outcome.GOOD_POTENTIAL);
        check("intact base: condition intact/mostly", ri.condition == BaseReport.Condition.INTACT || ri.condition == BaseReport.Condition.MOSTLY_INTACT);
        check("intact base: 18 storage units, 8 chests (4 double)", ri.storage.total == 18 && ri.storage.chests == 8 && ri.storage.doubleChests == 4);
        check("intact base: likelihood high, infra > 0", ri.likelihood >= 55 && ri.infraScore > 20);
        check("intact base: no damage indicators", ri.indicators.isEmpty());
        check("intact base: sealed room found", ri.structure.sealedRooms >= 1);

        Observation grief = griefedBase();
        BaseReport rg = BaseScoringEngine.analyze(grief, p);
        dump("GRIEFED", rg);
        check("griefed base: routed to GRIEFED", rg.classification == BaseReport.BaseClass.GRIEFED);
        check("griefed base: likelihood stays high (independent of damage)", rg.likelihood >= 45);
        check("griefed base: >=3 independent indicators", rg.indicators.size() >= 3);
        check("griefed base: craters found", rg.structure.craters.size() >= 1);
        check("griefed base: damage score high", rg.damageScore >= p.griefedDamageThreshold);

        Observation rough = roughBase();
        BaseReport rr = BaseScoringEngine.analyze(rough, p);
        dump("ROUGH", rr);
        check("rough base: NOT griefed", rr.classification != BaseReport.BaseClass.GRIEFED);
        check("rough base: not intact, goes to SUSPICIOUS", rr.classification == BaseReport.BaseClass.SUSPICIOUS);
        check("rough base: condition is Rough, not damaged", rr.condition == BaseReport.Condition.ROUGH);
        check("rough base: no crater/floating false positives", rr.structure.craters.isEmpty() && rr.structure.floating == 0);

        // one missing wall must not make a griefed base
        Observation oneWall = intactBase();
        fill(oneWall.snap, 31, 17, 22, 31, 19, 24, Cat.AIR);
        BaseReport rw = BaseScoringEngine.analyze(oneWall, p);
        check("one missing wall section: not griefed", rw.classification != BaseReport.BaseClass.GRIEFED);

        // suspicious blocks
        Observation crystal = intactBase();
        crystal.crystals.add(new int[]{OX + 24, OY + 25, OZ + 10});
        BaseReport rc = BaseScoringEngine.analyze(crystal, p);
        check("end crystal nearby: rejected (default)", rc.classification == BaseReport.BaseClass.REJECTED && rc.rejectionReasons.size() >= 1);
        Params soft = p.copy();
        soft.rejectEndCrystals = false;
        BaseReport rcs = BaseScoringEngine.analyze(crystal, soft);
        check("end crystal, soft mode: penalty applied and never INTACT", rcs.likelihood < ri.likelihood && rcs.classification == BaseReport.BaseClass.SUSPICIOUS);
        Params noPen = soft.copy();
        noPen.penalizeEndCrystals = false;
        check("end crystal far away: ignored", analyzeWithCrystalAt(p, 200).classification == BaseReport.BaseClass.INTACT);

        Observation anchor = intactBase();
        anchor.snap.set(10, 17, 10, Cat.ANCHOR);
        check("respawn anchor: rejected", BaseScoringEngine.analyze(anchor, p).classification == BaseReport.BaseClass.REJECTED);
        Params keepAnchor = p.copy();
        keepAnchor.rejectRespawnAnchors = false;
        BaseReport ra = BaseScoringEngine.analyze(anchor, keepAnchor);
        check("respawn anchor, hard rule off: very strong penalty applied, never INTACT", ra.classification != BaseReport.BaseClass.INTACT && ra.penaltyTotal >= 60 && ra.likelihood < ri.likelihood - 50);

        Observation obs = intactBase();
        obs.snap.set(5, 17, 40, Cat.OBSIDIAN);
        check("isolated obsidian: rejected", BaseScoringEngine.analyze(obs, p).classification == BaseReport.BaseClass.REJECTED);
        Observation portal = intactBase();
        fill(portal.snap, 4, 17, 40, 7, 21, 40, Cat.OBSIDIAN);
        fill(portal.snap, 5, 18, 40, 6, 20, 40, Cat.PORTAL);
        check("obsidian portal frame: not treated as isolated obsidian", BaseScoringEngine.analyze(portal, p).isolatedObsidian == 0);

        // unknown / unloaded handling
        Observation partial = intactBase();
        for (int z = 0; z < SZ; z++) for (int x = 0; x < SX; x++) if (x < 30) for (int y = 0; y < SY; y++) partial.snap.set(x, y, z, Cat.UNKNOWN);
        BaseReport rp = BaseScoringEngine.analyze(partial, p);
        check("low coverage: condition uncertain", rp.condition == BaseReport.Condition.UNCERTAIN);
        check("low coverage: classified conservatively as SUSPICIOUS (never intact/griefed/rejected)", rp.classification == BaseReport.BaseClass.SUSPICIOUS);

        // determinism
        BaseReport ri2 = BaseScoringEngine.analyze(intactBase(), p);
        check("deterministic: same input gives same scores", ri2.likelihood == ri.likelihood && ri2.damageScore == ri.damageScore
                && ri2.conditionScore == ri.conditionScore && ri2.classification == ri.classification);

        // strict / lenient modes change thresholds, not facts
        Params strict = p.copy();
        strict.mode = "STRICT";
        strict.intactMinLikelihood = 99;
        check("strict mode can demote an intact base", BaseScoringEngine.analyze(intactBase(), strict).classification == BaseReport.BaseClass.SUSPICIOUS);

        // ---- Discord payloads ----
        ri.id = "BF-TEST1";
        rg.id = "BF-TEST2";
        rr.id = "BF-TEST3";
        String iso = "2026-11-18T12:00:00Z";
        String[] payloads = {
                EmbedFactory.baseReport(ri, "donutsmp.net", "Base Finder", iso, true, true, true, null),
                EmbedFactory.baseReport(rr, "donutsmp.net", "Base Finder", iso, true, false, false, null),
                EmbedFactory.baseReport(rg, "donutsmp.net", "Base Finder", iso, true, true, true, "was Suspicious Base"),
                EmbedFactory.player("Steve123", 10, 64, -20, "minecraft:overworld", iso, "donutsmp.net", "Base Finder"),
                EmbedFactory.pearl(5, 80, 5, "minecraft:the_nether", null, iso, null, "Base Finder"),
                EmbedFactory.death("Me", 1, 2, 3, "minecraft:overworld", "Me was slain \"by\" Steve", iso, "x", "Base Finder"),
                EmbedFactory.test("Intact Bases", iso, "Base Finder")};
        String[] names = {"intact", "suspicious", "griefed", "player", "pearl", "death", "test"};
        for (int i = 0; i < payloads.length; i++) {
            Files.writeString(out.resolve("payload-" + names[i] + ".json"), payloads[i], StandardCharsets.UTF_8);
        }
        check("discord: griefed payload includes damage indicators + coords", payloads[2].contains("Damage indicators") && payloads[2].contains("X 1000"));
        check("discord: intact payload says contents unavailable", payloads[0].contains("Inventory contents unavailable"));
        check("discord: payloads never ping anyone", payloads[0].contains("\"allowed_mentions\":{\"parse\":[]}"));
        check("discord: player alert is TOP PRIORITY and not a base report", payloads[3].contains("TOP PRIORITY") && payloads[3].contains("Player detected"));
        check("discord: pearl alert purple + TOP PRIORITY", payloads[4].contains("TOP PRIORITY") && payloads[4].contains(String.valueOf(EmbedFactory.COLOR_PEARL)));
        check("discord: embed JSON escapes quotes", payloads[5].contains("\\\"by\\\""));

        // ---- webhook utility ----
        String good = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyzABCDEFGH0123456789";
        check("webhook: valid URL accepted", WebhookUtil.isValid(good));
        check("webhook: garbage rejected", !WebhookUtil.isValid("hello") && !WebhookUtil.isValid("http://discord.com/api/webhooks/1/2") && !WebhookUtil.isValid(""));
        check("webhook: mask hides the token", !WebhookUtil.mask(good).contains("abcdefgh") && !WebhookUtil.mask(good).contains("123456789012345678"));
        check("webhook: invalid reason is useful", WebhookUtil.invalidReason("ftp://x").contains("https://"));
        check("webhook: 404 explained", WebhookUtil.describeHttp(404).contains("deleted"));

        // ---- multipart ----
        Multipart mp = Multipart.build("{\"a\":1}", List.of(new Multipart.Attachment("plan.png", new byte[]{1, 2, 3})));
        String body = new String(mp.body, StandardCharsets.ISO_8859_1);
        check("multipart: has payload_json and file part", body.contains("name=\"payload_json\"") && body.contains("filename=\"plan.png\"")
                && mp.contentType.startsWith("multipart/form-data; boundary="));

        // ---- images ----
        long t0 = System.currentTimeMillis();
        byte[] plan = ImageRenderer.floorPlanPng(intact, ri);
        byte[] voxel = ImageRenderer.voxelPng(intact, ri);
        byte[] isoImg = ImageRenderer.isometricPng(intact, ri);
        byte[] gPlan = ImageRenderer.floorPlanPng(grief, rg);
        byte[] gVoxel = ImageRenderer.voxelPng(grief, rg);
        long ms = System.currentTimeMillis() - t0;
        check("images: all six rendered", plan != null && voxel != null && isoImg != null && gPlan != null && gVoxel != null);
        if (plan != null) {
            Files.write(out.resolve("intact-plan.png"), plan);
            Files.write(out.resolve("intact-voxel.png"), voxel);
            Files.write(out.resolve("intact-iso.png"), isoImg);
            Files.write(out.resolve("griefed-plan.png"), gPlan);
            Files.write(out.resolve("griefed-voxel.png"), gVoxel);
            check("images: small enough for Discord (each < 1.5 MB)", plan.length < 1_500_000 && voxel.length < 1_500_000 && isoImg.length < 1_500_000);
            System.out.println("      image sizes KB: plan " + plan.length / 1024 + ", voxel " + voxel.length / 1024 + ", iso " + isoImg.length / 1024 + "; render time " + ms + " ms");
        }

        // ---- analysis speed ----
        long t1 = System.nanoTime();
        for (int i = 0; i < 5; i++) BaseScoringEngine.analyze(griefedBase(), p);
        System.out.println("      analysis time (incl. scene build): " + (System.nanoTime() - t1) / 5_000_000 + " ms each");

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) {
            for (String f : failures) System.out.println("  FAILED: " + f);
            System.exit(1);
        }
    }

    static BaseReport analyzeWithCrystalAt(Params p, int dist) {
        Observation o = intactBase();
        o.crystals.add(new int[]{OX + 24 + dist, OY + 25, OZ + 24});
        return BaseScoringEngine.analyze(o, p);
    }

    static void dump(String label, BaseReport r) {
        System.out.printf("---- %s: class=%s outcome=%s likelihood=%.0f condition=%s(%.0f) damage=%.0f infra=%.0f coverage=%.0f%% storage=%d%n",
                label, r.classification, r.outcome, r.likelihood, r.condition.label, r.conditionScore, r.damageScore, r.infraScore,
                r.coverage * 100, r.storage.total);
        for (String s : r.classReasons) System.out.println("       " + s);
        StructureReport st = r.structure;
        System.out.printf("       evaluated=%d housed=%d sealed=%d roofMissing=%d breached=%d floating=%d debris=%d craters=%d rooms=%d%n",
                st.evaluated, st.housed, st.sealed, st.roofMissing, st.breached, st.floating, st.debris, st.craters.size(), st.sealedRooms);
    }
}
