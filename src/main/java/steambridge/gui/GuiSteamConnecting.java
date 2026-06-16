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

import steambridge.steam.SteamClient;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextComponentString;

import java.io.IOException;

public class GuiSteamConnecting extends GuiScreen {
    private final GuiScreen previousGuiScreen;
    private final SteamClient client;
    private boolean cancel;

    public GuiSteamConnecting(GuiScreen parent, SteamClient client) {
        this.previousGuiScreen = parent;
        this.client = client;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.buttonList.add(new GuiButton(0, this.width / 2 - 100, this.height / 4 + 120 + 12, I18n.format("gui.cancel")));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            this.cancel = true;
            this.client.disconnect();
            this.mc.displayGuiScreen(this.previousGuiScreen);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        if (this.client != null) {
            this.drawCenteredString(this.fontRenderer, this.client.getStatusMsg(), this.width / 2, this.height / 2 - 50, 16777215);

            SteamClient.State state = this.client.getState();
            if (state == SteamClient.State.FAILED) {
                this.mc.displayGuiScreen(new GuiDisconnected(this.previousGuiScreen, "connect.failed", new TextComponentString(this.client.getStatusMsg())));
            } else if (state == SteamClient.State.IN_WORLD) {
                // Automatically covered by Minecraft itself when joining world
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}

