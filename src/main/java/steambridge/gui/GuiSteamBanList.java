/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.steam.SteamServer;
import steambridge.steam.SteamSocial;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
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
    public boolean isPauseScreen() {
        return false;
    }

    private void rebuild() {
        this.init(this.minecraft, this.width, this.height);
    }

    @Override
    protected void init() {
        this.addRenderableWidget(GuiButtons.createCentered(this.font, this.width / 2, this.height - 30,
                Component.translatable("gui.back"),
                b -> this.minecraft.setScreen(parent), 100, this.width - 20));

        if (server != null) {
            List<SteamSocial.Bans.Record> bans = server.getBanRecords();
            banCount = bans.size();
            int yStart = 40;
            Component unbanMsg = Component.translatable("steambridge.gui.unban");
            for (int i = 0; i < bans.size(); i++) {
                int y = yStart + (i * 25);
                final long steamId = bans.get(i).getSteamId();
                this.addRenderableWidget(GuiButtons.createRightAligned(this.font, this.width - 8, y,
                        unbanMsg,
                        b -> {
                            server.unbanPlayer(steamId);
                            rebuild();
                        }, 40, 160));
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (server != null) {
            List<SteamSocial.Bans.Record> bans = server.getBanRecords();
            if (bans.size() != banCount) {
                rebuild();
            }
        }
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(poseStack);
        drawCenteredString(poseStack, this.font, I18n.get("steambridge.gui.banned"), this.width / 2, 10, 16777215);

        if (server != null) {
            List<SteamSocial.Bans.Record> bans = server.getBanRecords();
            int yStart = 40;

            if (bans.isEmpty()) {
                drawCenteredString(poseStack, this.font, I18n.get("steambridge.gui.no_bans"), this.width / 2, yStart + 10, 0xAAAAAA);
            } else {
                for (int i = 0; i < bans.size(); i++) {
                    SteamSocial.Bans.Record ban = bans.get(i);
                    int y = yStart + (i * 25);

                    String avatar = SteamSocial.ProfileCache.get().getAvatarTexture(ban.getSteamId());
                    if (avatar != null && !avatar.isEmpty()) {
                        try {
                            ResourceLocation loc = new ResourceLocation(avatar);
                            RenderSystem.setShader(GameRenderer::getPositionTexShader);
                            RenderSystem.setShaderTexture(0, loc);
                            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                            blit(poseStack, this.width / 2 - 170, y + 2, 0, 0, 16, 16, 16, 16);
                        } catch (Exception ignored) {}
                    }

                    drawString(poseStack, this.font,
                            ban.getSteamName() + " (" + ban.getMinecraftName() + ")",
                            this.width / 2 - 150, y + 6, 16777215);
                }
            }
        }

        super.render(poseStack, mouseX, mouseY, partialTicks);
    }
}
