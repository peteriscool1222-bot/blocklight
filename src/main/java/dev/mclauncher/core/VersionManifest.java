package dev.mclauncher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Mojang version manifest (piston-meta) and local version discovery. */
public final class VersionManifest {
    public static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    public record VersionEntry(String id, String type, String url, String releaseTime) {}

    private static volatile JsonObject manifest;
    private static volatile List<VersionEntry> entries;

    private static java.nio.file.Path cacheFile() {
        return LauncherPaths.ROOT.resolve("cache").resolve("version_manifest.json");
    }

    private VersionManifest() {}

    public static JsonObject raw() throws IOException, InterruptedException {
        JsonObject m = manifest;
        if (m == null) {
            synchronized (VersionManifest.class) {
                if (manifest == null) {
                    // prefer a recent on-disk copy so the UI opens instantly
                    java.nio.file.Path cache = cacheFile();
                    boolean fresh = false;
                    try {
                        fresh = Files.exists(cache)
                                && Files.getLastModifiedTime(cache).toMillis()
                                   > System.currentTimeMillis() - 3_600_000L;
                    } catch (IOException ignored) {}
                    if (fresh) {
                        try {
                            manifest = Jsons.parseFile(cache);
                        } catch (Exception ignored) {
                            manifest = null;
                        }
                    }
                    if (manifest == null) {
                        JsonObject fetched = Http.getJson(MANIFEST_URL);
                        try {
                            Files.createDirectories(cache.getParent());
                            Files.writeString(cache, fetched.toString());
                        } catch (IOException ignored) {}
                        manifest = fetched;
                    }
                }
                m = manifest;
            }
        }
        return m;
    }

    public static List<VersionEntry> versions() throws IOException, InterruptedException {
        List<VersionEntry> e = entries;
        if (e == null) {
            synchronized (VersionManifest.class) {
                if (entries == null) {
                    List<VersionEntry> list = new ArrayList<>();
                    JsonArray arr = raw().getAsJsonArray("versions");
                    for (var el : arr) {
                        JsonObject o = el.getAsJsonObject();
                        list.add(new VersionEntry(
                                o.get("id").getAsString(),
                                o.get("type").getAsString(),
                                o.get("url").getAsString(),
                                o.get("releaseTime").getAsString()));
                    }
                    entries = list;
                }
                e = entries;
            }
        }
        return e;
    }

    public static VersionEntry find(String id) throws IOException, InterruptedException {
        return versions().stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
    }

    public static JsonObject downloadVersionJson(VersionEntry entry, Path versionsDir) throws IOException, InterruptedException {
        Path file = versionsDir.resolve(entry.id()).resolve(entry.id() + ".json");
        if (Files.exists(file)) {
            return Jsons.parseFile(file);
        }
        JsonObject json = Http.getJson(entry.url());
        Files.createDirectories(file.getParent());
        Files.writeString(file, json.toString());
        return json;
    }

    /** Every version id known locally plus in the manifest, sorted newest-first. */
    public static List<String> allIds(boolean includeSnapshots, Path versionsDir) throws IOException, InterruptedException {
        List<String> ids = new ArrayList<>();
        for (VersionEntry v : versions()) {
            if (includeSnapshots || v.type().equals("release")) ids.add(v.id());
        }
        if (Files.isDirectory(versionsDir)) {
            try (var s = Files.list(versionsDir)) {
                for (Path p : s.filter(Files::isDirectory).toList()) {
                    String name = p.getFileName().toString();
                    if (!ids.contains(name) && Files.exists(p.resolve(name + ".json"))) ids.add(name);
                }
            }
        }
        ids.sort(Comparator.comparing((String id) -> id.toLowerCase()).reversed());
        return ids;
    }
}
