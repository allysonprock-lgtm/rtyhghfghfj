package dev.basefinder;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseFinderClient implements ClientModInitializer {
    public static final String MOD_ID = "basefinder";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static volatile Config config;
    public static DetectionStore store;
    public static HistoryStore history;
    public static DiscordManager discord;
    public static DetectionPipeline pipeline;
    public static Scanner scanner;
    public static final LocalPlayerExclusion me = new LocalPlayerExclusion();
    public static final PlayerDetection players = new PlayerDetection();
    public static final EnderPearlDetection pearls = new EnderPearlDetection();
    public static final DeathDetection deaths = new DeathDetection();

    /** Shows up in Options > Controls > Key Binds under "Base Finder". */
    public static KeyMapping openKey;

    private static boolean inWorld;
    private static boolean noticeShown;
    private static long tick;

    @Override
    public void onInitializeClient() {
        config = Config.load();
        store = DetectionStore.load();
        history = HistoryStore.load();
        discord = new DiscordManager();
        pipeline = new DetectionPipeline();
        scanner = new Scanner();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.basefinder.open_menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                category));

        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> scanner.onChunkLoad(level, chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> scanner.onChunkUnload(level, chunk));
        ClientTickEvents.END_CLIENT_TICK.register(BaseFinderClient::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            history.flushIfDue(true);
            discord.shutdown();
            pipeline.shutdown();
        });

        LOG.info("[BaseFinder] Loaded. Config: {}", Config.file());
    }

    /** The one place that drives every subsystem, once per client tick. */
    private static void tick(Minecraft mc) {
        tick++;
        Config cfg = config;

        if (mc.level == null) {
            if (inWorld) { // left the world / disconnected: drop everything tied to it
                inWorld = false;
                scanner.reset();
                pipeline.reset();
                players.reset();
                pearls.reset();
                deaths.reset();
                history.flushIfDue(true);
            }
            return;
        }
        var level = mc.level;
        me.update(mc);

        if (!inWorld) {
            inWorld = true;
            if (!noticeShown && !cfg.migrationNotice.isBlank() && !cfg.anyNewWebhookConfigured() && mc.player != null) {
                noticeShown = true;
                mc.player.sendSystemMessage(Component.literal("[BaseFinder] " + cfg.migrationNotice).withStyle(ChatFormatting.YELLOW));
            }
            if (cfg.sendTestOnJoin) Alerts.testAlert(mc);
        }

        if (tick % 100 == 0 && cfg.changedOnDisk()) {
            Config reloaded = Config.reload(cfg);
            if (reloaded != cfg) LOG.info("[BaseFinder] Config reloaded");
            config = reloaded;
            cfg = reloaded;
        }

        // Keybind from Options > Controls. consumeClick() only fires while no screen is open.
        while (openKey != null && openKey.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new BaseFinderScreen(null));
        }

        scanner.tick(mc, level, cfg);
        pipeline.tick(mc, level, cfg, scanner);
        players.tick(mc, level, cfg, me);
        pearls.tick(mc, level, cfg, me);
        deaths.tick(mc, cfg, me);
        history.flushIfDue(false);
    }
}
