package steambridge.steam;

import steambridge.SteamBridgeMod;
import steambridge.core.LogLimiter;
import steambridge.core.ProblemLog;

public final class SteamManager extends SteamRuntime<SteamClient, SteamServer> {
    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

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
        if ((activeClient != null && activeClient.isAlive())
                || (activeServer != null && activeServer.isRunning())) {
            ProblemLog.warn(
                    SteamBridgeMod.LOG,
                    LOG_LIMITER,
                    "SteamManager-warn-1",
                    "[SteamManager] Steam stopped during an active session; disconnecting.");
        } else {
            SteamBridgeMod.LOG.debug("[SteamManager] Steam shutdown signal received.");
        }

        running.set(false);
        signalReceiveWake();

        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
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

                            if (wasClient && mc.level != null) {

                                mc.setScreen(
                                        new net.minecraft.client.gui.screens.DisconnectedScreen(
                                                new net.minecraft.client.gui.screens.TitleScreen(),
                                                net.minecraft.network.chat.Component.translatable(
                                                        "disconnect.lost"),
                                                net.minecraft.network.chat.Component.translatable(
                                                        "steambridge.error.steam_shutdown")));
                            } else if (wasHost && mc.player != null) {

                                mc.player.sendSystemMessage(
                                        net.minecraft.network.chat.Component.translatable(
                                                        "steambridge.error.host_steam_shutdown")
                                                .withStyle(net.minecraft.ChatFormatting.RED));
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
