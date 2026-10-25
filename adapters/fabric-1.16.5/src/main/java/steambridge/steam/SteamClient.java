package steambridge.steam;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.network.ClientConnection;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;

import steambridge.SteamBridgeMod;

public class SteamClient extends SteamClientBase<Screen> {
    @Override
    protected SteamRuntime<?, ?> runtime() {
        return SteamManager.getInstance();
    }

    @Override
    protected void activateLoopback(int conn, long remoteSteamID, Screen screen) {
        java.net.SocketAddress proxyAddr = SteamTransport.allocateClientLoopbackEndpoint(conn);
        if (proxyAddr == null) {
            fail(
                    i18n(
                            "steambridge.status.fail_proxy",
                            "Failed to start loopback proxy for connection."));
            return;
        }

        SteamBridgeMod.LOG.info(
                "[SteamClient] Loopback proxy ready: conn={} steamID={} addr={}",
                conn,
                remoteSteamID,
                proxyAddr);

        final java.net.SocketAddress finalProxyAddr = proxyAddr;
        final Screen finalScreen = screen;
        Thread mcConnect =
                new Thread(
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
                                                conn,
                                                remoteSteamID,
                                                finalProxyAddr,
                                                finalScreen,
                                                SteamClient.this);
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
                                    SteamBridgeMod.LOG.info(
                                            "[SteamClient] Loopback mode active - Steam"
                                                    + " transport is ready.");
                                } else {
                                    SteamBridgeMod.LOG.error(
                                            "[SteamClient] Loopback connect to {} failed.",
                                            finalProxyAddr);
                                    fail(
                                            i18n(
                                                            "steambridge.status.fail_proxy",
                                                            "Failed to connect to loopback"
                                                                    + " proxy ")
                                                    + finalProxyAddr);
                                }
                            } catch (Exception e) {
                                SteamBridgeMod.LOG.error(
                                        "[SteamClient] Loopback connect failed: {}",
                                        e.getMessage(),
                                        e);
                                fail("Loopback connect error: " + e.getMessage());
                            }
                        },
                        "SteamBridge-MC-Connect");
        mcConnect.setDaemon(true);
        mcConnect.start();
    }

    @Override
    protected void showFailure(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(
                () -> {
                    if (!isFailureCurrent()) return;
                    if (state != State.FAILED
                            || (SteamManager.getInstance().getActiveClient() != null
                                    && SteamManager.getInstance().getActiveClient() != this))
                        return;
                    Screen current = mc.currentScreen;
                    if (current instanceof DownloadingTerrainScreen
                            || current instanceof ConnectScreen) {
                        mc.openScreen(
                                new DisconnectedScreen(
                                        connectingScreen != null
                                                ? connectingScreen
                                                : new TitleScreen(),
                                        new TranslatableText("connect.failed"),
                                        new LiteralText(msg)));
                    }
                });
    }

    @Override
    protected String i18n(String key, String fallback) {
        try {
            if (net.minecraft.client.resource.language.I18n.hasTranslation(key)) {
                return net.minecraft.client.resource.language.I18n.translate(key);
            }
        } catch (Exception ignored) {

        }
        return fallback;
    }

    private volatile ClientConnection pendingConnection;

    public void setPendingConnection(ClientConnection connection) {
        this.pendingConnection = connection;
    }

    public ClientConnection getPendingConnection() {
        return pendingConnection;
    }

    public void tickPendingConnection() {
        ClientConnection connection = pendingConnection;
        if (connection == null) {
            return;
        }
        if (connection.isOpen()) {
            connection.tick();
        } else {
            connection.handleDisconnection();
            pendingConnection = null;
        }
    }

    @Override
    protected void clearMinecraftConnection() {
        pendingConnection = null;
    }
}
