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
    protected boolean voiceSupported() {
        return false;
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
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            steambridge.ClientTasks.run(
                    () -> {
                        if (jniGate != generation) return;
                        try {
                            boolean wasClient = activeClient != null;
                            boolean wasHost = activeServer != null && activeServer.isRunning();

                            SteamClient client = activeClient;
                            if (client != null) client.disconnect();
                            SteamServer server = activeServer;
                            if (server != null) server.stop();

                            if (wasClient && mc.theWorld != null) {

                                mc.loadWorld(null);
                                mc.displayGuiScreen(
                                        new net.minecraft.client.gui.GuiDisconnected(
                                                new net.minecraft.client.gui.GuiMainMenu(),
                                                "disconnect.lost",
                                                new net.minecraft.util.ChatComponentTranslation(
                                                        "steambridge.error.steam_shutdown")));
                            } else if (wasHost && mc.thePlayer != null) {

                                mc.thePlayer.addChatMessage(
                                        new net.minecraft.util.ChatComponentTranslation(
                                                        "steambridge.error.host_steam_shutdown")
                                                .setChatStyle(
                                                        new net.minecraft.util.ChatStyle()
                                                                .setColor(
                                                                        net.minecraft.util
                                                                                .EnumChatFormatting
                                                                                .RED)));
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
