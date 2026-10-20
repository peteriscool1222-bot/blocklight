package dev.mclauncher.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Thin JSON/form HTTP helper on top of java.net.http. */
public final class Http {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static final String USER_AGENT = "custom-mc-launcher/1.0";

    private Http() {}

    public static String get(String url) throws IOException, InterruptedException {
        // metadata path: gzip + tight timeout — these are all small-to-medium JSON/XML
        // payloads (manifest, fabric meta, forge maven) where a stalled response must
        // fail fast instead of holding a "Loading..." UI hostage for minutes
        return new String(fetch(url, null, true, Duration.ofSeconds(15), 2), StandardCharsets.UTF_8);
    }

    /** GET with up to 3 attempts and backoff — launchers run for a long time on flaky connections. */
    public static byte[] getBytes(String url) throws IOException, InterruptedException {
        return getBytes(url, null);
    }

    /**
     * GET with retries; when {@code onBytes} is provided the body is streamed and
     * the callback receives the cumulative byte count as chunks arrive, so progress
     * UIs keep moving during large downloads instead of freezing on one file.
     */
    public static byte[] getBytes(String url, java.util.function.LongConsumer onBytes)
            throws IOException, InterruptedException {
        // bulk downloads keep the old long timeout — big jars need it
        return fetch(url, onBytes, false, Duration.ofMinutes(10), 3);
    }

    private static byte[] fetch(String url, java.util.function.LongConsumer onBytes, boolean gzip,
                                Duration timeout, int attempts) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .timeout(timeout)
                        .GET();
                if (gzip) rb.header("Accept-Encoding", "gzip");

                if (onBytes == null && !gzip) {
                    HttpResponse<byte[]> res = CLIENT.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
                    if (res.statusCode() / 100 != 2)
                        throw new IOException("GET " + url + " -> HTTP " + res.statusCode());
                    byte[] body = res.body();
                    if (isGzip(res.headers().firstValue("Content-Encoding").orElse(""))) body = gunzip(body);
                    return body;
                }

                HttpResponse<java.io.InputStream> res = CLIENT.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() / 100 != 2) {
                    res.body().close();
                    throw new IOException("GET " + url + " -> HTTP " + res.statusCode());
                }
                java.io.InputStream decoded = res.body();
                if (isGzip(res.headers().firstValue("Content-Encoding").orElse("")))
                    decoded = new java.util.zip.GZIPInputStream(decoded);
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[64 * 1024];
                long total = 0;
                long deadline = System.nanoTime() + timeout.toNanos();
                try (java.io.InputStream in = decoded) {
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        buf.write(chunk, 0, n);
                        total += n;
                        if (onBytes != null) onBytes.accept(total);
                        // the request timeout only covers headers on streaming paths —
                        // enforce the same deadline on the body ourselves
                        if (System.nanoTime() > deadline)
                            throw new IOException("GET " + url + " -> body timed out after " + timeout);
                    }
                }
                return buf.toByteArray();
            } catch (IOException e) {
                last = e;
                if (isClientError(e)) throw e; // 4xx won't fix itself — fail fast, no retries
                if (attempt < attempts) Thread.sleep(750L * attempt);
            }
        }
        throw last;
    }

    private static boolean isGzip(String contentEncoding) {
        return contentEncoding.toLowerCase(java.util.Locale.ROOT).contains("gzip");
    }

    /** 4xx (except 408/429) responses are permanent — retrying only adds latency. */
    private static boolean isClientError(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        int i = m.lastIndexOf(" -> HTTP ");
        if (i < 0 || i + 9 + 3 > m.length()) return false;
        int code;
        try {
            code = Integer.parseInt(m.substring(i + 9, i + 12));
        } catch (NumberFormatException ex) {
            return false;
        }
        return code >= 400 && code < 500 && code != 408 && code != 429;
    }

    private static byte[] gunzip(byte[] body) throws IOException {
        try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(body))) {
            return in.readAllBytes();
        }
    }

    public static JsonObject getJson(String url) throws IOException, InterruptedException {
        return JsonParser.parseString(get(url)).getAsJsonObject();
    }

    /** GET that returns null instead of throwing when the server answers 400/404. */
    public static String getOptional(String url) throws IOException, InterruptedException {
        try {
            return get(url);
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.endsWith("HTTP 400") || m.endsWith("HTTP 404")) return null;
            throw e;
        }
    }

    public static JsonObject postJson(String url, String jsonBody) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2)
            throw new IOException("POST " + url + " -> HTTP " + res.statusCode() + ": " + res.body());
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    /** POST form-encoded body, returns raw body (caller inspects for oauth errors). */
    public static String postFormRaw(String url, Map<String, String> form) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                .build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        return res.body();
    }
}
