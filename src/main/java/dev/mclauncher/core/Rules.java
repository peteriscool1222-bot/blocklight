package dev.mclauncher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Minecraft "rules" evaluation (os / arch / features). */
public final class Rules {
    private Rules() {}

    /**
     * Returns true if the given rules allow the item.
     * No rules -> allowed. Otherwise default disallow, any matching allow flips on,
     * any matching disallow flips off.
     */
    public static boolean allowed(JsonArray rules, Map<String, Boolean> features) {
        if (rules == null || rules.isEmpty()) return true;
        boolean result = false;
        for (var el : rules) {
            JsonObject rule = el.getAsJsonObject();
            boolean match = matches(rule, features);
            if (match) result = Jsons.str(rule, "action", "allow").equals("allow");
        }
        return result;
    }

    private static boolean matches(JsonObject rule, Map<String, Boolean> features) {
        if (rule.has("os")) {
            JsonObject os = rule.getAsJsonObject("os");
            if (os.has("name") && !os.get("name").getAsString().equals(Os.NAME)) return false;
            if (os.has("arch")) {
                String arch = os.get("arch").getAsString();
                // "x86" means 32-bit java
                boolean is32 = System.getProperty("os.arch").toLowerCase(Locale.ROOT).contains("86")
                        && !System.getProperty("os.arch").toLowerCase(Locale.ROOT).contains("64");
                if (arch.equals("x86") && !is32) return false;
            }
            if (os.has("version")) {
                String regex = os.get("version").getAsString();
                String ver = System.getProperty("os.version");
                if (!Pattern.compile(regex).matcher(ver).find()) return false;
            }
        }
        if (rule.has("features")) {
            JsonObject f = rule.getAsJsonObject("features");
            for (String key : f.keySet()) {
                boolean expected = f.get(key).getAsBoolean();
                boolean actual = features.getOrDefault(key, false);
                if (expected != actual) return false;
            }
        }
        return true;
    }

    public static final Set<String> KNOWN_FEATURES = Set.of(
            "is_demo_user", "has_custom_resolution", "has_quick_plays_support",
            "is_quick_play_singleplayer", "is_quick_play_multiplayer", "is_quick_play_realms");
}
