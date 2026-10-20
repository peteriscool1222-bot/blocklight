package dev.mclauncher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mclauncher.auth.Account;
import dev.mclauncher.storage.Settings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Installs versions (client jar, libraries, assets, natives) and launches the game. */
public final class LauncherCore {
    public static final String LAUNCHER_BRAND = "Custom Launcher";
    public static final String LAUNCHER_VERSION = "1.0";

    private LauncherCore() {}

    // ---------------------------------------------------------------- install

    /** Everything the version needs before it can launch. Progress in 0..1. */
    public static void install(VersionChain chain, Path game,
                               java.util.function.DoubleConsumer progress, Consumer<String> status)
            throws IOException, InterruptedException {
        Path versions = LauncherPaths.versions(game);
        Path libraries = LauncherPaths.libraries(game);
        Path assets = LauncherPaths.assets(game);

        Map<String, Boolean> features = defaultFeatures();

        // 1. client jar
        status.accept("Downloading client...");
        JsonObject client = chain.base.has("downloads")
                ? chain.base.getAsJsonObject("downloads").getAsJsonObject("client") : null;
        Path clientJar = versions.resolve(chain.jarId()).resolve(chain.jarId() + ".jar");
        if (client != null && (!Files.exists(clientJar) || Files.size(clientJar) != client.get("size").getAsLong())) {
            long clientSize = Math.max(1L, client.get("size").getAsLong());
            // the client jar is one big file — without byte-level feedback the bar
            // would sit still for the whole download
            Downloader.run(List.of(new Downloader.Task(
                    "client " + chain.jarId(),
                    client.get("url").getAsString(),
                    clientJar,
                    Jsons.str(client, "sha1", ""),
                    client.get("size").getAsLong())), (d, bytes, cur) ->
                    progress.accept(0.25 * Math.min(1.0, Math.max((double) bytes / clientSize, d))));
        } else if (client == null && !Files.exists(clientJar)) {
            throw new IOException("No client jar found for " + chain.jarId() + " and no download URL in version json.");
        }
        progress.accept(0.25);

        // 2. libraries (mirror-aware, concurrent)
        status.accept("Downloading libraries...");
        List<LibrarySpec> libs = collectLibraries(chain, libraries, features);
        downloadLibraries(libs, status, f -> progress.accept(0.25 + 0.25 * f));
        progress.accept(0.5);

        // 3. natives — extract once per version (marker file), not on every launch
        status.accept("Extracting natives...");
        Path nativesDir = LauncherPaths.natives(game, chain.id());
        Path nativesMarker = nativesDir.resolve(".ready");
        if (!Files.exists(nativesMarker)) {
            List<Path> nativeJars = libs.stream().filter(LibrarySpec::isNative).map(LibrarySpec::target).toList();
            Natives.extract(nativeJars, nativesDir);
            try { Files.writeString(nativesMarker, Long.toString(System.currentTimeMillis())); }
            catch (IOException ignored) {}
        }
        progress.accept(0.55);

        // 4. assets
        status.accept("Downloading assets...");
        JsonObject idx = chain.assetIndex();
        if (idx != null) {
            Path indexFile = assets.resolve("indexes").resolve(idx.get("id").getAsString() + ".json");
            Files.createDirectories(indexFile.getParent());
            Files.write(indexFile, Http.getBytes(idx.get("url").getAsString()));
            List<Downloader.Task> tasks = Downloader.objectTasks(indexFile, assets);
            int total = tasks.size();
            Downloader.run(tasks, (d, bytes, cur) -> {
                progress.accept(0.55 + 0.44 * Math.min(1.0, (double) d / Math.max(1, total)));
                status.accept("Assets: " + d + " / " + total);
            });
        }
        progress.accept(1.0);

        // 5. logging config (client log4j config) — non-fatal: the game falls back
        // to its default configuration when the file is missing
        JsonObject logging = chain.loggingConfig();
        if (logging != null && logging.has("file")) {
            JsonObject f = logging.getAsJsonObject("file");
            Path logCfg = game.resolve("log-configs").resolve(f.get("id").getAsString());
            if (!Files.exists(logCfg)) {
                Files.createDirectories(logCfg.getParent());
                try {
                    Files.write(logCfg, Http.getBytes(f.get("url").getAsString()));
                } catch (IOException e) {
                    status.accept("Log config unavailable (continuing): " + e.getMessage());
                }
            }
        }
        status.accept("Ready");
    }

