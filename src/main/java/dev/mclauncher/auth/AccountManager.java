package dev.mclauncher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mclauncher.core.Jsons;
import dev.mclauncher.core.LauncherPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Loads/saves accounts and creates offline accounts. */
public final class AccountManager {
    private final List<Account> accounts = new ArrayList<>();
    private String defaultUuid = "";

    public AccountManager() {
        load();
    }

    public List<Account> all() { return accounts; }

    public Account defaultAccount() {
        Account byId = accounts.stream().filter(a -> a.uuid.equals(defaultUuid)).findFirst().orElse(null);
        if (byId != null) return byId;
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    public void setDefault(Account acc) {
        defaultUuid = acc == null ? "" : acc.uuid;
        save();
    }

    public void add(Account acc) {
        accounts.removeIf(a -> a.uuid.equals(acc.uuid));
        accounts.add(acc);
        if (defaultUuid.isEmpty()) defaultUuid = acc.uuid;
        save();
    }

    public void remove(Account acc) {
        accounts.removeIf(a -> a.uuid.equals(acc.uuid));
        if (defaultUuid.equals(acc.uuid)) defaultUuid = "";
        save();
    }

    public static Account offline(String username) {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,16}"))
            throw new IllegalArgumentException("Username must be 3-16 characters (letters, digits, underscore).");
        Account a = new Account();
        a.type = "offline";
        a.username = username;
        a.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        a.accessToken = "0";
        return a;
    }

    private void load() {
        try {
            if (!Files.exists(LauncherPaths.ACCOUNTS)) return;
            JsonObject root = Jsons.parseFile(LauncherPaths.ACCOUNTS);
            defaultUuid = Jsons.str(root, "default", "");
            JsonArray arr = root.getAsJsonArray("accounts");
            for (var el : arr) {
                Account a = Jsons.GSON.fromJson(el, Account.class);
                if (a != null && a.uuid != null) {
                    // tokens stored base64-obfuscated (not truly secret on disk)
                    if (a.accessToken != null) a.accessToken = decode(a.accessToken);
                    if (a.refreshToken != null) a.refreshToken = decode(a.refreshToken);
                    accounts.add(a);
                }
            }
        } catch (Exception e) {
            System.err.println("Could not load accounts: " + e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(LauncherPaths.ROOT);
            JsonObject root = new JsonObject();
            root.addProperty("default", defaultUuid);
            JsonArray arr = new JsonArray();
            for (Account a : accounts) {
                Account copy = Jsons.GSON.fromJson(Jsons.GSON.toJson(a), Account.class);
                copy.accessToken = encode(a.accessToken);
                copy.refreshToken = a.refreshToken == null ? null : encode(a.refreshToken);
                arr.add(Jsons.GSON.toJsonTree(copy));
            }
            root.add("accounts", arr);
            Files.writeString(LauncherPaths.ACCOUNTS, Jsons.GSON.toJson(root));
        } catch (IOException e) {
            System.err.println("Could not save accounts: " + e.getMessage());
        }
    }

    private static String encode(String s) {
        return s == null ? null : java.util.Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String s) {
        try {
            return new String(java.util.Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
