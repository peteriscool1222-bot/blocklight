package dev.mclauncher.core;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;

/** All launcher-managed paths. */
public final class LauncherPaths {
    /**
     * Launcher data dir. One-time migration: when the new dir does not exist yet but
     * the pre-rename one ({@code ~/.mc-custom-launcher}) does, it is moved wholesale so
     * settings/accounts/caches/game files come along. On failure the legacy dir keeps
     * being served (nothing looks lost) and migration is retried on the next start.
     */
    public static final Path ROOT = resolveRoot();
    public static final Path SETTINGS = ROOT.resolve("settings.json");
    public static final Path ACCOUNTS = ROOT.resolve("accounts.json");

    private LauncherPaths() {}

    private static Path resolveRoot() {
        Path fresh = Path.of(System.getProperty("user.home"), ".blocklight");
        Path legacy = Path.of(System.getProperty("user.home"), ".mc-custom-launcher");
        boolean freshUsable = Files.exists(fresh)
                && !(Files.isDirectory(fresh) && isEmpty(fresh)); // tolerate a stray empty placeholder
        if (!freshUsable && Files.isDirectory(legacy)) {
            try {
                if (Files.exists(fresh)) Files.delete(fresh);
                Files.move(legacy, fresh); // same filesystem: a plain rename
                return fresh;
            } catch (java.io.IOException e) {
                return legacy; // in use right now — keep serving old data, retry next start
            }
        }
        return fresh;
    }

    private static boolean isEmpty(Path dir) {
        try (var s = Files.list(dir)) {
            return s.findFirst().isEmpty();
        } catch (java.io.IOException e) {
            return false;
        }
    }

    public static Path gameDir(String configured) {
        if (configured != null && !configured.isBlank())
            return Path.of(configured).toAbsolutePath().normalize();
        return ROOT.resolve("game");
    }

    public static Path versions(Path game) { return game.resolve("versions"); }
    public static Path libraries(Path game) { return game.resolve("libraries"); }
    public static Path assets(Path game) { return game.resolve("assets"); }
    public static Path natives(Path game, String versionId) { return game.resolve("bin").resolve(versionId).resolve("natives"); }

    /** Marker written by .mrpack import: versions/&lt;id&gt;/instance.json. */
    public static Path instanceMarker(Path game, String versionId) {
        return versions(game).resolve(versionId).resolve("instance.json");
    }

    /**
     * The isolated game folder of a modpack instance (mods/config/saves), or null
     * when the version is a regular install that shares the main game directory.
     */
    public static Path instanceDir(Path game, String versionId) {
        Path marker = instanceMarker(game, versionId);
        if (!Files.exists(marker)) return null;
        try {
            JsonObject o = Jsons.parseFile(marker);
            String dir = Jsons.str(o, "dir", null);
            if (dir != null && !dir.isBlank()) return game.resolve(dir).normalize();
        } catch (Exception ignored) {
            // unreadable marker — fall through to the conventional location
        }
        return game.resolve("instances").resolve(versionId);
    }
}
