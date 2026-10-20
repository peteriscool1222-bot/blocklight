package dev.mclauncher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A resolved version: either a vanilla version json or a modded profile
 * (Fabric/Forge) that inherits from one via "inheritsFrom".
 * Child values win for scalars; libraries and arguments are merged.
 */
public final class VersionChain {
    public final JsonObject root;        // the version the user selected
    public final JsonObject base;        // vanilla ancestor (may equal root)
    public final List<JsonObject> chain; // root -> ... -> base (root first)

    private VersionChain(JsonObject root, JsonObject base, List<JsonObject> chain) {
        this.root = root;
        this.base = base;
        this.chain = chain;
    }

    public static VersionChain resolve(String id, Path versionsDir) throws IOException, InterruptedException {
        List<JsonObject> chain = new ArrayList<>();
        JsonObject current = load(id, versionsDir);
        chain.add(current);
        int guard = 0;
        while (current.has("inheritsFrom") && guard++ < 10) {
            String parentId = current.get("inheritsFrom").getAsString();
            current = load(parentId, versionsDir);
            chain.add(0, current); // base first
        }
        return new VersionChain(chain.get(chain.size() - 1), chain.get(0), chain);
    }

    private static JsonObject load(String id, Path versionsDir) throws IOException, InterruptedException {
        Path p = versionsDir.resolve(id).resolve(id + ".json");
        if (Files.exists(p)) return Jsons.parseFile(p);
        var entry = VersionManifest.find(id);
        if (entry == null) throw new IOException("Unknown version: " + id);
        return VersionManifest.downloadVersionJson(entry, versionsDir);
    }

    public String id() { return Jsons.str(root, "id", "?"); }

    /** Version string passed to --version (root id). */
    public String displayName() { return id(); }

    public String mainClass() {
        // child wins: iterate from the selected version back to the base
        for (int i = chain.size() - 1; i >= 0; i--)
            if (chain.get(i).has("mainClass"))
                return chain.get(i).get("mainClass").getAsString();
        return "net.minecraft.client.main.Main";
    }

    /** The jar to run: explicit "jar" field, else the base (vanilla) id. */
    public String jarId() {
        for (int i = chain.size() - 1; i >= 0; i--)
            if (chain.get(i).has("jar"))
                return chain.get(i).get("jar").getAsString();
        return base.get("id").getAsString();
    }

    public int javaVersion() {
        for (JsonObject o : chain) {
            if (o.has("javaVersion"))
                return o.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
        }
        return 8;
    }

    public JsonObject assetIndex() {
        for (JsonObject o : chain) if (o.has("assetIndex")) return o.getAsJsonObject("assetIndex");
        return null;
    }

    public JsonObject loggingConfig() {
        if (root.has("logging")) {
            var l = root.getAsJsonObject("logging").getAsJsonObject("client");
            if (l != null) return l;
        }
        if (base.has("logging")) {
            var l = base.getAsJsonObject("logging").getAsJsonObject("client");
            if (l != null) return l;
        }
        return null;
    }

    /** Merged libraries, child-first, deduped by name (first occurrence wins). */
    public List<JsonObject> libraries() {
        Set<String> seen = new LinkedHashSet<>();
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject o : chain) {
            if (!o.has("libraries")) continue;
            for (var el : o.getAsJsonArray("libraries")) {
                JsonObject lib = el.getAsJsonObject();
                String name = lib.get("name").getAsString();
                if (seen.add(name)) out.add(lib);
            }
        }
        return out;
    }

    /** Merged "modern" arguments (jvm or game), child entries first. Empty for legacy versions. */
    public JsonArray arguments(String kind) {
        JsonArray out = new JsonArray();
        for (JsonObject o : chain) {
            if (o.has("arguments") && o.getAsJsonObject("arguments").has(kind)) {
                for (var el : o.getAsJsonObject("arguments").getAsJsonArray(kind)) out.add(el);
            }
        }
        return out;
    }

    public boolean hasModernArguments() {
        return chain.stream().anyMatch(o -> o.has("arguments"));
    }

    /** Legacy space-separated minecraftArguments from the base version. */
    public String legacyGameArgs() {
        for (JsonObject o : chain) if (o.has("minecraftArguments"))
            return o.get("minecraftArguments").getAsString();
        return null;
    }
}
