package dev.basefinder;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.util.List;

/**
 * The single place that asks the client level for its entities. The accessor is looked up once by reflection (trying
 * the known names), so a rename can never break compilation; if nothing is found the mod keeps working without
 * entity features (end crystals, pearls, armor stands, item frames, animals) and logs it once.
 */
final class EntityView {
    private EntityView() {}

    private static Method accessor;
    private static boolean resolved, warned;
    private static final java.util.Map<Object, String> TYPE_IDS = new java.util.HashMap<>();

    @SuppressWarnings("unchecked")
    static Iterable<Entity> all(ClientLevel level) {
        if (!resolved) {
            resolved = true;
            for (String name : new String[]{"entitiesForRendering", "getAllEntities", "getEntities"}) {
                try {
                    accessor = level.getClass().getMethod(name);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // try the next name
                }
            }
        }
        if (accessor == null) {
            if (!warned) {
                warned = true;
                BaseFinderClient.LOG.warn("[BaseFinder] Could not find the client entity list; entity features (crystals, pearls, animals) are disabled");
            }
            return List.of();
        }
        try {
            Object r = accessor.invoke(level);
            if (r instanceof Iterable<?> it) return (Iterable<Entity>) it;
            if (r != null) { // some versions return an entity getter object with getAll()
                Object all = r.getClass().getMethod("getAll").invoke(r);
                if (all instanceof Iterable<?> it2) return (Iterable<Entity>) it2;
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                BaseFinderClient.LOG.warn("[BaseFinder] Reading client entities failed: {}", t.toString());
            }
        }
        return List.of();
    }

    static String typeId(Entity e) {
        Object type = e.getType();
        String id = TYPE_IDS.get(type);
        if (id == null) {
            id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            TYPE_IDS.put(type, id);
        }
        return id;
    }
}
