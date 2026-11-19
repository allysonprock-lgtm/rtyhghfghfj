package dev.basefinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Local history of alerts and notable events (players, ender pearls, deaths, base classifications). */
final class HistoryStore {
    static final class Event {
        String type;      // PLAYER, PEARL, DEATH, BASE_INTACT, BASE_SUSPICIOUS, BASE_GRIEFED, BASE_REJECTED, RECLASSIFIED
        String time;
        String dimension;
        boolean hasPos;
        int x, y, z;
        String text;
        String detail;
    }

    private static Path file() {
        return Config.dir().resolve("events.json");
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<Event> events = new ArrayList<>();
    private boolean dirty;
    private long lastWrite;

    static HistoryStore load() {
        HistoryStore h = new HistoryStore();
        try {
            if (Files.exists(file())) {
                List<Event> read = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), new TypeToken<List<Event>>() {}.getType());
                if (read != null) for (Event e : read) if (e != null && e.type != null) h.events.add(e);
            }
        } catch (Exception e) {
            BaseFinderClient.LOG.warn("[BaseFinder] Could not read event history: {}", e.toString());
        }
        return h;
    }

    synchronized void add(String type, String dim, Integer x, Integer y, Integer z, String text, String detail, int max) {
        Event e = new Event();
        e.type = type;
        e.time = Instant.now().toString();
        e.dimension = dim;
        if (x != null && y != null && z != null) {
            e.hasPos = true;
            e.x = x;
            e.y = y;
            e.z = z;
        }
        e.text = text;
        e.detail = detail;
        events.add(e);
        while (events.size() > max) events.remove(0);
        dirty = true;
        flushIfDue(false);
    }

    /** Writes at most every 5 seconds unless forced (death/exit), so frequent events never hammer the disk. */
    synchronized void flushIfDue(boolean force) {
        if (!dirty) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastWrite < 5000) return;
        lastWrite = now;
        dirty = false;
        try {
            Files.createDirectories(Config.dir());
            Files.writeString(file(), GSON.toJson(events), StandardCharsets.UTF_8);
        } catch (Exception e) {
            BaseFinderClient.LOG.warn("[BaseFinder] Could not save event history: {}", e.toString());
        }
    }

    synchronized List<Event> recent(int n) {
        List<Event> out = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0 && out.size() < n; i--) out.add(events.get(i));
        return out;
    }

    synchronized List<Event> ofType(String type, int n) {
        List<Event> out = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0 && out.size() < n; i--) if (type.equals(events.get(i).type)) out.add(events.get(i));
        return out;
    }

    synchronized int count(String type) {
        int c = 0;
        for (Event e : events) if (type.equals(e.type)) c++;
        return c;
    }
}
