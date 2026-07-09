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

    // -- Spec definition -------------------------------------------------------
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue ALLOW_WITHOUT_AUTH;
    private static final ForgeConfigSpec.IntValue     VIRTUAL_PORT;

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

        SPEC = b.build();
    }

    /** Refreshes the mirror fields from the spec. Call after the config is loaded/reloaded. */
    public static void bake() {
        allowWithoutAuth = ALLOW_WITHOUT_AUTH.get();
        virtualPort      = VIRTUAL_PORT.get();
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
