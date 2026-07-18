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
            .comment("DatagramSocket factory for voice mods (SVC, Plasmo Voice). "
                   + "Launch-only; cannot toggle at runtime.")
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
