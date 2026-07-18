/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.steam.SteamConnectionStatus;
import steambridge.steam.SteamManager;
import steambridge.steam.SteamServer;
import steambridge.steam.SteamSocial;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public class GuiSteamHostManagement extends Screen {
    private final Screen parent;
    private SteamServer server;
    private int snapshotCount = -1;

    /** Live status (incl. per-player ping) is a native Steam call; poll it at most this often. */
    private static final long SNAPSHOT_REFRESH_INTERVAL_MS = 1000L;
    private List<SteamServer.PlayerSnapshot> cachedSnaps = java.util.Collections.emptyList();
    private long lastSnapRefreshMs = 0L;

    /**
     * Returns player snapshots, refreshed at most once per second. render() runs every
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

    public GuiSteamHostManagement(Screen parent) {
        super(Component.translatable("steambridge.gui.management"));
        this.parent = parent;
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {}

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        this.server = SteamManager.getInstance().getActiveServer();

        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                b -> this.minecraft.setScreen(parent))
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("steambridge.gui.banned"),
                b -> this.minecraft.setScreen(new GuiSteamBanList(this, server)))
                .bounds(this.width - 110, 10, 100, 20).build());

        if (server != null && server.isRunning()) {
            List<SteamServer.PlayerSnapshot> snaps = snapshots();
            snapshotCount = snaps.size();
            int yStart = 40;
            for (int i = 0; i < snaps.size(); i++) {
                int y = yStart + (i * 25);
                final long steamId = snaps.get(i).getSteamId();
                this.addRenderableWidget(Button.builder(Component.translatable("steambridge.gui.kick"),
                        b -> { server.kickPlayer(steamId); this.rebuildWidgets(); })
                        .bounds(this.width / 2 + 50, y, 40, 20).build());
                this.addRenderableWidget(Button.builder(Component.translatable("steambridge.gui.ban"),
                        b -> { server.banPlayer(steamId); this.rebuildWidgets(); })
                        .bounds(this.width / 2 + 95, y, 40, 20).build());
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (server != null && server.isRunning()) {
            List<SteamServer.PlayerSnapshot> snaps = snapshots();
            if (snaps.size() != snapshotCount) {
                this.rebuildWidgets();
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        super.renderBackground(g, mouseX, mouseY, partialTicks);
        g.drawCenteredString(this.font, I18n.get("steambridge.gui.management"), this.width / 2, 10, 16777215);

        if (server != null && server.isRunning()) {
            List<SteamServer.PlayerSnapshot> snaps = snapshots();
            int yStart = 40;

            if (snaps.isEmpty()) {
                g.drawCenteredString(this.font, I18n.get("steambridge.gui.no_players"), this.width / 2, yStart + 10, 0xAAAAAA);
            } else {
                for (int i = 0; i < snaps.size(); i++) {
                    SteamServer.PlayerSnapshot snap = snaps.get(i);
                    int y = yStart + (i * 25);

                    String avatar = SteamSocial.ProfileCache.get().getAvatarTexture(snap.getSteamId());
                    if (avatar != null && !avatar.isEmpty()) {
                        g.blit(ResourceLocation.parse(avatar), this.width / 2 - 170, y + 2, 0.0F, 0.0F, 16, 16, 16, 16);
                    }

                    SteamConnectionStatus status = snap.getConnectionStatus();
                    String pingStr  = (status != null && status.getPingMs() >= 0) ? status.getPingMs() + "ms" : "~";
                    String connType = (status != null && status.isConnectionActive())
                            ? (status.isUsingRelay()
                                ? I18n.get("steambridge.gui.conn_relay")
                                : I18n.get("steambridge.gui.conn_p2p"))
                            : "?";
                    g.drawString(this.font,
                            snap.getSteamName() + " (" + snap.getMinecraftName() + ") "
                            + pingStr + " [" + connType + "]",
                            this.width / 2 - 150, y + 6, 16777215);
                }
            }
        } else {
            g.drawCenteredString(this.font, I18n.get("steambridge.gui.not_running"), this.width / 2, 50, 16733525);
        }

        super.render(g, mouseX, mouseY, partialTicks);
    }
}
