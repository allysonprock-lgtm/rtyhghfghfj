package dev.basefinder;

import dev.basefinder.core.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional generic JSON endpoint (apiEndpointUrl). Discord is handled by {@link DiscordManager}.
 * The payload keeps the v1.x field names and adds the v2 analysis fields.
 */
final class Notifier {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "basefinder-api");
        t.setDaemon(true);
        return t;
    });

    private Notifier() {}

    static void sendApi(Config cfg, DetectionStore.Detection d, String event) {
        String api = cfg.apiEndpointUrl == null ? "" : cfg.apiEndpointUrl.trim();
        if (api.isEmpty() || !api.startsWith("https://")) return;
        Map<String, Object> o = Json.obj();
        o.put("event", event);
        if (cfg.includeServerAddress) o.put("server", d.server);
        o.put("id", d.id);
        o.put("classification", d.classification);
        o.put("dimension", d.dimension);
        o.put("x", d.x);
        o.put("y", d.y);
        o.put("z", d.z);
        o.put("chunkX", d.chunkX);
        o.put("chunkZ", d.chunkZ);
        o.put("storageCount", d.totalStorage);
        o.put("likelihood", d.likelihood);
        o.put("condition", d.condition);
        o.put("conditionScore", d.conditionScore);
        o.put("damageScore", d.damageScore);
        o.put("infraScore", d.infraScore);
        o.put("foundAt", d.foundAt);
        String body = Json.write(o);
        String auth = cfg.apiAuthHeader;
        EXEC.execute(() -> {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api)).timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
                if (auth != null && !auth.isBlank()) b.header("Authorization", auth);
                HttpResponse<Void> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.discarding());
                if (res.statusCode() >= 300) BaseFinderClient.LOG.warn("[BaseFinder] API endpoint returned HTTP {}", res.statusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                BaseFinderClient.LOG.warn("[BaseFinder] API endpoint request failed: {}", e.getClass().getSimpleName());
            }
        });
    }
}
