package dev.mclauncher.install;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mclauncher.core.Http;
import dev.mclauncher.core.Jsons;
import dev.mclauncher.core.LauncherPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Installs Fabric loader profiles using the official Fabric meta API. */
public final class FabricInstaller {
    public static final String META = "https://meta.fabricmc.net/v2";
    private static final java.util.Map<String, List<Loader>> LOADER_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_TTL = 3_600_000L; // 1h, same as the forge maven cache

    private FabricInstaller() {}

    public record Loader(String version, boolean stable) {}

    private static Path cacheFile(String gameVersion) {
        String safe = gameVersion.replaceAll("[^A-Za-z0-9._-]", "_");
        return LauncherPaths.ROOT.resolve("cache").resolve("fabric_loaders_" + safe + ".json");
    }

    /** Loader versions available for a game version (disk + session cached). */
    public static List<Loader> loaders(String gameVersion) throws IOException, InterruptedException {
        List<Loader> cached = LOADER_CACHE.get(gameVersion);
        if (cached != null) return cached;
        // recent on-disk copy first: the meta endpoint's payload is huge and the service
        // is occasionally slow — a disk hit makes repeat loads instant
        Path cache = cacheFile(gameVersion);
        try {
            if (Files.exists(cache) && Files.getLastModifiedTime(cache).toMillis()
                    > System.currentTimeMillis() - CACHE_TTL) {
                List<Loader> fromDisk = new ArrayList<>();
                for (var el : com.google.gson.JsonParser.parseString(Files.readString(cache)).getAsJsonArray()) {
                    JsonObject o = el.getAsJsonObject();
                    fromDisk.add(new Loader(o.get("version").getAsString(), Jsons.bool(o, "stable", false)));
                }
                fromDisk.sort((a, b) -> Boolean.compare(b.stable(), a.stable()));
                List<Loader> frozen = List.copyOf(fromDisk);
                LOADER_CACHE.put(gameVersion, frozen);
                return frozen;
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable/stale cache — fall through to the network
        }
        // this endpoint returns a top-level JSON array with full launcher metadata — it is huge,
        // so we keep only the newest entries and cache the (compact) result on disk.
        // versions fabric doesn't support answer HTTP 400; that's not an error, just "no fabric"
        String body = Http.getOptional(META + "/versions/loader/" + gameVersion);
        if (body == null) {
            LOADER_CACHE.put(gameVersion, List.of());
            writeCache(cache, List.of());
            return List.of();
        }
        JsonArray arr = com.google.gson.JsonParser.parseString(body).getAsJsonArray();
        List<Loader> out = new ArrayList<>();
        for (var el : arr) {
            if (out.size() >= 20) break; // newest first in the api; 20 is plenty for the dropdown
            JsonObject o = el.getAsJsonObject().getAsJsonObject("loader");
            out.add(new Loader(o.get("version").getAsString(), Jsons.bool(o, "stable", false)));
        }
        out.sort((a, b) -> Boolean.compare(b.stable(), a.stable()));
        List<Loader> frozen = List.copyOf(out);
        LOADER_CACHE.put(gameVersion, frozen);
        writeCache(cache, frozen);
        return frozen;
    }

    /** Stores just {version, stable} — a few hundred bytes versus the huge raw payload. */
    private static void writeCache(Path cache, List<Loader> list) {
        try {
            Files.createDirectories(cache.getParent());
            JsonArray arr = new JsonArray();
            for (Loader l : list) {
                JsonObject o = new JsonObject();
                o.addProperty("version", l.version());
                o.addProperty("stable", l.stable());
                arr.add(o);
            }
            Files.writeString(cache, Jsons.GSON.toJson(arr));
        } catch (IOException ignored) {}
    }

    /**
     * Downloads the profile json for game+loader and writes it into the versions dir.
     * Returns the profile id (the version to launch afterwards).
     */
    public static String install(String gameVersion, String loaderVersion, Path versionsDir)
            throws IOException, InterruptedException {
        String url = META + "/versions/loader/" + gameVersion + "/" + loaderVersion + "/profile/json";
        JsonObject profile = Http.getJson(url);
        String id = profile.get("id").getAsString();
        Path dir = versionsDir.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), Jsons.GSON.toJson(profile));
        return id;
    }
}
