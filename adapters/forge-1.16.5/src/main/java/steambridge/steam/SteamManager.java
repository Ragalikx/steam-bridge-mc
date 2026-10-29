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
    protected void handleGameInvite(long host) {
        steambridge.gui.VanillaGuiIntegration.acceptSteamInvite(host);
    }

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
        ProblemLog.warn(
                SteamBridgeMod.LOG,
                LOG_LIMITER,
                "SteamManager-warn-1",
                "[SteamManager] Steam shutdown signal received - scheduling graceful disconnect.");

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

                                mc.clearLevel();
                                mc.setScreen(
                                        new net.minecraft.client.gui.screen.DisconnectedScreen(
                                                new net.minecraft.client.gui.screen
                                                        .MainMenuScreen(),
                                                new net.minecraft.util.text
                                                        .TranslationTextComponent(
                                                        "disconnect.lost"),
                                                new net.minecraft.util.text
                                                        .TranslationTextComponent(
                                                        "steambridge.error.steam_shutdown")));
                            } else if (wasHost && mc.player != null) {

                                mc.player.displayClientMessage(
                                        new net.minecraft.util.text.TranslationTextComponent(
                                                        "steambridge.error.host_steam_shutdown")
                                                .withStyle(
                                                        net.minecraft.util.text.TextFormatting.RED),
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
