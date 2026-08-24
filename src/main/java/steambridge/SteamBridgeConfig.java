/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Client config (Forge 1.16.5, {@link ForgeConfigSpec}).
 *
 * <p>The {@code virtualPort} / {@code allowWithoutAuth} static fields are plain mirrors of
 * the spec values so the rest of the codebase can read them directly. They are refreshed
 * from the spec on every config load/reload via {@link #onLoad}/{@link #onReload}.</p>
 */
public final class SteamBridgeConfig {

    private SteamBridgeConfig() {}

    public static boolean allowWithoutAuth = true;
    public static int     virtualPort      = 0;
    /**
     * Whether to install a JVM-wide {@link java.net.DatagramSocketImplFactory} that intercepts
     * UDP sockets so voice-chat mods can be tunnelled through Steam alongside Minecraft traffic.
     */
    public static boolean interceptUdp    = true;
    /**
     * Whether the mod remembers and restores the last used game mode / allow-commands
     * setting for the "Open for Steam" screen, per world. Relies on reflection into vanilla's
     * ShareToLanScreen fields and widgets, so a conflict with another mod poking the same
     * screen is possible in theory. Turning this off makes the screen behave exactly like
     * plain vanilla, no memory between sessions.
     */
    public static boolean disableOnlineModeOnPublish = true;
    public static String  sameNameAsHost = "allow";
    public static int     timeoutInitialSec = 30;
    public static int     timeoutConnectedSec = 60;
    public static String  stunServers = "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302";

    public static boolean kickOnSameNameAsHost() {
        return "kick".equalsIgnoreCase(sameNameAsHost);
    }

    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue ALLOW_WITHOUT_AUTH;
    private static final ForgeConfigSpec.IntValue     VIRTUAL_PORT;
    private static final ForgeConfigSpec.BooleanValue INTERCEPT_UDP;
    private static final ForgeConfigSpec.BooleanValue DISABLE_ONLINE_MODE;
    private static final ForgeConfigSpec.ConfigValue<String> SAME_NAME_AS_HOST;
    private static final ForgeConfigSpec.IntValue TIMEOUT_INITIAL_SEC;
    private static final ForgeConfigSpec.IntValue TIMEOUT_CONNECTED_SEC;
    private static final ForgeConfigSpec.ConfigValue<String> STUN_SERVERS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        ALLOW_WITHOUT_AUTH = b
            .comment("Allow connections without validating Steam Auth Ticket. "
                   + "False is more secure but might affect some NAT types.")
            .define("allowWithoutAuth", true);

        VIRTUAL_PORT = b
            .comment("Virtual port for Steam network. 0 is default. "
                   + "Change only if conflicting with other mods.")
            .defineInRange("virtualPort", 0, 0, 65535);

        INTERCEPT_UDP = b
            .comment("Install a JVM-wide DatagramSocket factory so that voice-chat mods "
                   + "(Simple Voice Chat, Plasmo Voice, etc.) work through Steam Bridge. "
                   + "If disabled, voice chat will not be tunnelled but no UDP interception occurs. "
                   + "Takes effect only on launch - cannot be toggled at runtime.")
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

    public static void onLoad(final ModConfig.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            bake();
        }
    }

    public static void onReload(final ModConfig.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            bake();
        }
    }
}
