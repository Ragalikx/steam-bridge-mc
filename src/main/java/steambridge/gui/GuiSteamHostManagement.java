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
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public class GuiSteamHostManagement extends Screen {
    private final Screen parent;
    private SteamServer server;
    private int snapshotCount = -1;

    private static final long SNAPSHOT_REFRESH_INTERVAL_MS = 1000L;
    private List<SteamServer.PlayerSnapshot> cachedSnaps = java.util.Collections.emptyList();
    private long lastSnapRefreshMs = 0L;

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
    public boolean isPauseScreen() {
        return false;
    }

    private void rebuild() {
        this.init(this.minecraft, this.width, this.height);
    }

    @Override
    protected void init() {
        this.server = SteamManager.getInstance().getActiveServer();

        this.addRenderableWidget(GuiButtons.createCentered(this.font, this.width / 2, this.height - 30,
                Component.translatable("gui.back"),
                b -> this.minecraft.setScreen(parent), 100, this.width - 20));

        // Right edge margin 8 so long labels (ru "Список заблокированных") stay on screen.
        this.addRenderableWidget(GuiButtons.createRightAligned(this.font, this.width - 8, 10,
                Component.translatable("steambridge.gui.banned"),
                b -> this.minecraft.setScreen(new GuiSteamBanList(this, server)),
                40, this.width - 16));

        if (server != null && server.isRunning()) {
            List<SteamServer.PlayerSnapshot> snaps = snapshots();
            snapshotCount = snaps.size();
            int yStart = 40;
            Component kickMsg = Component.translatable("steambridge.gui.kick");
            Component banMsg  = Component.translatable("steambridge.gui.ban");
            int gap = 4;
            for (int i = 0; i < snaps.size(); i++) {
                int y = yStart + (i * 25);
                final long steamId = snaps.get(i).getSteamId();
                Button ban = GuiButtons.createRightAligned(this.font, this.width - 8, y, banMsg,
                        b -> { server.banPlayer(steamId); rebuild(); }, 30, 120);
                Button kick = GuiButtons.createRightAligned(this.font, ban.x - gap, y, kickMsg,
                        b -> { server.kickPlayer(steamId); rebuild(); }, 30, 120);
                this.addRenderableWidget(kick);
                this.addRenderableWidget(ban);
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (server != null && server.isRunning()) {
            List<SteamServer.PlayerSnapshot> snaps = snapshots();
            if (snaps.size() != snapshotCount) {
                rebuild();
            }
        }
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(poseStack);
        drawCenteredString(poseStack, this.font, I18n.get("steambridge.gui.management"), this.width / 2, 10, 16777215);

        if (server != null && server.isRunning()) {
            List<SteamServer.PlayerSnapshot> snaps = snapshots();
            int yStart = 40;

            if (snaps.isEmpty()) {
                drawCenteredString(poseStack, this.font, I18n.get("steambridge.gui.no_players"), this.width / 2, yStart + 10, 0xAAAAAA);
            } else {
                for (int i = 0; i < snaps.size(); i++) {
                    SteamServer.PlayerSnapshot snap = snaps.get(i);
                    int y = yStart + (i * 25);

                    String avatar = SteamSocial.ProfileCache.get().getAvatarTexture(snap.getSteamId());
                    if (avatar != null && !avatar.isEmpty()) {
                        try {
                            ResourceLocation loc = new ResourceLocation(avatar);
                            RenderSystem.setShader(GameRenderer::getPositionTexShader);
                            RenderSystem.setShaderTexture(0, loc);
                            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                            blit(poseStack, this.width / 2 - 170, y + 2, 0, 0, 16, 16, 16, 16);
                        } catch (Exception ignored) {}
                    }

                    SteamConnectionStatus status = snap.getConnectionStatus();
                    String pingStr  = (status != null && status.getPingMs() >= 0) ? status.getPingMs() + "ms" : "~";
                    String connType = (status != null && status.isConnectionActive())
                            ? (status.isUsingRelay()
                                ? I18n.get("steambridge.gui.conn_relay")
                                : I18n.get("steambridge.gui.conn_p2p"))
                            : "?";
                    drawString(poseStack, this.font,
                            snap.getSteamName() + " (" + snap.getMinecraftName() + ") "
                            + pingStr + " [" + connType + "]",
                            this.width / 2 - 150, y + 6, 16777215);
                }
            }
        } else {
            drawCenteredString(poseStack, this.font, I18n.get("steambridge.gui.not_running"), this.width / 2, 50, 16733525);
        }

        super.render(poseStack, mouseX, mouseY, partialTicks);
    }
}
