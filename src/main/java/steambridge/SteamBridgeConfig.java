/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config mirrors for NeoForge 1.21.1. */
public final class SteamBridgeConfig {

    private SteamBridgeConfig() {}

    // App ID is fixed to 480 (Spacewar) in SteamAppIdHelper; not configurable.
    public static boolean allowWithoutAuth = true;
    public static int     virtualPort      = 0;
    /** Voice UDP intercept (JVM DatagramSocket factory). Launch-only. */
    public static boolean interceptUdp    = true;
    /**
     * Remember the last game mode / allow-commands choice on the Open for Steam screen, per
     * world. Pokes at vanilla ShareToLanScreen internals through reflection, so if another
     * mod does something similar on the same screen, turn this off.
     */
    public static boolean disableOnlineModeOnPublish = true;
    public static String  sameNameAsHost = "allow";
    public static int     timeoutInitialSec = 30;
    public static int     timeoutConnectedSec = 60;
    public static String  stunServers = "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302";

    public static boolean kickOnSameNameAsHost() {
        return "kick".equalsIgnoreCase(sameNameAsHost);
    }

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ALLOW_WITHOUT_AUTH;
    private static final ModConfigSpec.IntValue     VIRTUAL_PORT;
    private static final ModConfigSpec.BooleanValue INTERCEPT_UDP;
    private static final ModConfigSpec.BooleanValue DISABLE_ONLINE_MODE;
    private static final ModConfigSpec.ConfigValue<String> SAME_NAME_AS_HOST;
    private static final ModConfigSpec.IntValue TIMEOUT_INITIAL_SEC;
    private static final ModConfigSpec.IntValue TIMEOUT_CONNECTED_SEC;
    private static final ModConfigSpec.ConfigValue<String> STUN_SERVERS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        ALLOW_WITHOUT_AUTH = b
            .comment("Allow connections without validating Steam Auth Ticket. "
                   + "False is more secure but might affect some NAT types.")
            .define("allowWithoutAuth", true);

        VIRTUAL_PORT = b
            .comment("Virtual port for Steam network. 0 is default. "
                   + "Change only if conflicting with other mods.")
            .defineInRange("virtualPort", 0, 0, 65535);

        INTERCEPT_UDP = b
            .comment("DatagramSocket factory for voice mods (SVC, Plasmo Voice). "
                   + "Launch-only; cannot toggle at runtime.")
            .define("interceptUdp", true);



        DISABLE_ONLINE_MODE = b
            .comment("After opening the world for Steam, turn off Mojang online-mode so cracked friends "
                   + "are not sent a licensed HELLO. Leave on unless you need Mojang auth on LAN.")
            .define("disableOnlineModeOnPublish", true);

        SAME_NAME_AS_HOST = b
            .comment("When a Steam guest uses the host Minecraft name: allow (offline UUID) or kick.")
            .define("sameNameAsHost", "allow");

        TIMEOUT_INITIAL_SEC = b
            .comment("Steam P2P initial route timeout in seconds.")
            .defineInRange("timeoutInitialSec", 30, 5, 120);

        TIMEOUT_CONNECTED_SEC = b
            .comment("Steam drop timeout after the connection is up, in seconds.")
            .defineInRange("timeoutConnectedSec", 60, 10, 300);

        STUN_SERVERS = b
            .comment("Comma-separated STUN servers for ICE / direct P2P.")
            .define("stunServers", "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302");

        SPEC = b.build();
    }

    public static void bake() {
        allowWithoutAuth = ALLOW_WITHOUT_AUTH.get();
        virtualPort      = VIRTUAL_PORT.get();
        interceptUdp     = INTERCEPT_UDP.get();
        disableOnlineModeOnPublish = DISABLE_ONLINE_MODE.get();
        sameNameAsHost = SAME_NAME_AS_HOST.get();
        if (sameNameAsHost == null) sameNameAsHost = "allow";
        sameNameAsHost = sameNameAsHost.trim().toLowerCase();
        if (!sameNameAsHost.equals("allow") && !sameNameAsHost.equals("kick")) sameNameAsHost = "allow";
        timeoutInitialSec = TIMEOUT_INITIAL_SEC.get();
        timeoutConnectedSec = TIMEOUT_CONNECTED_SEC.get();
        stunServers = STUN_SERVERS.get();
        if (stunServers == null) stunServers = "";
    }

    public static void onLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            bake();
        }
    }

    public static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            bake();
        }
    }
}
