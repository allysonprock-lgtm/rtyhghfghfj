package dev.basefinder;

import dev.basefinder.core.EmbedFactory;
import dev.basefinder.core.Multipart;
import dev.basefinder.core.WebhookUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The only place that talks to Discord. Every message goes through a background worker (never the game thread),
 * with rate-limit handling, retries, size checks and per-destination status. Webhook URLs are never logged.
 *
 * Routing rules (centralised in {@link #resolve}):
 *  - Base channels (intact / suspicious / griefed) only ever use their own webhook. No fallback, no copies.
 *  - Player / ender pearl / death alerts use their own webhook, or the optional priority fallback webhook when
 *    their own is not configured. They can never reach a base channel.
 */
final class DiscordManager {
    enum State { UNCONFIGURED, DISABLED, INVALID, READY, SENDING, OK, ERROR }

    record Status(State state, String message) {}

    private record Result(boolean ok, String message) {}

    /** Test hook: called for every message that was accepted for delivery (channel, payload). */
    static volatile java.util.function.BiConsumer<Channel, String> observer;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<Channel, Result> last = new EnumMap<>(Channel.class);
    private final Map<Channel, AtomicInteger> inFlight = new EnumMap<>(Channel.class);
    private final Map<Channel, Long> nextAllowed = new ConcurrentHashMap<>();
    private final Set<Channel> warned = new HashSet<>();

    private final ThreadPoolExecutor normal = pool("basefinder-discord");
    private final ThreadPoolExecutor priority = pool("basefinder-discord-priority");

    DiscordManager() {
        for (Channel c : Channel.values()) inFlight.put(c, new AtomicInteger());
    }

    private static ThreadPoolExecutor pool(String name) {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, name);
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.DiscardOldestPolicy());
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    void shutdown() {
        normal.shutdownNow();
        priority.shutdownNow();
    }

    // ------------------------------------------------------------------ status (used by the menu)

    /** Current configuration/connection status of one destination. Cheap; safe to call every frame. */
    synchronized Status status(Channel ch) {
        Config.Webhook w = BaseFinderClient.config.webhook(ch);
        if (w.url == null || w.url.isBlank()) {
            if (!ch.base && ch != Channel.FALLBACK && fallbackUsable()) return new Status(State.READY, "Not set - using the fallback webhook");
            return new Status(State.UNCONFIGURED, "Not configured");
        }
        if (!w.enabled) return new Status(State.DISABLED, "Disabled");
        if (!WebhookUtil.isValid(w.url)) return new Status(State.INVALID, WebhookUtil.invalidReason(w.url));
        if (inFlight.get(ch).get() > 0) return new Status(State.SENDING, "Sending...");
        Result r = last.get(ch);
        if (r == null) return new Status(State.READY, "Configured - not tested yet");
        return new Status(r.ok() ? State.OK : State.ERROR, r.message());
    }

    boolean busy(Channel ch) {
        return inFlight.get(ch).get() > 0;
    }

    private boolean fallbackUsable() {
        Config.Webhook f = BaseFinderClient.config.priorityFallbackWebhook;
        return f.enabled && WebhookUtil.isValid(f.url);
    }

    // ------------------------------------------------------------------ routing

    /** Returns {destination whose webhook is used}, or null if this alert cannot be delivered. */
    private Channel resolve(Channel ch) {
        Config cfg = BaseFinderClient.config;
        Config.Webhook w = cfg.webhook(ch);
        boolean configured = w.url != null && !w.url.isBlank();
        if (configured) {
            return (w.enabled && WebhookUtil.isValid(w.url)) ? ch : null; // explicit setting: never silently re-route
        }
        if (!ch.base && ch != Channel.FALLBACK && fallbackUsable()) return Channel.FALLBACK;
        return null;
    }

    // ------------------------------------------------------------------ public API

    /** Sends a prepared payload to a destination. Returns false (and keeps everything local) if it is not deliverable. */
    boolean send(Channel ch, String payloadJson, List<Multipart.Attachment> files) {
        Channel dest = resolve(ch);
        if (dest == null) {
            synchronized (warned) {
                if (warned.add(ch)) {
                    BaseFinderClient.LOG.info("[BaseFinder] Discord destination '{}' is not configured/enabled; alerts for it stay local", ch.label);
                }
            }
            return false;
        }
        java.util.function.BiConsumer<Channel, String> obs = observer;
        if (obs != null) obs.accept(ch, payloadJson);
        String url = BaseFinderClient.config.webhook(dest).url;
        enqueue(dest, ch, url, payloadJson, files == null ? List.of() : files);
        return true;
    }

    /** Sends a test message to exactly this destination (no fallback). */
    void test(Channel ch) {
        Config cfg = BaseFinderClient.config;
        Config.Webhook w = cfg.webhook(ch);
        String url = w.url;
        if (url == null || url.isBlank() || !w.enabled || !WebhookUtil.isValid(url)) return; // status() already explains why
        String json = EmbedFactory.test(ch.label, Instant.now().toString(), cfg.discordUsername);
        enqueue(ch, ch, url, json, List.of());
    }

    private void enqueue(Channel statusCh, Channel logCh, String url, String json, List<Multipart.Attachment> files) {
        inFlight.get(statusCh).incrementAndGet();
        Runnable job = () -> {
            try {
                Result r = post(statusCh, url, json, files);
                synchronized (this) {
                    last.put(statusCh, r);
                }
                if (!r.ok()) BaseFinderClient.LOG.warn("[BaseFinder] Discord '{}': {}", logCh.label, r.message());
            } catch (Throwable t) {
                synchronized (this) {
                    last.put(statusCh, new Result(false, "Unexpected error: " + t.getClass().getSimpleName()));
                }
            } finally {
                inFlight.get(statusCh).decrementAndGet();
            }
        };
        try {
            (logCh.base ? normal : priority).execute(job);
        } catch (RejectedExecutionException e) {
            inFlight.get(statusCh).decrementAndGet();
        }
    }

    // ------------------------------------------------------------------ worker

    private Result post(Channel ch, String url, String json, List<Multipart.Attachment> files) throws InterruptedException {
        boolean droppedFiles = false;
        long total = 0;
        for (Multipart.Attachment a : files) total += a.data().length;
        if (total > 7_500_000L) { // Discord's default limit is 8 MB (10 MB boosted)
            files = List.of();
            droppedFiles = true;
        }
        String contentType;
        byte[] body;
        if (files.isEmpty()) {
            contentType = "application/json";
            body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else {
            Multipart mp = Multipart.build(json, files);
            contentType = mp.contentType;
            body = mp.body;
        }

        String lastError = "Failed";
        for (int attempt = 1; attempt <= 4; attempt++) {
            Long wait = nextAllowed.get(ch);
            if (wait != null && wait > System.currentTimeMillis()) {
                Thread.sleep(Math.min(30_000L, wait - System.currentTimeMillis()));
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                res.headers().firstValue("X-RateLimit-Remaining").ifPresent(rem -> {
                    if ("0".equals(rem.trim())) {
                        res.headers().firstValue("X-RateLimit-Reset-After").ifPresent(sec -> {
                            try {
                                nextAllowed.put(ch, System.currentTimeMillis() + (long) (Double.parseDouble(sec) * 1000) + 100);
                            } catch (NumberFormatException ignored) {
                            }
                        });
                    }
                });
                if (code >= 200 && code < 300) {
                    return new Result(true, droppedFiles ? "OK (images dropped: too large)" : "Last message delivered");
                }
                lastError = WebhookUtil.describeHttp(code);
                if (code == 429) {
                    double secs = 2.0 * attempt;
                    try {
                        secs = Double.parseDouble(res.headers().firstValue("Retry-After").orElse(String.valueOf(secs)));
                    } catch (NumberFormatException ignored) {
                    }
                    Thread.sleep((long) (Math.min(30.0, secs) * 1000) + 250);
                    continue;
                }
                if (code >= 500) {
                    Thread.sleep(2000L * attempt);
                    continue;
                }
                return new Result(false, lastError); // 4xx: retrying will not help
            } catch (InterruptedException e) {
                throw e;
            } catch (java.io.IOException e) {
                lastError = "Network error (" + e.getClass().getSimpleName() + ")";
                Thread.sleep(2000L * attempt);
            } catch (IllegalArgumentException e) {
                return new Result(false, "Invalid webhook URL");
            }
        }
        return new Result(false, lastError + " - gave up after 4 attempts");
    }
}
