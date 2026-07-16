/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.steam.SteamServer;
import steambridge.steam.SteamSocial;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;

import java.util.List;

public class GuiSteamBanList extends Screen {
    private final Screen parent;
    private final SteamServer server;
    private int banCount = -1;

    public GuiSteamBanList(Screen parent, SteamServer server) {
        super(new TranslationTextComponent("steambridge.gui.banned"));
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
        this.addButton(GuiButtons.createCentered(this.font, this.width / 2, this.height - 30,
                new TranslationTextComponent("gui.back"),
                b -> this.minecraft.setScreen(parent), 100, this.width - 20));

        if (server != null) {
            List<SteamSocial.Bans.Record> bans = server.getBanRecords();
            banCount = bans.size();
            int yStart = 40;
            ITextComponent unbanMsg = new TranslationTextComponent("steambridge.gui.unban");
            for (int i = 0; i < bans.size(); i++) {
                int y = yStart + (i * 25);
                final long steamId = bans.get(i).getSteamId();
                this.addButton(GuiButtons.createRightAligned(this.font, this.width - 8, y,
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
    public void render(MatrixStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(matrixStack);
        drawCenteredString(matrixStack, this.font, I18n.get("steambridge.gui.banned"), this.width / 2, 10, 16777215);

        if (server != null) {
            List<SteamSocial.Bans.Record> bans = server.getBanRecords();
            int yStart = 40;

            if (bans.isEmpty()) {
                drawCenteredString(matrixStack, this.font, I18n.get("steambridge.gui.no_bans"), this.width / 2, yStart + 10, 0xAAAAAA);
            } else {
                for (int i = 0; i < bans.size(); i++) {
                    SteamSocial.Bans.Record ban = bans.get(i);
                    int y = yStart + (i * 25);

                    String avatar = SteamSocial.ProfileCache.get().getAvatarTexture(ban.getSteamId());
                    if (avatar != null && !avatar.isEmpty()) {
                        try {
                            ResourceLocation loc = new ResourceLocation(avatar);
                            this.minecraft.getTextureManager().bind(loc);
                            RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
                            blit(matrixStack, this.width / 2 - 170, y + 2, 0, 0, 16, 16, 16, 16);
                        } catch (Exception ignored) {}
                    }

                    drawString(matrixStack, this.font,
                            ban.getSteamName() + " (" + ban.getMinecraftName() + ")",
                            this.width / 2 - 150, y + 6, 16777215);
                }
            }
        }

        super.render(matrixStack, mouseX, mouseY, partialTicks);
    }
}
