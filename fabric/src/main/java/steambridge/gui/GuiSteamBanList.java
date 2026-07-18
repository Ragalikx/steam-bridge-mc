/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.steam.SteamServer;
import steambridge.steam.SteamSocial;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public class GuiSteamBanList extends Screen {
    private final Screen parent;
    private final SteamServer server;
    private int banCount = -1;

    public GuiSteamBanList(Screen parent, SteamServer server) {
        super(Component.translatable("steambridge.gui.banned"));
        this.parent = parent;
        this.server = server;
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {}

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                b -> this.minecraft.setScreen(parent))
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());

        if (server != null) {
            List<SteamSocial.Bans.Record> bans = server.getBanRecords();
            banCount = bans.size();
            int yStart = 40;
            for (int i = 0; i < bans.size(); i++) {
                int y = yStart + (i * 25);
                final long steamId = bans.get(i).getSteamId();
                this.addRenderableWidget(Button.builder(
                        Component.translatable("steambridge.gui.unban"),
                        b -> {
                            server.unbanPlayer(steamId);
                            this.rebuildWidgets();
                        })
                        .bounds(this.width / 2 + 50, y, 60, 20).build());
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (server != null) {
            List<SteamSocial.Bans.Record> bans = server.getBanRecords();
            if (bans.size() != banCount) {
                this.rebuildWidgets();
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        super.renderBackground(g, mouseX, mouseY, partialTicks);
        g.drawCenteredString(this.font, I18n.get("steambridge.gui.banned"), this.width / 2, 10, 16777215);

        if (server != null) {
            List<SteamSocial.Bans.Record> bans = server.getBanRecords();
            int yStart = 40;

            if (bans.isEmpty()) {
                g.drawCenteredString(this.font, I18n.get("steambridge.gui.no_bans"), this.width / 2, yStart + 10, 0xAAAAAA);
            } else {
                for (int i = 0; i < bans.size(); i++) {
                    SteamSocial.Bans.Record ban = bans.get(i);
                    int y = yStart + (i * 25);

                    String avatar = SteamSocial.ProfileCache.get().getAvatarTexture(ban.getSteamId());
                    if (avatar != null && !avatar.isEmpty()) {
                        g.blit(ResourceLocation.parse(avatar), this.width / 2 - 170, y + 2, 0.0F, 0.0F, 16, 16, 16, 16);
                    }

                    g.drawString(this.font, ban.getSteamName() + " (" + ban.getMinecraftName() + ")", this.width / 2 - 150, y + 6, 16777215);
                }
            }
        }

        super.render(g, mouseX, mouseY, partialTicks);
    }
}
