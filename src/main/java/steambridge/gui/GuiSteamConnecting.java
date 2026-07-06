/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.steam.SteamClient;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextComponentString;

import java.io.IOException;

public class GuiSteamConnecting extends GuiScreen {

    private final GuiScreen  previousGuiScreen;
    private final SteamClient client;

    public GuiSteamConnecting(GuiScreen parent, SteamClient client) {
        this.previousGuiScreen = parent;
        this.client            = client;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.buttonList.add(new GuiButton(0,
                this.width / 2 - 100, this.height / 4 + 120 + 12,
                I18n.format("gui.cancel")));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            client.disconnect();
            this.mc.displayGuiScreen(buildServerListScreen());
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        if (client != null) {
            this.drawCenteredString(this.fontRenderer,
                    client.getStatusMsg(),
                    this.width / 2, this.height / 2 - 50,
                    0xFFFFFF);

            SteamClient.State state = client.getState();
            if (state == SteamClient.State.FAILED) {
                this.mc.displayGuiScreen(new GuiDisconnected(
                        buildServerListScreen(),
                        "connect.failed",
                        new TextComponentString(client.getStatusMsg())));
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }



    /**
     * Returns the previous screen if it is a server list, or a fresh {@link GuiMultiplayer}
     * to avoid landing on a dead DirectConnect screen.
     */
    private GuiScreen buildServerListScreen() {
        if (previousGuiScreen instanceof GuiMultiplayer) {
            return previousGuiScreen;
        }
        return new GuiMultiplayer(new GuiMainMenu());
    }
}
