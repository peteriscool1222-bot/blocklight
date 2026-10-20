package dev.mclauncher.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Extracts native libraries from LWJGL jars into the natives directory. */
public final class Natives {
    private Natives() {}

    /** Files we ignore when extracting natives. */
    private static final List<String> EXCLUDES = List.of(
            "META-INF/", ".sha1", ".git", "module-info.class");

    public static void extract(List<Path> nativeLibs, Path nativesDir) throws IOException {
        Files.createDirectories(nativesDir);
        for (Path jar : nativeLibs) {
            if (!Files.exists(jar)) continue;
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    if (name.startsWith("META-INF/")) continue;
                    if (name.endsWith(".sha1") || name.endsWith(".git")) continue;
                    if (name.equals("module-info.class")) continue;
                    Path out = nativesDir.resolve(name).normalize();
                    if (!out.startsWith(nativesDir)) continue; // zip-slip guard
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zip.getInputStream(e)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }
}
