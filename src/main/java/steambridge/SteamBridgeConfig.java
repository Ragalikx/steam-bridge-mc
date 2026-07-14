/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client config (NeoForge 1.21.1, {@link ModConfigSpec}).
 *
 * <p>The {@code virtualPort} / {@code allowWithoutAuth} static fields are kept as plain
 * mirrors of the spec values so the rest of the codebase can read them directly.
 * They are refreshed from the spec on every config load/reload via {@link #onLoad}/{@link #onReload}.</p>
 */
public final class SteamBridgeConfig {

    private SteamBridgeConfig() {}

    // -- Live mirror values (read throughout the mod) --------------------------
    // NOTE: the Steam App ID is intentionally NOT configurable. It is hardcoded to 480
    // (Spacewar) in SteamAppIdHelper. Letting users point it at a real game's App ID,
    // especially one with an anti-cheat (VAC/EAC), would get their account banned.
    public static boolean allowWithoutAuth = true;
    public static int     virtualPort      = 0;
    /**
     * Whether to install a JVM-wide {@link java.net.DatagramSocketImplFactory} that intercepts
     * UDP sockets so voice-chat mods (Simple Voice Chat, Plasmo Voice, etc.) can be tunnelled
     * through Steam alongside Minecraft traffic.
     *
     * <p>Setting this to {@code false} disables the interception: voice mods will stop working
     * through Steam Bridge, but no DatagramSocket factory will be installed and no UDP port will
     * be hijacked. The setting takes effect only at launch; changing it mid-session has no effect
     * because the factory is a one-time JVM-lifetime operation.</p>
     */
    public static boolean interceptUdp    = true;

    // -- Spec definition -------------------------------------------------------
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ALLOW_WITHOUT_AUTH;
    private static final ModConfigSpec.IntValue     VIRTUAL_PORT;
    private static final ModConfigSpec.BooleanValue INTERCEPT_UDP;

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
            .comment("Install a JVM-wide DatagramSocket factory so that voice-chat mods "
                   + "(Simple Voice Chat, Plasmo Voice, etc.) work through Steam Bridge. "
                   + "If disabled, voice chat will not be tunnelled but no UDP interception occurs. "
                   + "Takes effect only on launch; cannot be toggled at runtime.")
            .define("interceptUdp", true);

        SPEC = b.build();
    }

    public static void bake() {
        allowWithoutAuth = ALLOW_WITHOUT_AUTH.get();
        virtualPort      = VIRTUAL_PORT.get();
        interceptUdp     = INTERCEPT_UDP.get();
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
