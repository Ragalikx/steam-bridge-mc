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
import net.minecraft.client.util.math.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.util.Identifier;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;

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
        super(new TranslatableText("steambridge.gui.management"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void rebuild() {
        this.init(this.client, this.width, this.height);
    }

    @Override
    protected void init() {
        this.server = SteamManager.getInstance().getActiveServer();

        this.addButton(GuiButtons.createCentered(this.textRenderer, this.width / 2, this.height - 30,
                new TranslatableText("gui.back"),
                b -> this.client.openScreen(parent), 100, this.width - 20));

        this.addButton(GuiButtons.createRightAligned(this.textRenderer, this.width - 8, 10,
                new TranslatableText("steambridge.gui.banned"),
                b -> this.client.openScreen(new GuiSteamBanList(this, server)),
                40, this.width - 16));

        if (server != null && server.isRunning()) {
            List<SteamServer.PlayerSnapshot> snaps = snapshots();
            snapshotCount = snaps.size();
            int yStart = 40;
            Text kickMsg = new TranslatableText("steambridge.gui.kick");
            Text banMsg  = new TranslatableText("steambridge.gui.ban");
            int gap = 4;
            for (int i = 0; i < snaps.size(); i++) {
                int y = yStart + (i * 25);
                final long steamId = snaps.get(i).getSteamId();
                ButtonWidget ban = GuiButtons.createRightAligned(this.textRenderer, this.width - 8, y, banMsg,
                        b -> { server.banPlayer(steamId); rebuild(); }, 30, 120);
                ButtonWidget kick = GuiButtons.createRightAligned(this.textRenderer, ban.x - gap, y, kickMsg,
                        b -> { server.kickPlayer(steamId); rebuild(); }, 30, 120);
                this.addButton(kick);
                this.addButton(ban);
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
    public void render(MatrixStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(matrixStack);
        drawCenteredText(matrixStack, this.textRenderer, I18n.translate("steambridge.gui.management"), this.width / 2, 10, 16777215);

        if (server != null && server.isRunning()) {
            List<SteamServer.PlayerSnapshot> snaps = snapshots();
            int yStart = 40;

            if (snaps.isEmpty()) {
                drawCenteredText(matrixStack, this.textRenderer, I18n.translate("steambridge.gui.no_players"), this.width / 2, yStart + 10, 0xAAAAAA);
            } else {
                for (int i = 0; i < snaps.size(); i++) {
                    SteamServer.PlayerSnapshot snap = snaps.get(i);
                    int y = yStart + (i * 25);

                    String avatar = SteamSocial.ProfileCache.get().getAvatarTexture(snap.getSteamId());
                    if (avatar != null && !avatar.isEmpty()) {
                        try {
                            Identifier loc = new Identifier(avatar);
                            this.client.getTextureManager().bindTexture(loc);
                            RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
                            drawTexture(matrixStack, this.width / 2 - 170, y + 2, 0, 0, 16, 16, 16, 16);
                        } catch (Exception ignored) {}
                    }

                    SteamConnectionStatus status = snap.getConnectionStatus();
                    String pingStr  = (status != null && status.getPingMs() >= 0) ? status.getPingMs() + "ms" : "~";
                    String connType = (status != null && status.isConnectionActive())
                            ? (status.isUsingRelay()
                                ? I18n.translate("steambridge.gui.conn_relay")
                                : I18n.translate("steambridge.gui.conn_p2p"))
                            : "?";
                    drawStringWithShadow(matrixStack, this.textRenderer,
                            snap.getSteamName() + " (" + snap.getMinecraftName() + ") "
                            + pingStr + " [" + connType + "]",
                            this.width / 2 - 150, y + 6, 16777215);
                }
            }
        } else {
            drawCenteredText(matrixStack, this.textRenderer, I18n.translate("steambridge.gui.not_running"), this.width / 2, 50, 16733525);
        }

        super.render(matrixStack, mouseX, mouseY, partialTicks);
    }
}
