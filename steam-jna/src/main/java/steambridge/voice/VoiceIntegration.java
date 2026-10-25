package steambridge.voice;

import steambridge.SteamBridgeConfig;
import steambridge.core.VoiceTransport;

public final class VoiceIntegration {
    private static volatile VoiceTransport transport;

    private VoiceIntegration() {}

    public static void install(VoiceTransport value) {
        transport = value;
        if (value != null && SteamBridgeConfig.settings().interceptUdp) PlasmoIntegration.install();
    }

    public static VoiceTransport transport() {
        return SteamBridgeConfig.settings().interceptUdp ? transport : null;
    }

    public static boolean needsLegacyFactory() {
        if (!SteamBridgeConfig.settings().interceptUdp) return false;
        try {
            Class.forName(
                    "de.maxhenkel.voicechat.Voicechat",
                    false,
                    VoiceIntegration.class.getClassLoader());
            try {
                Class.forName(
                        "de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent",
                        false,
                        VoiceIntegration.class.getClassLoader());
                return false;
            } catch (ClassNotFoundException olderApi) {
                return true;
            }
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }
}
