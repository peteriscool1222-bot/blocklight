package dev.mclauncher.auth;

/** A launcher account: Microsoft (msa) or offline. */
public final class Account {
    public String type;        // "msa" | "offline"
    public String username;    // display name
    public String uuid;        // no dashes
    public String accessToken;
    public String refreshToken;
    public long expiresAt;     // epoch millis
    public String xuid;        // xbox user id (msa only)

    public boolean isMicrosoft() { return "msa".equals(type); }

    public boolean expired() {
        return isMicrosoft() && System.currentTimeMillis() > expiresAt - 60_000;
    }

    @Override
    public String toString() {
        return username == null ? "?" : username;
    }
}
