package dev.mclauncher.auth;

import com.google.gson.JsonObject;
import dev.mclauncher.core.Http;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Microsoft OAuth device-code flow -> Xbox Live -> XSTS -> Minecraft services.
 *
 * Device-code sign-in uses the legacy login.live.com endpoint with the official
 * Minecraft launcher client id (the same one open-source launchers use); the
 * v2 login.microsoftonline.com endpoints reject that client id for the device
 * flow but do accept its refresh tokens.
 */
public final class MicrosoftAuth {
    public static final String CLIENT_ID = "00000000402b5328"; // Minecraft launcher official client id
    public static final String DEVICE_URL = "https://login.live.com/oauth20_connect.srf";
    public static final String REFRESH_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    public static final String REFRESH_URL_FALLBACK = DEVICE_URL;
    public static final String SCOPE = "XboxLive.signin offline_access";

    public record DeviceCode(String userCode, String verificationUri, String message, int interval, int expiresInSeconds) {}

    public interface TokenListener { void onStatus(String message); }

    private MicrosoftAuth() {}

    public static DeviceCode requestDeviceCode() throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("scope", SCOPE);
        form.put("response_type", "device_code");
        String body = Http.postFormRaw(DEVICE_URL, form);
        JsonObject o = dev.mclauncher.core.Jsons.GSON.fromJson(body, JsonObject.class);
        if (!o.has("device_code"))
            throw new IOException("Device code request failed: " + body);
        return new DeviceCode(
                o.get("user_code").getAsString(),
                o.get("verification_uri").getAsString(),
                o.has("message") ? o.get("message").getAsString() : "Visit " + o.get("verification_uri").getAsString(),
                o.get("interval").getAsInt(),
                o.get("expires_in").getAsInt());
    }

    /** Polls until the user authorizes (or times out). Returns a completed Account. */
    public static Account completeDeviceFlow(DeviceCode dc, TokenListener listener) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + dc.expiresInSeconds() * 1000L;
        int interval = Math.max(3, dc.interval());
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", CLIENT_ID);
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            form.put("device_code", dc.userCode());
            form.put("response_type", "device_code");
            String body;
            try {
                body = Http.postFormRaw(DEVICE_URL, form);
            } catch (IOException e) {
                continue; // transient
            }
            JsonObject o = dev.mclauncher.core.Jsons.GSON.fromJson(body, JsonObject.class);
            if (o.has("access_token")) {
                listener.onStatus("Completing Xbox sign-in...");
                return fromMicrosoftTokens(
                        o.get("access_token").getAsString(),
                        o.has("refresh_token") ? o.get("refresh_token").getAsString() : "",
                        o.get("expires_in").getAsLong() * 1000L);
            }
            String error = o.has("error") ? o.get("error").getAsString() : "?";
            switch (error) {
                case "authorization_pending" -> listener.onStatus("Waiting for you to authorize in the browser...");
                case "slow_down" -> interval += 5;
                case "expired_token" -> throw new IOException("The device code expired before authorization.");
                case "authorization_declined" -> throw new IOException("Authorization was declined.");
                default -> throw new IOException("Microsoft token error: " + error + " " + body);
            }
        }
        throw new IOException("Timed out waiting for Microsoft authorization.");
    }

    /** Refresh an existing account's tokens. */
    public static Account refresh(Account acc) throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", acc.refreshToken);
        form.put("scope", SCOPE);
        JsonObject o = null;
        for (String url : new String[]{REFRESH_URL, REFRESH_URL_FALLBACK}) {
            try {
                String body = Http.postFormRaw(url, form);
                JsonObject parsed = dev.mclauncher.core.Jsons.GSON.fromJson(body, JsonObject.class);
                if (parsed.has("access_token")) { o = parsed; break; }
            } catch (IOException ignored) {
                // try the next endpoint
            }
        }
        if (o == null)
            throw new IOException("Token refresh failed — please sign in again.");
        Account fresh = fromMicrosoftTokens(
                o.get("access_token").getAsString(),
                o.has("refresh_token") ? o.get("refresh_token").getAsString() : acc.refreshToken,
                o.get("expires_in").getAsLong() * 1000L);
        fresh.username = acc.username;
        return fresh;
    }

    private static Account fromMicrosoftTokens(String msToken, String refresh, long expiresInMs)
            throws IOException, InterruptedException {
        // 1) Xbox Live user auth
        JsonObject xblProps = new JsonObject();
        xblProps.addProperty("AuthMethod", "RpsTicket");
        xblProps.addProperty("SiteName", "user.auth.xboxlive.com");
        JsonObject xblBody = new JsonObject();
        xblBody.addProperty("RpsTicket", "d=" + msToken);
        xblBody.add("Properties", xblProps);
        xblBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblBody.addProperty("TokenType", "JWT");
        JsonObject xbl = Http.postJson("https://user.auth.xboxlive.com/user/authenticate", xblBody.toString());
        String xblToken = xbl.get("Token").getAsString();
        String userHash = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")
                .get(0).getAsJsonObject().get("uhs").getAsString();

        // 2) XSTS
        JsonObject xstsProps = new JsonObject();
        xstsProps.addProperty("SandboxId", "Retail");
        com.google.gson.JsonArray userTokens = new com.google.gson.JsonArray();
        userTokens.add(xblToken);
        xstsProps.add("UserTokens", userTokens);
        JsonObject xstsBody = new JsonObject();
        xstsBody.add("Properties", xstsProps);
        xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.addProperty("TokenType", "JWT");
        JsonObject xsts = Http.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody.toString());
        String xstsToken = xsts.get("Token").getAsString();

        // 3) Minecraft services login
        JsonObject mcBody = new JsonObject();
        mcBody.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        JsonObject mc = Http.postJson("https://api.minecraftservices.com/authentication/login_with_xbox", mcBody.toString());
        String mcToken = mc.get("access_token").getAsString();
        long mcExpires = mc.has("expires_in") ? mc.get("expires_in").getAsLong() * 1000L : expiresInMs;

        // 4) Entitlement check
        JsonObject ent = mcRequest("https://api.minecraftservices.com/entitlements/mcstore", mcToken);
        boolean owns = ent.has("items") && ent.getAsJsonArray("items").size() > 0;
        if (!owns) throw new IOException("This Microsoft account does not own Minecraft.");

        // 5) Profile
        JsonObject profile = mcRequest("https://api.minecraftservices.com/minecraft/profile", mcToken);

        Account acc = new Account();
        acc.type = "msa";
        acc.username = profile.get("name").getAsString();
        acc.uuid = profile.get("id").getAsString();
        acc.accessToken = mcToken;
        acc.refreshToken = refresh;
        acc.expiresAt = System.currentTimeMillis() + mcExpires;
        acc.xuid = userHash;
        return acc;
    }

    private static JsonObject mcRequest(String url, String token) throws IOException, InterruptedException {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", Http.USER_AGENT)
                .GET().build();
        java.net.http.HttpResponse<String> res =
                client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2)
            throw new IOException("Minecraft services error " + res.statusCode() + ": " + res.body());
        return dev.mclauncher.core.Jsons.GSON.fromJson(res.body(), JsonObject.class);
    }
}
