/*
 * Copyright (c) 2019-2026 Ragalikx
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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
            List<SteamServer.PlayerSnapshot> snaps = server.getPlayerSnapshots();
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
            List<SteamServer.PlayerSnapshot> snaps = server.getPlayerSnapshots();
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
            // Kick
            int idx = button.id - 100;
            List<SteamServer.PlayerSnapshot> snaps = server.getPlayerSnapshots();
            if (idx < snaps.size()) {
                server.kickPlayer(snaps.get(idx).getSteamId());
            }
        } else if (button.id >= 200 && button.id < 300) {
            // Ban
            int idx = button.id - 200;
            List<SteamServer.PlayerSnapshot> snaps = server.getPlayerSnapshots();
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
            List<SteamServer.PlayerSnapshot> snaps = server.getPlayerSnapshots();
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
                    String pingStr = (status != null && status.getPingMs() >= 0) ? status.getPingMs() + "ms" : "~";
                    this.drawString(this.fontRenderer, snap.getSteamName() + " (" + snap.getMinecraftName() + ") Ping: " + pingStr, this.width / 2 - 150, y + 6, 16777215);
                }
            }
        } else {
            this.drawCenteredString(this.fontRenderer, net.minecraft.client.resources.I18n.format("steambridge.gui.not_running"), this.width / 2, 50, 16733525);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}

