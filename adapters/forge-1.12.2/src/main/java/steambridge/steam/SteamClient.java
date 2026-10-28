package steambridge.steam;

import steambridge.SteamBridgeMod;

public class SteamClient extends SteamClientBase<net.minecraft.client.gui.GuiScreen> {
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
        mc2.addScheduledTask(
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
                        boolean ok2 =
                                SteamTransport.connectClientToLoopback(
                                        conn, remoteSteamID, finalProxyPort, screen);
                        if (ok2) {
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
        mc.addScheduledTask(
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
                                        new net.minecraft.util.text.TextComponentString(msg)));
                    }
                });
    }

    @Override
    protected String i18n(String key, String fallback) {
        try {
            if (net.minecraft.client.resources.I18n.hasKey(key)) {
                return net.minecraft.client.resources.I18n.format(key);
            }
        } catch (Exception ignored) {

        }
        return fallback;
    }
}
