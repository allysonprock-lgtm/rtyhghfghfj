package dev.basefinder.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Builds a Discord multipart/form-data body (payload_json + files[n]). */
public final class Multipart {
    public record Attachment(String name, byte[] data) {}

    public final String contentType;
    public final byte[] body;

    private Multipart(String contentType, byte[] body) {
        this.contentType = contentType;
        this.body = body;
    }

    public static Multipart build(String payloadJson, List<Attachment> files) {
        String boundary = "----BaseFinder" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                    + "Content-Type: application/json\r\n\r\n");
            out.write(payloadJson.getBytes(StandardCharsets.UTF_8));
            write(out, "\r\n");
            for (int i = 0; i < files.size(); i++) {
                Attachment a = files.get(i);
                write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[" + i + "]\"; filename=\""
                        + a.name() + "\"\r\nContent-Type: image/png\r\n\r\n");
                out.write(a.data());
                write(out, "\r\n");
            }
            write(out, "--" + boundary + "--\r\n");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return new Multipart("multipart/form-data; boundary=" + boundary, out.toByteArray());
    }

    private static void write(ByteArrayOutputStream out, String s) throws java.io.IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }
}
