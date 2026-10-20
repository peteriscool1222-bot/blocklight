package dev.mclauncher.install;

import com.google.gson.JsonObject;
import dev.mclauncher.core.Http;
import dev.mclauncher.core.Jsons;
import dev.mclauncher.core.LauncherPaths;
import dev.mclauncher.storage.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Installs Forge by downloading the official installer jar for a Minecraft version
 * and running it with --installClient pointed at the launcher's own versions directory.
 * The installer generates the version json (with inheritsFrom), which the launcher
 * then picks up like any other version.
 */
public final class ForgeInstaller {
    public static final String MAVEN =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";

    private ForgeInstaller() {}

    private static final java.util.Map<String, List<String>> BUILD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static java.nio.file.Path cacheFile() {
        return LauncherPaths.ROOT.resolve("cache").resolve("forge_maven.xml");
    }

    /** Forge builds (full "mcVersion-loaderVersion" strings) for a Minecraft version, newest first (cached). */
    public static List<String> builds(String mcVersion) throws IOException, InterruptedException {
        List<String> cached = BUILD_CACHE.get(mcVersion);
        if (cached != null) return cached;
        // the forge maven metadata is large and can be slow; prefer a recent on-disk copy
        String xml = null;
        java.nio.file.Path cache = cacheFile();
        try {
            if (java.nio.file.Files.exists(cache)
                    && java.nio.file.Files.getLastModifiedTime(cache).toMillis()
                       > System.currentTimeMillis() - 3_600_000L) {
                xml = java.nio.file.Files.readString(cache);
            }
        } catch (IOException ignored) {}
        if (xml == null) {
            xml = Http.get(MAVEN);
            try {
                java.nio.file.Files.createDirectories(cache.getParent());
                java.nio.file.Files.writeString(cache, xml);
            } catch (IOException ignored) {}
        }
        List<String> out = new ArrayList<>();
        for (String line : xml.split("\n")) {
            line = line.trim();
            if (line.startsWith("<version>") && line.endsWith("</version>")) {
                String v = line.substring("<version>".length(), line.length() - "</version>".length());
                if (v.startsWith(mcVersion + "-")) out.add(v);
            }
        }
        out.sort((a, b) -> compareBuilds(b, a, mcVersion));
        if (out.size() > 40) out = new ArrayList<>(out.subList(0, 40)); // newest 40 is plenty
        BUILD_CACHE.put(mcVersion, List.copyOf(out));
        return out;
    }

    /** Numeric-aware build comparison: 66.0.10 sorts above 66.0.9. */
    private static int compareBuilds(String a, String b, String mcVersion) {
        String sa = a.substring(mcVersion.length() + 1);
        String sb = b.substring(mcVersion.length() + 1);
        String[] pa = sa.split("[.\\-]");
        String[] pb = sb.split("[.\\-]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            String x = i < pa.length ? pa[i] : "0";
            String y = i < pb.length ? pb[i] : "0";
            int r;
            try {
                r = Integer.compare(Integer.parseInt(x), Integer.parseInt(y));
            } catch (NumberFormatException e) {
                r = x.compareTo(y);
            }
            if (r != 0) return r;
        }
        return sa.compareTo(sb);
    }

    /**
     * Runs the official installer. Installs into {@code gameDir} (the launcher's game
     * directory, which must contain the versions folder). The installer requires a
     * launcher_profiles.json to exist there, so a minimal one is created if missing.
     * Returns the id of the generated version (mcVersion-Forge...).
     */
    public static String install(String forgeBuild, Path gameDir, Path workDir,
                                 Settings settings, Consumer<String> log)
            throws IOException, InterruptedException {
        Path installDir = gameDir.resolve("versions");
        String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                + forgeBuild + "/forge-" + forgeBuild + "-installer.jar";
        Path installer = workDir.resolve("forge-installer-" + forgeBuild + ".jar");
        Files.createDirectories(workDir);
        if (!Files.exists(installer)) {
            log.accept("Downloading Forge installer...");
            Files.write(installer, Http.getBytes(url));
        }

        // the official installer refuses to run without a launcher profile json
        Files.createDirectories(gameDir);
        Path profiles = gameDir.resolve("launcher_profiles.json");
        if (!Files.exists(profiles)) {
            Files.writeString(profiles, "{\n  \"profiles\": {},\n  \"version\": 3\n}");
        }

        java.util.List<String> before = scanVersions(installDir);
        log.accept("Running Forge installer (this can take a minute)...");
        String java = settings.javaPath == null || settings.javaPath.isBlank()
                ? Path.of(System.getProperty("java.home"), "bin", "java").toString()
                : settings.javaPath;

        ProcessBuilder pb = new ProcessBuilder(java, "-jar", installer.toAbsolutePath().toString(),
                "--installClient", gameDir.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        pb.directory(workDir.toFile());
        Process p = pb.start();
        p.getOutputStream().close();
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) log.accept("[forge] " + line);
        }
        if (!p.waitFor(5, TimeUnit.MINUTES)) { p.destroyForcibly(); throw new IOException("Forge installer timed out."); }
        if (p.exitValue() != 0) throw new IOException("Forge installer exited with code " + p.exitValue());

        List<String> created = scanVersions(installDir);
        created.removeAll(before);
        // prefer a version that references forge for this mc version
        String match = created.stream().filter(v -> v.startsWith(forgeBuild)).findFirst()
                .or(() -> created.stream().findFirst()).orElse(null);
        if (match == null) throw new IOException("Installer finished but no version was created. Check the log.");
        return match;
    }

    private static List<String> scanVersions(Path versionsDir) throws IOException {
        List<String> ids = new ArrayList<>();
        if (Files.isDirectory(versionsDir)) {
            try (var s = Files.list(versionsDir)) {
                for (Path p : s.filter(Files::isDirectory).toList()) {
                    if (Files.exists(p.resolve(p.getFileName() + ".json"))) ids.add(p.getFileName().toString());
                }
            }
        }
        return ids;
    }
}
