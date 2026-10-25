package steambridge.steam;

import steambridge.SteamBridgeMod;

public final class SteamManager extends SteamRuntime<SteamClient, SteamServer> {
    private static final SteamManager INSTANCE = new SteamManager();

    public static SteamManager getInstance() {
        return INSTANCE;
    }

    private SteamManager() {}

    @Override
    protected void clearProfiles() {
        SteamSocial.ProfileCache.get().clear();
    }

    @Override
    protected void invalidateProfile(long steamId, boolean avatar) {
        if (avatar) SteamSocial.ProfileCache.get().invalidateAvatar(steamId);
        else SteamSocial.ProfileCache.get().invalidateName(steamId);
    }

    @Override
    public void handleSteamShutdown() {
        final steambridge.core.NativeCallGate generation = jniGate;
        SteamBridgeMod.LOG.warn(
                "[SteamManager] Steam shutdown signal received - scheduling graceful disconnect.");

        running.set(false);
        signalReceiveWake();

        try {
            net.minecraft.client.MinecraftClient mc =
                    net.minecraft.client.MinecraftClient.getInstance();
            mc.execute(
                    () -> {
                        if (jniGate != generation) return;
                        try {
                            boolean wasClient = activeClient != null;
                            boolean wasHost = activeServer != null && activeServer.isRunning();

                            SteamClient client = activeClient;
                            if (client != null) client.disconnect();
                            SteamServer server = activeServer;
                            if (server != null) server.stop();

                            if (wasClient && mc.world != null) {

                                mc.disconnect();
                                mc.openScreen(
                                        new net.minecraft.client.gui.screen.DisconnectedScreen(
                                                new net.minecraft.client.gui.screen.TitleScreen(),
                                                new net.minecraft.text.TranslatableText(
                                                        "disconnect.lost"),
                                                new net.minecraft.text.TranslatableText(
                                                        "steambridge.error.steam_shutdown")));
                            } else if (wasHost && mc.player != null) {

                                mc.player.sendMessage(
                                        new net.minecraft.text.TranslatableText(
                                                        "steambridge.error.host_steam_shutdown")
                                                .formatted(net.minecraft.util.Formatting.RED),
                                        false);
                            }
                        } catch (Exception e) {
                            SteamBridgeMod.LOG.error(
                                    "[SteamManager] Error in Steam shutdown handler: {}",
                                    e.getMessage(),
                                    e);
                        } finally {
                            if (jniGate == generation) shutdown();
                        }
                    });
        } catch (Exception e) {
            SteamBridgeMod.LOG.error(
                    "[SteamManager] Failed to schedule Steam shutdown handler: {}",
                    e.getMessage(),
                    e);
            if (jniGate == generation) shutdown();
        }
    }
}
