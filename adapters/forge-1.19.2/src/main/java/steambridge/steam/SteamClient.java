package steambridge.steam;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import steambridge.SteamBridgeMod;

public class SteamClient extends SteamClientBase<Screen> {
    @Override
    protected SteamRuntime<?, ?> runtime() {
        return SteamManager.getInstance();
    }

    @Override
    protected void activateLoopback(int conn, long remoteSteamID, Screen screen) {
        int proxyPort = SteamTransport.allocateClientLoopbackPort(conn);
        if (proxyPort < 0) {
            fail(
                    i18n(
                            "steambridge.status.fail_proxy",
                            "Failed to start loopback proxy for connection."));
            return;
        }

        final int finalProxyPort = proxyPort;
        Minecraft mc2 = Minecraft.getInstance();
        mc2.execute(
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
        Minecraft mc = Minecraft.getInstance();
        mc.execute(
                () -> {
                    if (!isFailureCurrent()) return;
                    if (state != State.FAILED
                            || (SteamManager.getInstance().getActiveClient() != null
                                    && SteamManager.getInstance().getActiveClient() != this))
                        return;
                    Screen current = mc.screen;
                    if (current instanceof ReceivingLevelScreen
                            || current instanceof ConnectScreen) {
                        mc.setScreen(
                                new DisconnectedScreen(
                                        connectingScreen != null
                                                ? connectingScreen
                                                : new TitleScreen(),
                                        Component.translatable("connect.failed"),
                                        Component.literal(msg)));
                    }
                });
    }

    @Override
    protected String i18n(String key, String fallback) {
        try {
            if (net.minecraft.client.resources.language.I18n.exists(key)) {
                return net.minecraft.client.resources.language.I18n.get(key);
            }
        } catch (Exception ignored) {

        }
        return fallback;
    }
}
