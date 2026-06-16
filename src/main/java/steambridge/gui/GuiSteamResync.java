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

import steambridge.SteamAppIdHelper;
import steambridge.SteamBridgeMod;
import steambridge.steam.SteamManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * A screen shown when the Friends button is clicked but Steam is not running.
 * It launches Steam, waits up to {@value #TIMEOUT_SECONDS} seconds, then re-inits.
 */
public class GuiSteamResync extends GuiScreen {

    private static final int TIMEOUT_SECONDS = 40;
    private static final int POLL_INTERVAL_TICKS = 40; // check every 2 seconds (20 ticks/sec)

    private final GuiScreen parent;
    private final Consumer<String> onSteamIdSelected;

    private enum State { LAUNCHING, WAITING, SUCCESS, FAILED }

    private volatile State state = State.LAUNCHING;
    private String statusLine1 = "";
    private String statusLine2 = "";

    /** Ticks since launch start. */
    private int ticksElapsed = 0;
    /** Next tick to attempt reinit. */
    private int nextCheckTick = POLL_INTERVAL_TICKS;

    public GuiSteamResync(GuiScreen parent, Consumer<String> onSteamIdSelected) {
        this.parent = parent;
        this.onSteamIdSelected = onSteamIdSelected;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.buttonList.add(new GuiButton(0, this.width / 2 - 100, this.height - 40, 200, 20,
                net.minecraft.client.resources.I18n.format("gui.cancel")));

        // Kick off launch on first init
        if (state == State.LAUNCHING) {
            statusLine1 = "§eЗапуск Steam...";
            statusLine2 = "";
            launchAndScheduleRetry();
        }
    }

    private void launchAndScheduleRetry() {
        try {
            SteamAppIdHelper.ensureAppId(net.minecraft.client.Minecraft.getMinecraft().gameDir);
            SteamAppIdHelper.launchSteam();
            state = State.WAITING;
            statusLine1 = "§eЗапускаем Steam...";
            statusLine2 = "§7Это может занять несколько секунд";
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("[Resync] Failed to launch Steam: {}", e.getMessage());
            state = State.WAITING; // still wait
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();

        if (state == State.FAILED || state == State.SUCCESS) {
            return;
        }

        ticksElapsed++;

        int elapsedSec = ticksElapsed / 20;
        int remaining  = TIMEOUT_SECONDS - elapsedSec;

        if (ticksElapsed >= nextCheckTick) {
            nextCheckTick = ticksElapsed + POLL_INTERVAL_TICKS;

            // Try re-init on the main thread (SteamAPI is single-threaded)
            SteamBridgeMod.LOG.info("[Resync] Attempting Steam reinit... elapsed={}s", elapsedSec);
            boolean ok = SteamManager.getInstance().reinit();
            if (ok) {
                state = State.SUCCESS;
                statusLine1 = "§aSteam запущен!";
                statusLine2 = "§7Открываем список друзей...";
                // Open friends screen on next tick
                net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() ->
                        net.minecraft.client.Minecraft.getMinecraft().displayGuiScreen(
                                new GuiSteamFriends(parent, null, onSteamIdSelected)));
                return;
            }
        }

        if (remaining <= 0) {
            state = State.FAILED;
            statusLine1 = "§cТайм-аут: Steam не запустился.";
            statusLine2 = "§7Запустите Steam вручную и попробуйте снова.";
            return;
        }

        // Update countdown message
        statusLine1 = "§eЗапускаем Steam... (" + remaining + " сек)";
        statusLine2 = "";
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            this.mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        String title = "§bSteam §rне запущен";
        this.drawCenteredString(this.fontRenderer, title, this.width / 2, this.height / 2 - 50, 0xFFFFFF);
        this.drawCenteredString(this.fontRenderer, statusLine1, this.width / 2, this.height / 2 - 20, 0xFFFFFF);
        if (!statusLine2.isEmpty()) {
            this.drawCenteredString(this.fontRenderer, statusLine2, this.width / 2, this.height / 2, 0xAAAAAA);
        }

        if (state == State.WAITING) {
            // Simple animated dots indicator
            int dots = (ticksElapsed / 8) % 4;
            StringBuilder sb = new StringBuilder("§7");
            for (int i = 0; i < dots; i++) sb.append('.');
            this.drawCenteredString(this.fontRenderer, sb.toString(), this.width / 2, this.height / 2 + 16, 0xFFFFFF);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
