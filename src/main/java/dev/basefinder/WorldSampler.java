package dev.basefinder;

import dev.basefinder.core.BlockKinds;
import dev.basefinder.core.Cat;
import dev.basefinder.core.Snapshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads blocks the client has ALREADY received into a {@link Snapshot}. It never requests chunks, never interacts
 * with anything and runs on the game thread only. Columns in chunks that are not loaded stay UNKNOWN.
 */
final class WorldSampler {
    private WorldSampler() {}

    private static final Map<Block, Cat> CACHE = new HashMap<>();

    static Cat catOf(BlockState state) {
        if (state.isAir()) return Cat.AIR;
        Block b = state.getBlock();
        Cat c = CACHE.get(b);
        if (c == null) {
            c = BlockKinds.classify(BuiltInRegistries.BLOCK.getKey(b).toString());
            CACHE.put(b, c);
        }
        return c;
    }

    static Snapshot capture(ClientLevel level, String dim, int cx, int cy, int cz, int radius, int below, int above) {
        int ox = cx - radius, oy = cy - below, oz = cz - radius;
        int sx = radius * 2 + 1, sy = below + above + 1, sz = radius * 2 + 1;
        Snapshot snap = new Snapshot(dim, ox, oy, oz, sx, sy, sz);

        Map<Long, Boolean> loaded = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                int wx = ox + x, wz = oz + z;
                long ck = (((long) (wx >> 4)) << 32) | ((wz >> 4) & 0xffffffffL);
                Boolean ok = loaded.get(ck);
                if (ok == null) {
                    LevelChunk chunk = level.getChunk(wx >> 4, wz >> 4);
                    ok = !(chunk instanceof EmptyLevelChunk);
                    loaded.put(ck, ok);
                }
                if (!ok) continue; // stays UNKNOWN
                for (int y = 0; y < sy; y++) {
                    pos.set(wx, oy + y, wz);
                    snap.set(x, y, z, catOf(level.getBlockState(pos)));
                }
            }
        }
        return snap;
    }
}