    private static Map<String, Boolean> defaultFeatures() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("is_demo_user", false);
        features.put("has_custom_resolution", true);
        features.put("has_quick_plays_support", false);
        features.put("is_quick_play_singleplayer", false);
        features.put("is_quick_play_multiplayer", false);
        features.put("is_quick_play_realms", false);
        return features;
    }

    private record LibrarySpec(String name, String url, Path target, String sha1, boolean isNative) {}

    private static List<LibrarySpec> collectLibraries(VersionChain chain, Path librariesDir,
                                                      Map<String, Boolean> features) {
        List<LibrarySpec> out = new ArrayList<>();
        for (JsonObject lib : chain.libraries()) {
            if (!Rules.allowed(lib.has("rules") ? lib.getAsJsonArray("rules") : null, features)) continue;
            JsonObject downloads = lib.has("downloads") ? lib.getAsJsonObject("downloads") : null;

            // regular artifact
            if (downloads != null && downloads.has("artifact")) {
                JsonObject a = downloads.getAsJsonObject("artifact");
                out.add(new LibrarySpec(lib.get("name").getAsString(),
                        Jsons.str(a, "url", null),
                        librariesDir.resolve(a.get("path").getAsString()),
                        Jsons.str(a, "sha1", ""), false));
            } else if (!lib.has("natives")) {
                String name = lib.get("name").getAsString();
                String url = lib.has("url") ? lib.get("url").getAsString() : null;
                out.add(new LibrarySpec(name, url, librariesDir.resolve(mavenPath(name)), "", false));
            }

            // native classifier
            if (lib.has("natives")) {
                JsonObject natives = lib.getAsJsonObject("natives");
                String classifier = natives.has(Os.NAME)
                        ? natives.get(Os.NAME).getAsString().replace("${arch}", Os.nativesArch())
                        : null;
                if (classifier == null) continue;
                String name = lib.get("name").getAsString();
                String classifierName = name + ":" + classifier;
                if (downloads != null && downloads.has("classifiers")) {
                    JsonObject cls = downloads.getAsJsonObject("classifiers");
                    if (cls.has(classifier)) {
                        JsonObject a = cls.getAsJsonObject(classifier);
                        out.add(new LibrarySpec(classifierName,
                                Jsons.str(a, "url", null),
                                librariesDir.resolve(a.get("path").getAsString()),
                                Jsons.str(a, "sha1", ""), true));
                        continue;
                    }
                }
                out.add(new LibrarySpec(classifierName, lib.has("url") ? lib.get("url").getAsString() : null,
                        librariesDir.resolve(mavenPath(classifierName)), "", true));
            }
        }
        return out;
    }

    /** group:artifact:version[:classifier] -> maven path */
    static String mavenPath(String name) {
        String[] parts = name.split(":");
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
    }

    private static void downloadLibraries(List<LibrarySpec> libs, Consumer<String> status,
                                          java.util.function.DoubleConsumer progress)
            throws IOException, InterruptedException {
        List<LibrarySpec> todo = new ArrayList<>();
        for (LibrarySpec s : libs) {
            if (Files.exists(s.target()) && (s.sha1().isEmpty() || Downloader.sha1(s.target()).equals(s.sha1())))
                continue;
            todo.add(s);
        }
        if (todo.isEmpty()) return;
        int threads = Math.min(8, Math.max(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger done = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (LibrarySpec s : todo) {
                futures.add(pool.submit(() -> {
                    byte[] data = fetchLibrary(s);
                    if (!s.sha1().isEmpty() && !Downloader.sha1(data).equals(s.sha1()))
                        throw new RuntimeException("sha1 mismatch for " + s.name()
                                + " (mirror served a different jar)");
                    Files.createDirectories(s.target().getParent());
                    Path tmp = s.target().resolveSibling(s.target().getFileName() + ".part");
                    Files.write(tmp, data);
                    Files.move(tmp, s.target(), StandardCopyOption.REPLACE_EXISTING);
                    int n = done.incrementAndGet();
                    status.accept("Libraries: " + n + " / " + todo.size());
                    progress.accept((double) n / todo.size());
                    return null;
                }));
            }
            Throwable failure = null;
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception e) {
                    if (failure == null) failure = e.getCause() != null ? e.getCause() : e;
                }
            }
            if (failure != null) throw new IOException("Library download failed: " + failure.getMessage(), failure);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Try the declared URL first, then known maven mirrors. */
    private static byte[] fetchLibrary(LibrarySpec s) throws IOException, InterruptedException {
        List<String> candidates = new ArrayList<>();
        String path = mavenPath(s.name());
        if (s.url() != null && !s.url().isBlank()) {
            // the declared url may be a full jar url or a maven repo base
            if (s.url().endsWith(".jar")) candidates.add(s.url());
            else candidates.add((s.url().endsWith("/") ? s.url() : s.url() + "/") + path);
        }
        String group = s.name().split(":")[0];
        candidates.add("https://libraries.minecraft.net/" + path);
        if (group.startsWith("net.fabricmc")) candidates.add("https://maven.fabricmc.net/" + path);
        if (group.startsWith("net.minecraftforge") || group.startsWith("cpw.mods") || group.startsWith("net.neoforged"))
            candidates.add("https://maven.minecraftforge.net/" + path);
        if (group.startsWith("org.quiltmc")) candidates.add("https://maven.quiltmc.org/repository/release/" + path);
        if (group.startsWith("org.spongepowered")) candidates.add("https://repo.spongepowered.org/repository/maven-public/" + path);
        // Maven Central as a last resort — some Mojang manifest URLs point at repos that no longer host the jar
        candidates.add("https://repo1.maven.org/maven2/" + path);
        IOException last = null;
        for (String url : candidates) {
            try {
                return Http.getBytes(url);
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                throw e;
            }
        }
        throw new IOException("Could not download " + s.name() + " from any mirror: "
                + (last != null ? last.getMessage() : "no urls"));
    }

    // ---------------------------------------------------------------- launch

    public static Process launch(VersionChain chain, Path game, Account account, Settings settings,
                                 Consumer<String> log) throws IOException, InterruptedException {
        Path nativesDir = LauncherPaths.natives(game, chain.id());
        Path librariesDir = LauncherPaths.libraries(game);
        Path assetsDir = LauncherPaths.assets(game);
        // modpack instances run in their own folder (own mods/config/saves);
        // versions, libraries, assets and natives all stay shared
        Path gameDir = LauncherPaths.instanceDir(game, chain.id());
        boolean instance = gameDir != null;
        if (!instance) gameDir = game;
        Map<String, Boolean> features = defaultFeatures();

        // classpath
        StringBuilder cp = new StringBuilder();
        cp.append(versionsJar(chain, game).toAbsolutePath());
        for (JsonObject lib : chain.libraries()) {
            if (!Rules.allowed(lib.has("rules") ? lib.getAsJsonArray("rules") : null, features)) continue;
            Path jar = libraryPath(lib, librariesDir);
            if (jar != null) {
                cp.append(java.io.File.pathSeparator).append(jar.toAbsolutePath());
            }
        }

        List<String> cmd = new ArrayList<>();
        String javaExe = resolveJava(settings, chain.javaVersion(), log);

        // Feral GameMode: system-level performance (CPU governor, I/O priority) —
        // wraps the entire command, so it must come first
        if (settings.gameMode) {
            String gm = gameModeRunner();
            if (gm != null) cmd.add(gm);
            else log.accept("GameMode: gamemoderun not found on PATH — skipped");
        }

        // wrapper command (e.g. mangohud --dlsym) runs in front of the java process
        if (settings.wrapperCommand != null && !settings.wrapperCommand.isBlank()) {
            for (String w : splitArgs(settings.wrapperCommand.trim()))
                if (!w.isBlank()) cmd.add(w);
            log.accept("Using wrapper: " + settings.wrapperCommand.trim());
        }
        cmd.add(javaExe);
        cmd.add("-Xms" + settings.minMemoryMB + "M");
        cmd.add("-Xmx" + settings.maxMemoryMB + "M");
        if (settings.jvmArgs != null && !settings.jvmArgs.isBlank()) {
            for (String a : settings.jvmArgs.trim().split("\\s+")) if (!a.isBlank()) cmd.add(a);
        }
        applyPerfFlags(cmd, settings, log);

        Map<String, String> vars = new HashMap<>();
        vars.put("${auth_player_name}", account.username);
        vars.put("${version_name}", chain.displayName());
        vars.put("${game_directory}", gameDir.toAbsolutePath().toString());
        vars.put("${game_assets}", assetsDir.resolve("virtual").toString());
        vars.put("${assets_root}", assetsDir.toAbsolutePath().toString());
        vars.put("${assets_index_name}", chain.assetIndex() != null
                ? chain.assetIndex().get("id").getAsString() : "legacy");
        vars.put("${auth_uuid}", account.uuid);
        vars.put("${auth_access_token}", account.accessToken == null ? "0" : account.accessToken);
        vars.put("${clientid}", "custom-launcher");
        vars.put("${auth_xuid}", account.xuid == null ? "0" : account.xuid);
        vars.put("${user_type}", account.isMicrosoft() ? "msa" : "legacy");
        vars.put("${version_type}", Jsons.str(chain.base, "type", "release"));
        vars.put("${natives_directory}", nativesDir.toAbsolutePath().toString());
        vars.put("${launcher_name}", LAUNCHER_BRAND);
        vars.put("${launcher_version}", LAUNCHER_VERSION);
        vars.put("${classpath}", cp.toString());
        vars.put("${classpath_separator}", java.io.File.pathSeparator);
        vars.put("${library_directory}", librariesDir.toAbsolutePath().toString());
        vars.put("${resolution_width}", String.valueOf(settings.width));
        vars.put("${resolution_height}", String.valueOf(settings.height));
        vars.put("${quickPlayPath}", "");
        vars.put("${quickPlaySingleplayer}", "");
        vars.put("${quickPlayMultiplayer}", "");
        vars.put("${quickPlayRealms}", "");

        // jvm arguments from version json
        if (chain.hasModernArguments()) {
            for (var el : chain.arguments("jvm")) {
                if (el.isJsonPrimitive()) {
                    cmd.add(substitute(el.getAsString(), vars));
                } else {
                    JsonObject o = el.getAsJsonObject();
                    if (!Rules.allowed(o.has("rules") ? o.getAsJsonArray("rules") : null, features)) continue;
                    var v = o.get("value");
                    if (v.isJsonPrimitive()) cmd.add(substitute(v.getAsString(), vars));
                    else for (var s : v.getAsJsonArray()) cmd.add(substitute(s.getAsString(), vars));
                }
            }
        } else {
            // legacy defaults
            cmd.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
            cmd.add("-cp");
            cmd.add(cp.toString());
        }

        // logging config
        JsonObject logging = chain.loggingConfig();
        if (logging != null && logging.has("file") && logging.has("argument")) {
            String id = logging.getAsJsonObject("file").get("id").getAsString();
            Path logCfg = game.resolve("log-configs").resolve(id);
            if (Files.exists(logCfg)) {
                cmd.add(logging.get("argument").getAsString().replace("${path}", logCfg.toAbsolutePath().toString()));
            }
        }

        cmd.add(chain.mainClass());

        // game arguments
        if (chain.hasModernArguments()) {
            for (var el : chain.arguments("game")) {
                if (el.isJsonPrimitive()) {
                    cmd.add(substitute(el.getAsString(), vars));
                } else {
                    JsonObject o = el.getAsJsonObject();
                    if (!Rules.allowed(o.has("rules") ? o.getAsJsonArray("rules") : null, features)) continue;
                    var v = o.get("value");
                    if (v.isJsonPrimitive()) cmd.add(substitute(v.getAsString(), vars));
                    else for (var s : v.getAsJsonArray()) cmd.add(substitute(s.getAsString(), vars));
                }
            }
        } else {
            String legacy = chain.legacyGameArgs();
            if (legacy != null) {
                for (String a : splitArgs(substitute(legacy, vars))) cmd.add(a);
            }
        }

        if (settings.fullscreen) cmd.add("--fullscreen");

        if (instance) log.accept("Modpack instance folder: " + gameDir);
        log.accept("Launching " + chain.displayName() + " as " + account.username);
        if (settings.keepOpen) {
            // never print the access token into the log
            String shown = String.join(" ", cmd);
            if (account.accessToken != null && !account.accessToken.isBlank() && !account.accessToken.equals("0"))
                shown = shown.replace(account.accessToken, "<token>");
            log.accept(shown);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(gameDir.toFile());
        pb.redirectErrorStream(true);
        if (settings.forceDgpu) applyForceDgpu(pb, javaExe, log);
        Files.createDirectories(gameDir);
        return pb.start();
    }

    /**
     * Run the game on the dedicated GPU.
     * Linux: NVIDIA PRIME render-offload environment variables, so GL/VK contexts are
     * created on the NVIDIA card instead of whatever Mesa (or software rendering)
     * picks — works on dGPU-only boxes and hybrid PRIME setups alike.
     * Windows: registers the Java runtime under the per-app "high performance GPU"
     * preference (the same value Windows Graphics Settings writes).
     * macOS has no GPU-selection mechanism, so nothing is done there.
     */
    /**
     * Aikar's G1GC tuning flags — the standard "performance mode" pack used by
     * advertised-fast clients: smaller, more predictable GC pauses mean fewer
     * frame-time spikes while chunk loading. Region size scales with the heap.
     * Skipped when the user has configured their own collector in Extra JVM args,
     * so nothing ever conflicts with a hand-tuned setup.
     */
    /** Absolute path of gamemoderun, or null when Feral GameMode isn't installed. */
    public static String gameModeRunner() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            java.nio.file.Path p = java.nio.file.Path.of(dir, "gamemoderun");
            try {
                if (java.nio.file.Files.isExecutable(p) && !java.nio.file.Files.isDirectory(p))
                    return p.toString();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void applyPerfFlags(List<String> cmd, Settings settings, Consumer<String> log) {
        if (!settings.perfTweaks) return;
        String user = settings.jvmArgs == null ? "" : settings.jvmArgs;
        String[] ownCollector = {"-XX:+UseG1GC", "-XX:+UseZGC", "-XX:+UseSerialGC",
                "-XX:+UseParallelGC", "-XX:+UseShenandoahGC", "-XX:+UseEpsilonGC"};
        for (String f : ownCollector) {
            if (user.contains(f)) {
                log.accept("Performance mode skipped — custom GC in Extra JVM args (" + f + ")");
                return;
            }
        }
        int regionMB = settings.maxMemoryMB > 12288 ? 16 : 8;
        cmd.addAll(List.of(
                "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
                "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch",
                "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40",
                "-XX:G1HeapRegionSize=" + regionMB + "M",
                "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4",
                "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1MixedGCLiveThresholdPercent=90",
                "-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32",
                "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1",
                "-Dusing.aikars.flags=true", "-Dmcflags.aikars.flags=true"));
        log.accept("Performance mode: Aikar's GC flags applied (G1, " + regionMB + "M regions)");
    }

    private static void applyForceDgpu(ProcessBuilder pb, String javaExe, Consumer<String> log) {
        switch (Os.NAME) {
            case "linux" -> {
                pb.environment().put("__NV_PRIME_RENDER_OFFLOAD", "1");
                pb.environment().put("__GLX_VENDOR_LIBRARY_NAME", "nvidia");
                pb.environment().put("__VK_LAYER_NV_optimus", "NVIDIA_only");
                log.accept("Force dGPU: NVIDIA PRIME render-offload environment set");
            }
            case "windows" -> {
                try {
                    Process p = new ProcessBuilder("reg", "add",
                            "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences",
                            "/v", javaExe, "/t", "REG_SZ", "/d", "GpuPreference=2;", "/f")
                            .redirectErrorStream(true).start();
                    p.waitFor();
                    log.accept("Force dGPU: " + javaExe + " registered as high-performance GPU");
                } catch (Exception e) {
                    log.accept("Force dGPU: registry update failed (" + e.getMessage() + ")");
                }
            }
            default -> log.accept("Force dGPU: not applicable on this OS");
        }
    }

    static String substitute(String template, Map<String, String> vars) {
        String out = template;
        for (var e : vars.entrySet()) out = out.replace(e.getKey(), e.getValue());
        return out;
    }

    /** Split a legacy argument string, honoring quotes. */
    static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (char c : s.toCharArray()) {
            if (c == '"') { inQuote = !inQuote; continue; }
            if (c == ' ' && !inQuote) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static Path versionsJar(VersionChain chain, Path game) {
        return LauncherPaths.versions(game).resolve(chain.jarId()).resolve(chain.jarId() + ".jar");
    }

    private static Path libraryPath(JsonObject lib, Path librariesDir) {
        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
            return librariesDir.resolve(lib.getAsJsonObject("downloads")
                    .getAsJsonObject("artifact").get("path").getAsString());
        }
        if (lib.has("natives")) return null; // natives go through extraction, not classpath
        return librariesDir.resolve(mavenPath(lib.get("name").getAsString()));
    }

    /**
     * Pick the java executable for the game: the user's setting when set, else the
     * current JVM when it's new enough, else a suitable runtime from JAVA_HOME or
     * PATH. Modern Minecraft declares the Java it needs in its version json
     * (26.x = Java 25), and launching with an older runtime only ends in
     * UnsupportedClassVersionError the moment the game's main class loads —
     * so when nothing suitable is found we stop with a clear message instead.
     */
    static String resolveJava(Settings settings, int requiredMajor, Consumer<String> log) throws IOException {
        String javaBin = Os.NAME.equals("windows") ? "bin/java.exe" : "bin/java";
        String javaName = Os.NAME.equals("windows") ? "java.exe" : "java"; // PATH entries are already bin dirs

        // explicit user choice wins — but a too-old runtime deserves a warning
        if (settings.javaPath != null && !settings.javaPath.isBlank()) {
            Path p = Path.of(settings.javaPath.trim());
            if (Files.isDirectory(p)) p = p.resolve(javaBin);
            String exe = p.toAbsolutePath().toString();
            int major = probeJavaMajor(exe);
            if (major > 0 && major < requiredMajor)
                log.accept("WARNING: the Java set in Settings is version " + major + " but this version needs "
                        + requiredMajor + " — the game will most likely fail to start.");
            return exe;
        }

        String current = Path.of(System.getProperty("java.home")).resolve(javaBin).toString();
        List<String> candidates = new ArrayList<>();
        candidates.add(current);
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank()) candidates.add(Path.of(jh).resolve(javaBin).toString());
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) candidates.add(Path.of(dir).resolve(javaName).toString());
            }
        }

        int currentMajor = Runtime.version().feature();
        Set<String> seen = new HashSet<>();
        for (String exe : candidates) {
            if (!seen.add(exe) || !Files.isExecutable(Path.of(exe))) continue;
            int major = exe.equals(current) ? currentMajor : probeJavaMajor(exe);
            if (major >= requiredMajor) {
                if (!exe.equals(current))
                    log.accept("Using Java " + major + " from " + exe + " (this version needs Java " + requiredMajor + ")");
                return exe;
            }
        }
        throw new IOException("This version needs Java " + requiredMajor + ", but no Java " + requiredMajor
                + "+ was found on this system. Install it (e.g. Eclipse Temurin " + requiredMajor
                + ") or set its path in Settings -> Java.");
    }

    /** Major version of the given java binary; 0 when it can't be probed. */
    static int probeJavaMajor(String exe) {
        try {
            Process p = new ProcessBuilder(exe, "-version").redirectErrorStream(true).start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            p.waitFor(5, TimeUnit.SECONDS);
            Matcher m = Pattern.compile("version \"([^\"]+)\"").matcher(out);
            if (!m.find()) return 0;
            String v = m.group(1);
            if (v.startsWith("1.")) v = v.substring(2); // ancient "1.8.0" style
            return Integer.parseInt(v.split("[.\\-_+]")[0]);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Streams process output line by line. */
    public static void pump(Process process, Consumer<String> line) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line2;
                while ((line2 = r.readLine()) != null) line.accept(line2);
            } catch (IOException ignored) {}
        }, "process-output");
        t.setDaemon(true);
        t.start();
    }
}
