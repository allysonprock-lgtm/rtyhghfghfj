package dev.basefinder.core;

import java.util.regex.Pattern;

/** Webhook URL validation, masking (so credentials never reach logs or the screen) and error wording. */
public final class WebhookUtil {
    private WebhookUtil() {}

    private static final Pattern VALID = Pattern.compile(
            "^https://(?:(?:ptb\\.|canary\\.)?discord(?:app)?\\.com)/api(?:/v\\d+)?/webhooks/(\\d{15,25})/([A-Za-z0-9_\\-]{20,})(?:\\?.*)?$");

    public static boolean isValid(String url) {
        return url != null && VALID.matcher(url.trim()).matches();
    }

    public static String invalidReason(String url) {
        if (url == null || url.isBlank()) return "Not configured";
        String t = url.trim();
        if (!t.startsWith("https://")) return "Must start with https://";
        if (!t.contains("/api/webhooks/")) return "Not a Discord webhook (expected .../api/webhooks/ID/TOKEN)";
        return "Webhook ID or token looks malformed";
    }

    /** e.g. "discord.com/api/webhooks/\u2022\u2022\u202247821/\u2022\u2022\u2022\u2022". Shows only the last 4 digits of the id. */
    public static String mask(String url) {
        if (url == null || url.isBlank()) return "Not set";
        java.util.regex.Matcher m = VALID.matcher(url.trim());
        if (!m.matches()) return "Invalid URL (" + Math.min(url.trim().length(), 999) + " chars)";
        String id = m.group(1);
        return "discord.com/api/webhooks/\u2022\u2022\u2022" + id.substring(id.length() - 4) + "/\u2022\u2022\u2022\u2022\u2022\u2022";
    }

    public static String describeHttp(int code) {
        if (code >= 200 && code < 300) return "OK";
        return switch (code) {
            case 400 -> "Discord rejected the message (HTTP 400)";
            case 401, 403 -> "Webhook not authorised (HTTP " + code + ") - regenerate the webhook";
            case 404 -> "Webhook not found - it was deleted (HTTP 404)";
            case 413 -> "Attachment too large (HTTP 413)";
            case 429 -> "Rate limited by Discord (HTTP 429)";
            default -> code >= 500 ? "Discord server error (HTTP " + code + ")" : "HTTP " + code;
        };
    }
}
