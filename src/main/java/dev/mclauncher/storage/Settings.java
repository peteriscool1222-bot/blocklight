package dev.mclauncher.storage;

import dev.mclauncher.core.Jsons;
import dev.mclauncher.core.LauncherPaths;

import java.io.IOException;
import java.nio.file.Files;

/** Persisted launcher settings. */
public final class Settings {
    public String javaPath = "";        // empty = auto-detect
    public int minMemoryMB = 512;
    public int maxMemoryMB = 4096;
    public String jvmArgs = "";
    public String wrapperCommand = "";  // prepended to the launch, e.g. "mangohud --dlsym"
    public boolean forceDgpu = false;   // run the game on the dedicated GPU
    public String gameDir = "";         // empty = default
    public int width = 1280;
    public int height = 720;
    public boolean fullscreen = false;
    public boolean keepOpen = true;
    public boolean showSnapshots = false;
    public boolean perfTweaks = true;   // Aikar's GC flags for smoother frame times
    public boolean gameMode = true;     // run under Feral GameMode (gamemoderun) when available
    public String defaultVersion = "";  // last selected version id

    public static Settings load() {
        try {
            if (Files.exists(LauncherPaths.SETTINGS)) {
                Settings s = Jsons.GSON.fromJson(Files.readString(LauncherPaths.SETTINGS), Settings.class);
                if (s != null) return s;
            }
        } catch (Exception ignored) {}
        return new Settings();
    }

    public void save() {
        try {
            Files.createDirectories(LauncherPaths.ROOT);
            Files.writeString(LauncherPaths.SETTINGS, Jsons.GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("Could not save settings: " + e.getMessage());
        }
    }
}
