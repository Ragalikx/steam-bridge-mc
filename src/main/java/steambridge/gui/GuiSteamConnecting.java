/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.steam.SteamClient;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.client.gui.screen.MultiplayerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;

public class GuiSteamConnecting extends Screen {

    private final Screen previousGuiScreen;
    private final SteamClient client;
    private boolean failHandled = false;

    public GuiSteamConnecting(Screen parent, SteamClient client) {
        super(StringTextComponent.EMPTY);
        this.previousGuiScreen = parent;
        this.client            = client;
    }

    @Override
    protected void init() {
        this.addButton(GuiButtons.createCentered(this.font, this.width / 2,
                this.height / 4 + 120 + 12,
                new TranslationTextComponent("gui.cancel"),
                b -> {
                    client.disconnect();
                    this.minecraft.setScreen(buildServerListScreen());
                }, 100, this.width - 20));
    }

    @Override
    public void tick() {
        super.tick();
        if (!failHandled && client != null && client.getState() == SteamClient.State.FAILED) {
            failHandled = true;
            this.minecraft.setScreen(new DisconnectedScreen(
                    buildServerListScreen(),
                    new TranslationTextComponent("connect.failed"),
                    new StringTextComponent(client.getStatusMsg())));
        }
    }

    @Override
    public void render(MatrixStack matrixStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(matrixStack);

        if (client != null) {
            drawCenteredString(matrixStack, this.font,
                    client.getStatusMsg(),
                    this.width / 2, this.height / 2 - 50,
                    0xFFFFFF);
        }

        super.render(matrixStack, mouseX, mouseY, partialTick);
    }

    /**
     * Returns the previous screen if it is a server list, or a fresh
     * {@link MultiplayerScreen} to avoid landing on a dead DirectConnect screen.
     */
    private Screen buildServerListScreen() {
        if (previousGuiScreen instanceof MultiplayerScreen) {
            return previousGuiScreen;
        }
        return new MultiplayerScreen(new MainMenuScreen());
    }
}
