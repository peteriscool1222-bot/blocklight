package dev.mclauncher.install;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mclauncher.core.Downloader;
import dev.mclauncher.core.Jsons;
import dev.mclauncher.core.LauncherCore;
import dev.mclauncher.core.LauncherPaths;
import dev.mclauncher.core.VersionChain;
import dev.mclauncher.storage.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports Modrinth modpacks (.mrpack): a zip containing {@code modrinth.index.json}
 * (file list plus Minecraft/loader dependencies) and optional {@code overrides/}
 * and {@code client-overrides/} folders.
 *
 * <p>Every pack gets an isolated instance folder {@code game/instances/<id>/} that
 * holds its own mods, config, saves and options — while versions, libraries and
 * assets stay shared with all other installations. The link is
 * {@code versions/<id>/instance.json}, which the launcher follows at launch time
 * to set ${game_directory}. Deleting the pack deletes both halves.
 */
public final class MrpackInstaller {
    private MrpackInstaller() {}

    /** Imports the pack; returns the id it was installed under (select it on Play). */
    public static String install(Path mrpack, Path game, Settings settings,
                                 Consumer<String> appLog,
                                 java.util.function.DoubleConsumer progress,
                                 Consumer<String> status) throws Exception {
        // ---------------------------------------------------------------- index
        status.accept("Reading modpack index...");
        JsonObject index;
        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            ZipEntry entry = zip.getEntry("modrinth.index.json");
            if (entry == null)
                throw new IOException("Not a Modrinth modpack — modrinth.index.json is missing.");
            try (InputStream in = zip.getInputStream(entry)) {
                index = JsonParser.parseString(new String(in.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
        if (Jsons.intOrDefault(index, "formatVersion", 0) != 1)
            throw new IOException("Unsupported modpack format: " + Jsons.str(index, "formatVersion", "?"));
        if (!"minecraft".equals(Jsons.str(index, "game", "minecraft")))
            throw new IOException("This modpack is for '" + Jsons.str(index, "game", "?") + "', not Minecraft.");

        JsonObject deps = index.has("dependencies") ? index.getAsJsonObject("dependencies") : new JsonObject();
        String mcVersion = deps.has("minecraft") ? deps.get("minecraft").getAsString() : null;
        if (mcVersion == null || mcVersion.isBlank())
            throw new IOException("The modpack doesn't declare a Minecraft version.");

        String name = Jsons.str(index, "name", stripExt(mrpack.getFileName().toString()));
        String packId = sanitize(name);
        String versionId = Jsons.str(index, "versionId", "");
        Path versionsDir = LauncherPaths.versions(game);
        Path packDir = versionsDir.resolve(packId);
        Path instanceDir = game.resolve("instances").resolve(packId);
        Files.createDirectories(packDir);
        Files.createDirectories(instanceDir);
        appLog.accept("Importing pack '" + name + "' as " + packId + " (Minecraft " + mcVersion + ")");
        progress.accept(0.05);

        // ------------------------------------------------------------ loader
        String parent;
        if (deps.has("fabric-loader")) {
            String loader = deps.get("fabric-loader").getAsString();
            status.accept("Installing Fabric loader " + loader + "...");
            parent = FabricInstaller.install(mcVersion, loader, versionsDir);
            appLog.accept("Fabric profile: " + parent);
        } else if (deps.has("forge")) {
            String v = deps.get("forge").getAsString();
            String build = v.startsWith(mcVersion + "-") ? v : mcVersion + "-" + v;
            status.accept("Installing Forge " + build + "...");
            parent = ForgeInstaller.install(build, game,
                    LauncherPaths.ROOT.resolve("forge-work"), settings, appLog);
        } else if (deps.has("neoforge")) {
            throw new IOException("This pack needs NeoForge, which Blocklight can't install yet.");
        } else if (deps.has("quilt-loader")) {
            throw new IOException("This pack needs Quilt, which Blocklight can't install yet.");
        } else {
            parent = mcVersion; // vanilla pack — the vanilla json comes from the version manifest
        }
        progress.accept(0.15);

        // ------------------------------------------------- profile + marker
        JsonObject profile = new JsonObject();
        profile.addProperty("id", packId);
        profile.addProperty("type", "release");
        profile.addProperty("inheritsFrom", parent);
        Files.writeString(packDir.resolve(packId + ".json"), Jsons.GSON.toJson(profile));

        JsonObject marker = new JsonObject();
        marker.addProperty("dir", "instances/" + packId); // relative: survives moving the install dir
        marker.addProperty("name", name);
        marker.addProperty("source", mrpack.getFileName().toString());
        if (!versionId.isEmpty()) marker.addProperty("versionId", versionId);
        Files.writeString(LauncherPaths.instanceMarker(game, packId), Jsons.GSON.toJson(marker));

        // ----------------------------------------------------- pack files
        List<Downloader.Task> tasks = packTasks(index, instanceDir);
        int total = tasks.size();
        long expectedBytes = tasks.stream().mapToLong(Downloader.Task::size).sum();
        status.accept("Downloading pack files (0 / " + total + ")...");
        Downloader.run(tasks, (done, bytes, cur) -> {
            // byte-based keeps the bar moving during big files (shader packs,
            // resource packs); count-based is the floor for size-less entries and
            // re-imports where cached files contribute no bytes
            double byBytes = expectedBytes > 0 ? (double) bytes / expectedBytes : 0;
            double byCount = (double) done / Math.max(1, total);
            double frac = Math.min(1.0, Math.max(byBytes, byCount));
            progress.accept(0.15 + 0.5 * frac);
            status.accept("Pack files: " + done + " / " + total + "  —  " + cur);
        });
        progress.accept(0.65);

        // ---------------------------------------------------------- overrides
        status.accept("Applying overrides...");
        int overridden = applyOverrides(mrpack, "overrides/", instanceDir)
                + applyOverrides(mrpack, "client-overrides/", instanceDir);
        if (overridden > 0) appLog.accept("Overrides applied: " + overridden + " file(s)");
        progress.accept(0.7);

        // ------------------------------------ client jar, libraries, assets, natives
        status.accept("Installing Minecraft files...");
        VersionChain chain = VersionChain.resolve(packId, versionsDir);
        LauncherCore.install(chain, game, p -> progress.accept(0.7 + 0.3 * p), status);
        progress.accept(1.0);
        status.accept("Ready");
        return packId;
    }

    /** Download tasks for every client-side entry of the index (zip-slip safe targets). */
    private static List<Downloader.Task> packTasks(JsonObject index, Path instanceDir)
            throws IOException {
        List<Downloader.Task> out = new ArrayList<>();
        if (!index.has("files")) return out;
        JsonArray files = index.getAsJsonArray("files");
        for (var el : files) {
            JsonObject f = el.getAsJsonObject();
            JsonObject env = f.has("env") ? f.getAsJsonObject("env") : null;
            // "unsupported" on the client means server-only content
            if (env != null && "unsupported".equals(Jsons.str(env, "client", ""))) continue;
            if (!f.has("path") || !f.has("downloads")) continue;
            String rel = f.get("path").getAsString();
            Path target = instanceDir.resolve(rel).normalize();
            if (!target.startsWith(instanceDir.normalize()))
                throw new IOException("Unsafe path in modpack: " + rel);
            JsonArray urls = f.getAsJsonArray("downloads");
            if (urls.size() == 0) continue;
            JsonObject hashes = f.has("hashes") ? f.getAsJsonObject("hashes") : null;
            String sha1 = hashes != null ? Jsons.str(hashes, "sha1", "") : "";
            long size = f.has("fileSize") ? f.get("fileSize").getAsLong() : 0;
            out.add(new Downloader.Task(rel, urls.get(0).getAsString(), target, sha1, size));
        }
        return out;
    }

    /** Extracts one overrides tree over the instance dir; returns files written. */
    private static int applyOverrides(Path zipFile, String prefix, Path targetDir) throws IOException {
        int n = 0;
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String nm = e.getName();
                if (!nm.startsWith(prefix)) continue;
                String rel = nm.substring(prefix.length());
                if (rel.isEmpty() || rel.startsWith("__MACOSX") || rel.startsWith(".DS_Store")) continue;
                Path target = targetDir.resolve(rel).normalize();
                if (!target.startsWith(targetDir.normalize())) continue; // zip-slip guard
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                n++;
            }
        }
        return n;
    }

    /** Folder-safe id: drops characters Windows forbids, collapses whitespace. */
    static String sanitize(String s) {
        String t = s.replaceAll("[\\\\/:*?\"<>|]", "").trim().replaceAll("\\s+", " ");
        return t.isEmpty() ? "modpack" : t;
    }

    private static String stripExt(String s) {
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }
}
