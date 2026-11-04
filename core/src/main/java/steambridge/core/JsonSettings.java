package steambridge.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

public final class JsonSettings {
    public static JsonSettings defaults() {
        return new JsonSettings(
                true,
                0,
                true,
                true,
                "allow",
                30,
                60,
                "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302");
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("allowWithoutAuth", allowWithoutAuth);
        json.addProperty("virtualPort", virtualPort);
        json.addProperty("interceptUdp", interceptUdp);
        json.addProperty("disableOnlineModeOnPublish", disableOnlineModeOnPublish);
        json.addProperty("sameNameAsHost", sameNameAsHost);
        json.addProperty("timeoutInitialSec", timeoutInitialSec);
        json.addProperty("timeoutConnectedSec", timeoutConnectedSec);
        json.addProperty("stunServers", stunServers);
        json.addProperty("iceEnabled", iceEnabled);
        return json;
    }

    public final boolean allowWithoutAuth, interceptUdp, disableOnlineModeOnPublish;
    public final int virtualPort, timeoutInitialSec, timeoutConnectedSec;
    public final String sameNameAsHost, stunServers;
    /** false forces Valve relay for hosting and joining; direct ICE routes are never attempted. */
    public final boolean iceEnabled;

    public JsonSettings(
            boolean auth,
            int port,
            boolean udp,
            boolean offline,
            String sameName,
            int initial,
            int connected,
            String stun) {
        this(auth, port, udp, offline, sameName, initial, connected, stun, true);
    }

    public JsonSettings(
            boolean auth,
            int port,
            boolean udp,
            boolean offline,
            String sameName,
            int initial,
            int connected,
            String stun,
            boolean ice) {
        iceEnabled = ice;
        allowWithoutAuth = auth;
        virtualPort = port;
        interceptUdp = udp;
        disableOnlineModeOnPublish = offline;
        sameNameAsHost = sameName;
        timeoutInitialSec = initial;
        timeoutConnectedSec = connected;
        stunServers = stun;
    }

    public static JsonSettings parse(JsonObject json, JsonSettings current) {
        int port = number(json, "virtualPort", current.virtualPort);
        if (port < 0 || port > 65534)
            throw new IllegalArgumentException("virtualPort must be between 0 and 65534");
        int initial = timeout(json, "timeoutInitial", current.timeoutInitialSec);
        int connected = timeout(json, "timeoutConnected", current.timeoutConnectedSec);
        if (initial < 5 || initial > 120 || connected < 10 || connected > 300)
            throw new IllegalArgumentException("Steam timeouts must be 5..120 and 10..300 seconds");
        String sameName =
                string(json, "sameNameAsHost", current.sameNameAsHost)
                        .trim()
                        .toLowerCase(Locale.ROOT);
        if (!sameName.equals("allow") && !sameName.equals("kick"))
            throw new IllegalArgumentException("sameNameAsHost must be allow or kick");
        String stun = string(json, "stunServers", current.stunServers).trim();
        if (stun.length() > 4096
                || stun.indexOf('\0') >= 0
                || stun.indexOf('\r') >= 0
                || stun.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid stunServers");
        return new JsonSettings(
                bool(json, "allowWithoutAuth", current.allowWithoutAuth),
                port,
                bool(json, "interceptUdp", current.interceptUdp),
                bool(json, "disableOnlineModeOnPublish", current.disableOnlineModeOnPublish),
                sameName,
                initial,
                connected,
                stun,
                bool(json, "iceEnabled", current.iceEnabled));
    }

    private static int timeout(JsonObject json, String key, int fallback) {
        if (json.has(key + "Sec")) return number(json, key + "Sec", fallback);
        if (json.has(key + "Ms")) return number(json, key + "Ms", fallback * 1000) / 1000;
        return fallback;
    }

    private static int number(JsonObject json, String key, int fallback) {
        if (!json.has(key)) return fallback;
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(key + " must be an integer");
        return value.getAsBigDecimal().intValueExact();
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) return fallback;
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(key + " must be a boolean");
        return value.getAsBoolean();
    }

    private static String string(JsonObject json, String key, String fallback) {
        if (!json.has(key)) return fallback;
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(key + " must be a string");
        return value.getAsString();
    }
}
