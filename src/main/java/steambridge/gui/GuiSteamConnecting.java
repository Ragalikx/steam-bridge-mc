/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.steam.SteamClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;

public class GuiSteamConnecting extends Screen {

    private final Screen previousGuiScreen;
    private final SteamClient steamClient;
    private boolean failHandled = false;

    public GuiSteamConnecting(Screen parent, SteamClient client) {
        super(LiteralText.EMPTY);
        this.previousGuiScreen = parent;
        this.steamClient       = client;
    }

    @Override
    protected void init() {
        this.addButton(GuiButtons.createCentered(this.textRenderer, this.width / 2,
                this.height / 4 + 120 + 12,
                new TranslatableText("gui.cancel"),
                b -> {
                    steamClient.disconnect();
                    this.client.openScreen(buildDirectConnectScreen());
                }, 100, this.width - 20));
    }

    @Override
    public void tick() {
        super.tick();
        if (!failHandled && steamClient != null && steamClient.getState() == SteamClient.State.FAILED) {
            failHandled = true;
            this.client.openScreen(new DisconnectedScreen(
                    buildDirectConnectScreen(),
                    new TranslatableText("connect.failed"),
                    new LiteralText(steamClient.getStatusMsg())));
        }
    }

    @Override
    public void render(MatrixStack matrixStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(matrixStack);

        if (steamClient != null) {
            drawCenteredText(matrixStack, this.textRenderer,
                    steamClient.getStatusMsg(),
                    this.width / 2, this.height / 2 - 50,
                    0xFFFFFF);
        }

        super.render(matrixStack, mouseX, mouseY, partialTick);
    }

    /**
     * Returns the previous screen if it is a server list, or a fresh
     * {@link MultiplayerScreen} to avoid landing on a dead DirectConnect screen.
     */
    private Screen buildDirectConnectScreen() {
        if (previousGuiScreen instanceof MultiplayerScreen) {
            return previousGuiScreen;
        }
        return new MultiplayerScreen(new TitleScreen());
    }
}
