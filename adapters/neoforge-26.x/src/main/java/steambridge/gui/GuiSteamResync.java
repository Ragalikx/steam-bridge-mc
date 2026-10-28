/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import steambridge.SteamAppIdHelper;
import steambridge.SteamBridgeMod;
import steambridge.core.LogLimiter;
import steambridge.core.ProblemLog;
import steambridge.steam.SteamManager;

import java.util.function.Consumer;

/**
 * Shown when Steam is not running. Launches Steam, waits up to {@value #TIMEOUT_SECONDS} seconds
 * for {@link SteamManager#reinit()}, then runs {@code onSteamReady}. Used for the Friends picker
 * and for multiplayer SteamID connect.
 */
public class GuiSteamResync extends Screen {
    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

    private static final int TIMEOUT_SECONDS = 40;
    private static final int POLL_INTERVAL_TICKS = 40; // check every 2 seconds (20 ticks/sec)

    private final Screen parent;
    private final Runnable onSteamReady;
    private final String successHintKey;

    private enum State {
        LAUNCHING,
        WAITING,
        SUCCESS,
        FAILED
    }

    private volatile State state = State.LAUNCHING;
    private String statusLine1 = "";
    private String statusLine2 = "";

    /** Ticks since launch start. */
    private int ticksElapsed = 0;

    /** Next tick to attempt reinit. */
    private int nextCheckTick = POLL_INTERVAL_TICKS;

    /** Friends-button flow: after Steam is up, open the friends picker. */
    public GuiSteamResync(Screen parent, Consumer<String> onSteamIdSelected) {
        this(
                parent,
                () ->
                        Minecraft.getInstance()
                                .setScreen(new GuiSteamFriends(parent, null, onSteamIdSelected)),
                "steambridge.gui.resync_success_hint");
    }

    /**
     * Generic flow: after Steam is up, run {@code onSteamReady} (e.g. continue connect).
     *
     * @param successHintKey i18n key for the second status line on success
     */
    public GuiSteamResync(Screen parent, Runnable onSteamReady, String successHintKey) {
        super(Component.empty());
        this.parent = parent;
        this.onSteamReady = onSteamReady;
        this.successHintKey =
                successHintKey != null ? successHintKey : "steambridge.gui.resync_success_hint";
    }

    @Override
    protected void extractBlurredBackground(GuiGraphicsExtractor graphics) {}

    @Override
    protected void init() {
        this.addRenderableWidget(
                Button.builder(
                                Component.translatable("gui.cancel"),
                                b -> this.minecraft.setScreen(parent))
                        .bounds(this.width / 2 - 100, this.height - 40, 200, 20)
                        .build());

        // Kick off launch on first init
        if (state == State.LAUNCHING) {
            statusLine1 = "§e" + I18n.get("steambridge.gui.resync_launching");
            statusLine2 = "";
            launchAndScheduleRetry();
        }
    }

    private void launchAndScheduleRetry() {
        try {
            SteamAppIdHelper.ensureAppId(Minecraft.getInstance().gameDirectory);
            SteamAppIdHelper.launchSteam();
            state = State.WAITING;
            statusLine1 = "§e" + I18n.get("steambridge.gui.resync_starting");
            statusLine2 = "§7" + I18n.get("steambridge.gui.resync_starting_hint");
        } catch (Exception e) {
            ProblemLog.warn(
                    SteamBridgeMod.LOG,
                    LOG_LIMITER,
                    "GuiSteamResync-warn-1",
                    "[Resync] Failed to launch Steam: {}",
                    e.getMessage());
            state = State.WAITING; // still wait
        }
    }

    @Override
    public void tick() {
        super.tick();

        if (state == State.FAILED || state == State.SUCCESS) {
            return;
        }

        ticksElapsed++;

        int elapsedSec = ticksElapsed / 20;
        int remaining = TIMEOUT_SECONDS - elapsedSec;

        if (ticksElapsed >= nextCheckTick) {
            nextCheckTick = ticksElapsed + POLL_INTERVAL_TICKS;

            // Try re-init on the main thread (SteamAPI is single-threaded)
            SteamBridgeMod.LOG.debug("[Resync] Attempting Steam reinit... elapsed={}s", elapsedSec);
            boolean ok = SteamManager.getInstance().reinit();
            if (ok) {
                state = State.SUCCESS;
                statusLine1 = "§a" + I18n.get("steambridge.gui.resync_success");
                statusLine2 = "§7" + I18n.get(successHintKey);
                Minecraft.getInstance()
                        .execute(
                                () -> {
                                    if (onSteamReady != null) onSteamReady.run();
                                });
                return;
            }
        }

        if (remaining <= 0) {
            state = State.FAILED;
            // Prefer the last Steam init diagnosis (Spacewar / Family View / client down)
            // over a generic "Steam did not start" timeout when reinit already failed.
            statusLine1 = "§c" + I18n.get("steambridge.gui.resync_timeout");
            statusLine2 = "§7" + I18n.get("steambridge.gui.resync_timeout_hint");
            return;
        }

        // Update countdown message
        statusLine1 = "§e" + I18n.get("steambridge.gui.resync_countdown", remaining);
        statusLine2 = "";
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTicks) {
        super.extractBackground(g, mouseX, mouseY, partialTicks);

        String title = "§b" + I18n.get("steambridge.gui.resync_title");
        g.centeredText(this.font, title, this.width / 2, this.height / 2 - 50, 0xFFFFFFFF);
        g.centeredText(this.font, statusLine1, this.width / 2, this.height / 2 - 20, 0xFFFFFFFF);
        if (!statusLine2.isEmpty()) {
            g.centeredText(this.font, statusLine2, this.width / 2, this.height / 2, 0xFFAAAAAA);
        }

        if (state == State.WAITING) {
            // Simple animated dots indicator
            int dots = (ticksElapsed / 8) % 4;
            StringBuilder sb = new StringBuilder("§7");
            for (int i = 0; i < dots; i++) sb.append('.');
            g.centeredText(
                    this.font, sb.toString(), this.width / 2, this.height / 2 + 16, 0xFFFFFFFF);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
