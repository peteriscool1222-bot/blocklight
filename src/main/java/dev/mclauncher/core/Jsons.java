package dev.mclauncher.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Jsons {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Jsons() {}

    public static JsonObject parseFile(Path p) throws IOException {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    public static String str(JsonObject o, String key, String def) {
        var v = o.get(key);
        return v == null || v.isJsonNull() ? def : v.getAsString();
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        var v = o.get(key);
        return v == null || v.isJsonNull() ? def : v.getAsBoolean();
    }

    public static int intOrDefault(JsonObject o, String key, int def) {
        var v = o.get(key);
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) return def;
        try {
            return v.getAsInt();
        } catch (Exception e) {
            return def;
        }
    }
}
