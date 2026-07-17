/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.SteamAppIdHelper;
import steambridge.SteamBridgeMod;
import steambridge.steam.SteamManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import java.util.function.Consumer;

/**
 * Shown when Steam is not running. Launches Steam, waits up to {@value #TIMEOUT_SECONDS}
 * seconds for {@link SteamManager#reinit()}, then runs {@code onSteamReady}.
 */
public class GuiSteamResync extends GuiScreen {

    private static final int TIMEOUT_SECONDS = 40;
    private static final int POLL_INTERVAL_TICKS = 40;

    private final GuiScreen parent;
    private final Runnable onSteamReady;
    private final String successHintKey;

    private enum State { LAUNCHING, WAITING, SUCCESS, FAILED }

    private volatile State state = State.LAUNCHING;
    private String statusLine1 = "";
    private String statusLine2 = "";

    private int ticksElapsed = 0;
    private int nextCheckTick = POLL_INTERVAL_TICKS;

    public GuiSteamResync(GuiScreen parent, Consumer<String> onSteamIdSelected) {
        this(parent,
                () -> net.minecraft.client.Minecraft.getMinecraft().displayGuiScreen(
                        new GuiSteamFriends(parent, null, onSteamIdSelected)),
                "steambridge.gui.resync_success_hint");
    }

    public GuiSteamResync(GuiScreen parent, Runnable onSteamReady, String successHintKey) {
        this.parent = parent;
        this.onSteamReady = onSteamReady;
        this.successHintKey = successHintKey != null
                ? successHintKey
                : "steambridge.gui.resync_success_hint";
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.buttonList.add(GuiButtons.createCentered(0, this.fontRendererObj, this.width / 2, this.height - 40,
                I18n.format("gui.cancel"), 100, this.width - 20));

        if (state == State.LAUNCHING) {
            statusLine1 = "\u00a7e" + I18n.format("steambridge.gui.resync_launching");
            statusLine2 = "";
            launchAndScheduleRetry();
        }
    }

    private void launchAndScheduleRetry() {
        try {
            SteamAppIdHelper.ensureAppId(net.minecraft.client.Minecraft.getMinecraft().mcDataDir);
            SteamAppIdHelper.launchSteam();
            state = State.WAITING;
            statusLine1 = "\u00a7e" + I18n.format("steambridge.gui.resync_starting");
            statusLine2 = "\u00a77" + I18n.format("steambridge.gui.resync_starting_hint");
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("[Resync] Failed to launch Steam: {}", e.getMessage());
            state = State.WAITING;
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

            SteamBridgeMod.LOG.info("[Resync] Attempting Steam reinit... elapsed={}s", elapsedSec);
            boolean ok = SteamManager.getInstance().reinit();
            if (ok) {
                state = State.SUCCESS;
                statusLine1 = "\u00a7a" + I18n.format("steambridge.gui.resync_success");
                statusLine2 = "\u00a77" + I18n.format(successHintKey);
                steambridge.ClientTasks.run(() -> {
                    if (onSteamReady != null) onSteamReady.run();
                });
                return;
            }
        }

        if (remaining <= 0) {
            state = State.FAILED;
            SteamManager.InitFailure fail = SteamManager.getInstance().getLastInitFailure();
            if (fail != null && fail != SteamManager.InitFailure.NONE) {
                statusLine1 = "\u00a7c" + I18n.format(SteamManager.getInstance().getLastInitFailureKey());
                String hintKey = SteamManager.getInstance().getLastInitFailureHintKey();
                statusLine2 = hintKey.isEmpty()
                        ? "\u00a77" + I18n.format("steambridge.gui.resync_timeout_hint")
                        : "\u00a77" + I18n.format(hintKey);
            } else {
                statusLine1 = "\u00a7c" + I18n.format("steambridge.gui.resync_timeout");
                statusLine2 = "\u00a77" + I18n.format("steambridge.gui.resync_timeout_hint");
            }
            return;
        }

        statusLine1 = "\u00a7e" + I18n.format("steambridge.gui.resync_countdown", remaining);
        statusLine2 = "";
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            this.mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        String title = "\u00a7b" + I18n.format("steambridge.gui.resync_title");
        this.drawCenteredString(this.fontRendererObj, title, this.width / 2, this.height / 2 - 50, 0xFFFFFF);
        this.drawCenteredString(this.fontRendererObj, statusLine1, this.width / 2, this.height / 2 - 20, 0xFFFFFF);
        if (!statusLine2.isEmpty()) {
            this.drawCenteredString(this.fontRendererObj, statusLine2, this.width / 2, this.height / 2, 0xAAAAAA);
        }

        if (state == State.WAITING) {
            int dots = (ticksElapsed / 8) % 4;
            StringBuilder sb = new StringBuilder("\u00a77");
            for (int i = 0; i < dots; i++) sb.append('.');
            this.drawCenteredString(this.fontRendererObj, sb.toString(), this.width / 2, this.height / 2 + 16, 0xFFFFFF);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}