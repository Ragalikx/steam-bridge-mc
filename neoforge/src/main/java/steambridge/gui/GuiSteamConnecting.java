/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.steam.SteamClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

public class GuiSteamConnecting extends Screen {

    private final Screen previousGuiScreen;
    private final SteamClient client;
    private boolean failHandled = false;

    public GuiSteamConnecting(Screen parent, SteamClient client) {
        super(Component.empty());
        this.previousGuiScreen = parent;
        this.client            = client;
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {}

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> {
            client.disconnect();
            this.minecraft.setScreen(buildServerListScreen());
        }).bounds(this.width / 2 - 100, this.height / 4 + 120 + 12, 200, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (!failHandled && client != null && client.getState() == SteamClient.State.FAILED) {
            failHandled = true;
            this.minecraft.setScreen(new DisconnectedScreen(
                    buildServerListScreen(),
                    Component.translatable("connect.failed"),
                    Component.literal(client.getStatusMsg())));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);

        if (client != null) {
            g.drawCenteredString(this.font,
                    client.getStatusMsg(),
                    this.width / 2, this.height / 2 - 50,
                    0xFFFFFF);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * Returns the previous screen if it is a server list, or a fresh
     * {@link JoinMultiplayerScreen} to avoid landing on a dead DirectConnect screen.
     */
    private Screen buildServerListScreen() {
        if (previousGuiScreen instanceof JoinMultiplayerScreen) {
            return previousGuiScreen;
        }
        return new JoinMultiplayerScreen(new TitleScreen());
    }
}
