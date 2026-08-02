/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * Client config (NeoForge 1.20.1, {@link ForgeConfigSpec}).
 *
 * <p>The {@code virtualPort} / {@code allowWithoutAuth} static fields are kept as plain
 * mirrors of the spec values so the rest of the codebase can read them directly, exactly
 * as it did under the 1.12.2 {@code @Config} system. They are refreshed from the spec on
 * every config load/reload via {@link #onLoad}/{@link #onReload}.</p>
 */
public final class SteamBridgeConfig {

    private SteamBridgeConfig() {}

    // -- Live mirror values (read throughout the mod) --------------------------
    // NOTE: the Steam App ID is intentionally NOT configurable. It is hardcoded to 480
    // (Spacewar) in SteamAppIdHelper. Letting users point it at a real game's App ID -
    // especially one with an anti-cheat (VAC/EAC) - would get their account banned.
    public static boolean allowWithoutAuth = true;
    public static int     virtualPort      = 0;
    /**
     * Whether to install a JVM-wide {@link java.net.DatagramSocketImplFactory} that intercepts
     * UDP sockets so voice-chat mods (Simple Voice Chat, Plasmo Voice, etc.) can be tunnelled
     * through Steam alongside Minecraft traffic.
     *
     * <p>Setting this to {@code false} disables the interception - voice mods will stop working
     * through Steam Bridge, but no DatagramSocket factory will be installed and no UDP port will
     * be hijacked. The setting takes effect only at launch; changing it mid-session has no effect
     * because the factory is a one-time JVM-lifetime operation.</p>
     */
    public static boolean interceptUdp    = true;
    /**
     * Whether the mod remembers and restores the last used game mode / allow-commands
     * setting for the "Open for Steam" screen, per world. Relies on reflection into vanilla's
     * ShareToLanScreen fields and widgets, so a conflict with another mod poking the same
     * screen is possible in theory. Turning this off makes the screen behave exactly like
     * plain vanilla, no memory between sessions.
     */
    public static boolean rememberNetworkSettings = true;

    // -- Spec definition -------------------------------------------------------
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue ALLOW_WITHOUT_AUTH;
    private static final ForgeConfigSpec.IntValue     VIRTUAL_PORT;
    private static final ForgeConfigSpec.BooleanValue INTERCEPT_UDP;
    private static final ForgeConfigSpec.BooleanValue REMEMBER_NETWORK_SETTINGS;

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

        REMEMBER_NETWORK_SETTINGS = b
            .comment("Remember the last game mode / allow-commands choice on the Open for Steam "
                   + "screen, per world. Uses reflection into vanilla's own screen fields, so if "
                   + "another mod also messes with that screen and something looks off, turn "
                   + "this off.")
            .define("rememberNetworkSettings", true);

        SPEC = b.build();
    }

    /** Refreshes the mirror fields from the spec. Call after the config is loaded/reloaded. */
    public static void bake() {
        allowWithoutAuth = ALLOW_WITHOUT_AUTH.get();
        virtualPort      = VIRTUAL_PORT.get();
        interceptUdp     = INTERCEPT_UDP.get();
        rememberNetworkSettings = REMEMBER_NETWORK_SETTINGS.get();
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
