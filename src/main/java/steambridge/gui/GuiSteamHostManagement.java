/*
 * Copyright (c) 2019-2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.steam.SteamConnectionStatus;
import steambridge.steam.SteamManager;
import steambridge.steam.SteamServer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import java.io.IOException;
import java.util.List;

public class GuiSteamHostManagement extends GuiScreen {
    private final GuiScreen parent;
    private SteamServer server;
    private int snapshotCount = -1;
    private static final int BUTTON_BACK = 0;
    private static final int BUTTON_BAN_LIST = 1;

    /** Live status (incl. per-player ping) is a native Steam call; poll it at most this often. */
    private static final long SNAPSHOT_REFRESH_INTERVAL_MS = 1000L;
    private List<SteamServer.PlayerSnapshot> cachedSnaps = java.util.Collections.emptyList();
    private long lastSnapRefreshMs = 0L;

    /**
     * Returns player snapshots, refreshed at most once per second. drawScreen runs every
     * frame, so calling the server (and its native ping query) directly there would fire
     * dozens of native calls per second and make the ping value jitter every frame. All
     * on-screen consumers share this cache so button indices stay consistent with the roster.
     */
    private List<SteamServer.PlayerSnapshot> snapshots() {
        if (server == null || !server.isRunning()) {
            cachedSnaps = java.util.Collections.emptyList();
            return cachedSnaps;
        }
        long now = System.currentTimeMillis();
        if (now - lastSnapRefreshMs >= SNAPSHOT_REFRESH_INTERVAL_MS) {
            cachedSnaps = server.getPlayerSnapshots();
            lastSnapRefreshMs = now;
        }
        return cachedSnaps;
    }

    public GuiSteamHostManagement(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        this.server = SteamManager.getInstance().getActiveServer();
        this.buttonList.clear();
        this.buttonList.add(new GuiButton(BUTTON_BACK, this.width / 2 - 100, this.height - 30, 200, 20, net.minecraft.client.resources.I18n.format("gui.back")));
        
        String bannedText = net.minecraft.client.resources.I18n.hasKey("steambridge.gui.banned") ? 
                            net.minecraft.client.resources.I18n.format("steambridge.gui.banned") : "Ban List";
        this.buttonList.add(new GuiButton(BUTTON_BAN_LIST, this.width - 110, 10, 100, 20, bannedText));

        updatePlayerButtons();
    }

    private void updatePlayerButtons() {
        this.buttonList.removeIf(b -> b.id >= 100);
        if (server != null && server.isRunning()) {
            List<SteamServer.PlayerSnapshot> snaps = snapshots();
            snapshotCount = snaps.size();
            int yStart = 40;
            for (int i = 0; i < snaps.size(); i++) {
                int y = yStart + (i * 25);
                this.buttonList.add(new GuiButton(100 + i, this.width / 2 + 50, y, 40, 20, net.minecraft.client.resources.I18n.format("steambridge.gui.kick")));
                this.buttonList.add(new GuiButton(200 + i, this.width / 2 + 95, y, 40, 20, net.minecraft.client.resources.I18n.format("steambridge.gui.ban")));
            }
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (server != null && server.isRunning()) {
            List<SteamServer.PlayerSnapshot> snaps = snapshots();
            if (snaps.size() != snapshotCount) {
                updatePlayerButtons();
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == BUTTON_BACK) {
            this.mc.displayGuiScreen(parent);
        } else if (button.id == BUTTON_BAN_LIST) {
            this.mc.displayGuiScreen(new GuiSteamBanList(this, server));
        } else if (button.id >= 100 && button.id < 200) {
            // Kick - resolve against the same cached roster the buttons were built from.
            int idx = button.id - 100;
            List<SteamServer.PlayerSnapshot> snaps = snapshots();
            if (idx < snaps.size()) {
                server.kickPlayer(snaps.get(idx).getSteamId());
            }
        } else if (button.id >= 200 && button.id < 300) {
            // Ban - resolve against the same cached roster the buttons were built from.
            int idx = button.id - 200;
            List<SteamServer.PlayerSnapshot> snaps = snapshots();
            if (idx < snaps.size()) {
                server.banPlayer(snaps.get(idx).getSteamId());
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, net.minecraft.client.resources.I18n.format("steambridge.gui.management"), this.width / 2, 10, 16777215);

        if (server != null && server.isRunning()) {
            List<SteamServer.PlayerSnapshot> snaps = snapshots();
            int yStart = 40;

            if (snaps.isEmpty()) {
                this.drawCenteredString(this.fontRenderer, net.minecraft.client.resources.I18n.format("steambridge.gui.no_players"), this.width / 2, yStart + 10, 0xAAAAAA);
            } else {
                for (int i = 0; i < snaps.size(); i++) {
                    SteamServer.PlayerSnapshot snap = snaps.get(i);
                    int y = yStart + (i * 25);
                    
                    String avatar = steambridge.steam.SteamSocial.ProfileCache.get().getAvatarTexture(snap.getSteamId());
                    if (avatar != null && !avatar.isEmpty()) {
                        this.mc.getTextureManager().bindTexture(new net.minecraft.util.ResourceLocation(avatar));
                        net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                        net.minecraft.client.gui.Gui.drawModalRectWithCustomSizedTexture(this.width / 2 - 170, y + 2, 0, 0, 16, 16, 16, 16);
                    }
                    
                    SteamConnectionStatus status = snap.getConnectionStatus();
                    String pingStr  = (status != null && status.getPingMs() >= 0) ? status.getPingMs() + "ms" : "~";
                    String connType = (status != null && status.isConnectionActive())
                            ? (status.isUsingRelay() ? "relay" : "p2p")
                            : "?";
                    this.drawString(this.fontRenderer,
                            snap.getSteamName() + " (" + snap.getMinecraftName() + ") "
                            + pingStr + " [" + connType + "]",
                            this.width / 2 - 150, y + 6, 16777215);
                }
            }
        } else {
            this.drawCenteredString(this.fontRenderer, net.minecraft.client.resources.I18n.format("steambridge.gui.not_running"), this.width / 2, 50, 16733525);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}

