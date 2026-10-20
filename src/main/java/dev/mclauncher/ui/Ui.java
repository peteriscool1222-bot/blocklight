package dev.mclauncher.ui;

import dev.mclauncher.core.LauncherPaths;
import dev.mclauncher.core.VersionChain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Helpers shared by views. */
public final class Ui {
    private Ui() {}

    /** Locally installed, launchable version ids (json + client jar present), newest first. */
    public static List<String> installedVersions(Path game) {
        List<String> out = new ArrayList<>();
        Path versionsDir = LauncherPaths.versions(game);
        if (!Files.isDirectory(versionsDir)) return out;
        try (var dirs = Files.list(versionsDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                String id = dir.getFileName().toString();
                if (!Files.exists(dir.resolve(id + ".json"))) continue;
                try {
                    VersionChain chain = VersionChain.resolve(id, versionsDir);
                    if (Files.exists(LauncherCoreJars.jar(chain, game))) out.add(id);
                } catch (Exception ignored) {
                    // broken/incomplete version
                }
            }
        } catch (IOException ignored) {}
        out.sort(Comparator.comparing((String s) -> s.toLowerCase()).reversed());
        return out;
    }

    /** Open a folder in the system file manager (creates it first if asked). */
    public static void openFolder(Path p, boolean create) {
        if (create) {
            try { Files.createDirectories(p); } catch (Exception ignored) {}
        }
        String[] cmd = switch (dev.mclauncher.core.Os.NAME) {
            case "windows" -> new String[]{"explorer", p.toString()};
            case "osx" -> new String[]{"open", p.toString()};
            default -> new String[]{"xdg-open", p.toString()};
        };
        try {
            new ProcessBuilder(cmd).start();
        } catch (Exception ignored) {}
    }

    /** Bridge so ui package can call the package-private helper without exposing internals. */
    public static final class LauncherCoreJars {
        private LauncherCoreJars() {}
        public static Path jar(VersionChain chain, Path game) {
            return LauncherPaths.versions(game).resolve(chain.jarId()).resolve(chain.jarId() + ".jar");
        }
    }
}
