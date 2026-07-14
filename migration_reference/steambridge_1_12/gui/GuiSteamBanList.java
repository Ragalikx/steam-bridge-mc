/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.steam.SteamServer;
import steambridge.steam.SteamSocial;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import java.io.IOException;
import java.util.List;

public class GuiSteamBanList extends GuiScreen {
    private final GuiScreen parent;
    private final SteamServer server;
    private int banCount = -1;
    private static final int BUTTON_BACK = 0;

    public GuiSteamBanList(GuiScreen parent, SteamServer server) {
        this.parent = parent;
        this.server = server;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.buttonList.add(new GuiButton(BUTTON_BACK, this.width / 2 - 100, this.height - 30, 200, 20, net.minecraft.client.resources.I18n.format("gui.back")));
        updateBanButtons();
    }

    private void updateBanButtons() {
        this.buttonList.removeIf(b -> b.id >= 100);
        if (server != null) {
            List<SteamSocial.Bans.Record> bans = server.getBanRecords();
            banCount = bans.size();
            int yStart = 40;
            for (int i = 0; i < bans.size(); i++) {
                int y = yStart + (i * 25);
                String unbanText = net.minecraft.client.resources.I18n.hasKey("steambridge.gui.unban") ? 
                                   net.minecraft.client.resources.I18n.format("steambridge.gui.unban") : "Unban";
                this.buttonList.add(new GuiButton(100 + i, this.width / 2 + 50, y, 60, 20, unbanText));
            }
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (server != null) {
            List<SteamSocial.Bans.Record> bans = server.getBanRecords();
            if (bans.size() != banCount) {
                updateBanButtons();
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == BUTTON_BACK) {
            this.mc.displayGuiScreen(parent);
        } else if (button.id >= 100) {
            int idx = button.id - 100;
            if (server != null) {
                List<SteamSocial.Bans.Record> bans = server.getBanRecords();
                if (idx < bans.size()) {
                    long steamId = bans.get(idx).getSteamId();
                    server.unbanPlayer(steamId);
                    updateBanButtons();
                }
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        String bannedText = net.minecraft.client.resources.I18n.hasKey("steambridge.gui.banned") ? 
                            net.minecraft.client.resources.I18n.format("steambridge.gui.banned") : "Ban List";
        this.drawCenteredString(this.fontRenderer, bannedText, this.width / 2, 10, 16777215);

        if (server != null) {
            List<SteamSocial.Bans.Record> bans = server.getBanRecords();
            int yStart = 40;

            if (bans.isEmpty()) {
                this.drawCenteredString(this.fontRenderer, net.minecraft.client.resources.I18n.format("steambridge.gui.no_bans"), this.width / 2, yStart + 10, 0xAAAAAA);
            } else {
                for (int i = 0; i < bans.size(); i++) {
                    SteamSocial.Bans.Record ban = bans.get(i);
                    int y = yStart + (i * 25);

                    String avatar = steambridge.steam.SteamSocial.ProfileCache.get().getAvatarTexture(ban.getSteamId());
                    if (avatar != null && !avatar.isEmpty()) {
                        this.mc.getTextureManager().bindTexture(new net.minecraft.util.ResourceLocation(avatar));
                        net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                        net.minecraft.client.gui.Gui.drawModalRectWithCustomSizedTexture(this.width / 2 - 170, y + 2, 0, 0, 16, 16, 16, 16);
                    }

                    this.drawString(this.fontRenderer, ban.getSteamName() + " (" + ban.getMinecraftName() + ")", this.width / 2 - 150, y + 6, 16777215);
                }
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}

