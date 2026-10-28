package steambridge.steam;

import steambridge.SteamBridgeMod;
import steambridge.core.LogLimiter;
import steambridge.core.ProblemLog;

public class SteamClient extends SteamClientBase<net.minecraft.client.gui.GuiScreen> {
    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

    @Override
    protected SteamRuntime<?, ?> runtime() {
        return SteamManager.getInstance();
    }

    @Override
    protected void activateLoopback(
            int conn, long remoteSteamID, net.minecraft.client.gui.GuiScreen screen) {
        int proxyPort = SteamTransport.allocateClientLoopbackPort(conn);
        if (proxyPort < 0) {
            fail(
                    i18n(
                            "steambridge.status.fail_proxy",
                            "Failed to start loopback proxy for connection."));
            return;
        }

        final int finalProxyPort = proxyPort;
        net.minecraft.client.Minecraft mc2 = net.minecraft.client.Minecraft.getMinecraft();
        steambridge.ClientTasks.run(
                () -> {
                    if (!isAttemptActive()) {
                        SteamManager.getInstance()
                                .closeConnection(
                                        conn,
                                        SteamSocketsApi.APP_CLOSE_NORMAL,
                                        "Cancelled attempt");
                        return;
                    }

                    try {
                        net.minecraft.network.NetworkManager nm =
                                SteamTransport.connectClientToLoopback(
                                        conn, remoteSteamID, finalProxyPort, screen);
                        if (nm != null) {
                            networkManager = nm;
                            if (!isAttemptActive()) {
                                SteamManager.getInstance()
                                        .closeConnection(
                                                conn,
                                                SteamSocketsApi.APP_CLOSE_NORMAL,
                                                "Cancelled attempt");
                                return;
                            }
                            markSteamReady();
                            SteamBridgeMod.LOG.debug(
                                    "[SteamClient] Loopback mode active - Steam transport is"
                                            + " ready.");
                        } else {
                            SteamBridgeMod.LOG.error(
                                    "[SteamClient] Loopback connect to port {} failed.",
                                    finalProxyPort);
                            fail(
                                    i18n(
                                                    "steambridge.status.fail_proxy",
                                                    "Failed to connect to loopback proxy port ")
                                            + finalProxyPort);
                        }
                    } catch (Exception e) {
                        SteamBridgeMod.LOG.error(
                                "[SteamClient] Loopback connect failed: {}", e.getMessage(), e);
                        fail("Loopback connect error: " + e.getMessage());
                    }
                });

        SteamBridgeMod.LOG.debug(
                "[SteamClient] Loopback proxy ready: conn={} steamID={}", conn, remoteSteamID);
    }

    @Override
    protected void showFailure(String msg) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        steambridge.ClientTasks.run(
                () -> {
                    if (!isFailureCurrent()) return;
                    net.minecraft.client.gui.GuiScreen current = mc.currentScreen;
                    if (current instanceof net.minecraft.client.gui.GuiDownloadTerrain
                            || current instanceof net.minecraft.client.multiplayer.GuiConnecting) {
                        mc.displayGuiScreen(
                                new net.minecraft.client.gui.GuiDisconnected(
                                        connectingScreen != null
                                                ? connectingScreen
                                                : new net.minecraft.client.gui.GuiMainMenu(),
                                        "connect.failed",
                                        new net.minecraft.util.ChatComponentText(msg)));
                    }
                });
    }

    @Override
    protected String i18n(String key, String fallback) {
        try {

            String t = net.minecraft.client.resources.I18n.format(key);
            if (t != null && !t.isEmpty() && !t.equals(key)) {
                return t;
            }
        } catch (Exception ignored) {

        }
        return fallback;
    }

    private volatile net.minecraft.network.NetworkManager networkManager;

    @Override
    protected void clearMinecraftConnection() {
        networkManager = null;
    }

    public void tickNetwork() {
        net.minecraft.network.NetworkManager nm = networkManager;
        if (nm == null) {
            return;
        }
        try {
            if (nm.isChannelOpen()) {
                nm.processReceivedPackets();
            } else if (nm.getExitMessage() != null && alive.get()) {

                ProblemLog.warn(
                        SteamBridgeMod.LOG,
                        LOG_LIMITER,
                        "SteamClient-warn-1",
                        "[SteamClient] NetworkManager closed: {}",
                        nm.getExitMessage().getUnformattedText());
            }
        } catch (Throwable t) {
            ProblemLog.warn(
                    SteamBridgeMod.LOG,
                    LOG_LIMITER,
                    "SteamClient-warn-2",
                    "[SteamClient] tickNetwork error: {}",
                    t.toString());
        }
    }

    public net.minecraft.network.NetworkManager getNetworkManager() {
        return networkManager;
    }
}
