package dev.mclauncher.core;

import java.util.Locale;

/** OS / architecture detection helpers (Minecraft rule format). */
public final class Os {
    public static final String NAME;      // windows | osx | linux
    public static final String ARCH;      // x86 | x64 | arm64 | arm32
    public static final String RAW_NAME;  // windows | macos | linux (for natives classifier)

    static {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) { NAME = "windows"; RAW_NAME = "windows"; }
        else if (os.contains("mac")) { NAME = "osx"; RAW_NAME = "osx"; }
        else { NAME = "linux"; RAW_NAME = "linux"; }

        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) ARCH = "arm64";
        else if (arch.startsWith("arm")) ARCH = "arm32";
        else if (arch.contains("86") && !arch.contains("64")) ARCH = "x86";
        else ARCH = "x64";
    }

    private Os() {}

    /** The value Minecraft substitutes for ${arch} in natives classifiers. */
    public static String nativesArch() {
        return ARCH.equals("x86") ? "32" : "64";
    }
}
