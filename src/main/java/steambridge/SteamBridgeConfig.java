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

    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue ALLOW_WITHOUT_AUTH;
    private static final ForgeConfigSpec.IntValue     VIRTUAL_PORT;
    private static final ForgeConfigSpec.BooleanValue INTERCEPT_UDP;

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

        SPEC = b.build();
    }

    public static void bake() {
        allowWithoutAuth = ALLOW_WITHOUT_AUTH.get();
        virtualPort      = VIRTUAL_PORT.get();
        interceptUdp     = INTERCEPT_UDP.get();
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
