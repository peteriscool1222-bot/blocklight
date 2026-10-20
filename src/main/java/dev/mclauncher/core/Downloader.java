package dev.mclauncher.core;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Concurrent downloader with sha1 verification and progress reporting.
 * Progress callback receives (done, total) and is invoked via the ui dispatcher.
 */
public final class Downloader {
    public record Task(String name, String url, Path target, String sha1, long size) {}

    public interface Progress { void update(int done, long totalBytes, String current); }

    private Downloader() {}

    public static void run(List<Task> tasks, Progress progress) throws IOException, InterruptedException {
        // Filter: already present with correct size and sha -> skip sha check by size only for speed
        List<Task> todo = new ArrayList<>();
        for (Task t : tasks) {
            if (Files.exists(t.target()) && Files.size(t.target()) == Math.max(1, t.size())) {
                if (t.sha1() == null || t.sha1().isEmpty() || sha1(t.target()).equals(t.sha1())) continue;
            }
            todo.add(t);
        }
        if (todo.isEmpty()) {
            if (progress != null) progress.update(tasks.size(), 0, "All files up to date");
            return;
        }
        Files.createDirectories(Path.of("."));
        // done counts across ALL tasks — files skipped above are already done,
        // otherwise callers dividing by the full task count freeze partway on
        // re-installs where most of the content is cached
        AtomicInteger done = new AtomicInteger(tasks.size() - todo.size());
        AtomicLong bytes = new AtomicLong();
        AtomicLong lastEmit = new AtomicLong(); // nanoTime of the last progress event
        int threads = Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Task t : todo) {
                futures.add(pool.submit(() -> {
                    try {
                        Path tmp = t.target().resolveSibling(t.target().getFileName() + ".part");
                        Files.createDirectories(t.target().getParent());
                        byte[] data;
                        if (progress == null) {
                            data = Http.getBytes(t.url());
                        } else {
                            // stream with live byte feedback, throttled so 16 threads
                            // can't flood the UI thread with events (~10/s total)
                            long[] reported = {0};
                            data = Http.getBytes(t.url(), total -> {
                                if (total < reported[0]) reported[0] = 0; // retry restarted the stream
                                bytes.addAndGet(total - reported[0]);
                                reported[0] = total;
                                long now = System.nanoTime();
                                long last = lastEmit.get();
                                if (now - last > 100_000_000L && lastEmit.compareAndSet(last, now))
                                    progress.update(done.get(), bytes.get(), t.name());
                            });
                        }
                        if (t.sha1() != null && !t.sha1().isEmpty()) {
                            String got = sha1(data);
                            if (!got.equals(t.sha1()))
                                throw new IOException("sha1 mismatch for " + t.name());
                        }
                        Files.write(tmp, data);
                        Files.move(tmp, t.target(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException | InterruptedException e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        throw new RuntimeException("Failed downloading " + t.name() + ": " + e.getMessage(), e);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed downloading " + t.name() + ": " + e.getMessage(), e);
                    }
                    int d = done.incrementAndGet();
                    if (progress != null)
                        progress.update(d, bytes.get(), t.name());
                    return null;
                }));
            }
            Throwable failure = null;
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception e) {
                    if (failure == null) failure = e.getCause() != null ? e.getCause() : e;
                }
            }
            if (failure != null) {
                if (failure instanceof IOException ioe) throw ioe;
                throw new IOException(failure.getMessage(), failure);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    public static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return sha1(in.readAllBytes());
        }
    }

    public static String sha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Build the asset tasks for a version's asset index. */
    public static List<Task> assetTasks(JsonObject assetIndex, Path assetsDir) {
        List<Task> tasks = new ArrayList<>();
        tasks.add(new Task("asset index " + assetIndex.get("id").getAsString(),
                assetIndex.get("url").getAsString(),
                assetsDir.resolve("indexes").resolve(assetIndex.get("id").getAsString() + ".json"),
                Jsons.str(assetIndex, "sha1", ""),
                assetIndex.has("totalSize") ? assetIndex.get("totalSize").getAsLong() : 0));
        return tasks;
    }

    /** Parse a downloaded asset index file into object download tasks. */
    public static List<Task> objectTasks(Path indexFile, Path assetsDir) throws IOException {
        JsonObject index = Jsons.parseFile(indexFile);
        List<Task> tasks = new ArrayList<>();
        JsonObject objects = index.getAsJsonObject("objects");
        for (String key : objects.keySet()) {
            JsonObject o = objects.getAsJsonObject(key);
            String hash = o.get("hash").getAsString();
            long size = o.get("size").getAsLong();
            tasks.add(new Task(key,
                    "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash,
                    assetsDir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash),
                    hash, size));
        }
        return tasks;
    }

    public static void sortForDisplay(List<Task> tasks) {
        tasks.sort(Comparator.comparing(Task::name));
    }

    public static Progress chained(Progress a, Progress b) {
        return (d, t, c) -> { if (a != null) a.update(d, t, c); if (b != null) b.update(d, t, c); };
    }
}
