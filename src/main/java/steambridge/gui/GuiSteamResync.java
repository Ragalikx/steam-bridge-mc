/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.SteamAppIdHelper;
import steambridge.SteamBridgeMod;
import steambridge.steam.SteamManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;

import java.util.function.Consumer;

/**
 * Shown when the Friends button is clicked but Steam is not running.
 * Launches Steam, waits up to {@value #TIMEOUT_SECONDS} seconds, then re-inits.
 */
public class GuiSteamResync extends Screen {

    private static final int TIMEOUT_SECONDS = 40;
    private static final int POLL_INTERVAL_TICKS = 40; // check every 2 seconds (20 ticks/sec)

    private final Screen parent;
    private final Runnable onSteamReady;
    private final String successHintKey;

    private enum State { LAUNCHING, WAITING, SUCCESS, FAILED }

    private volatile State state = State.LAUNCHING;
    private String statusLine1 = "";
    private String statusLine2 = "";

    /** Ticks since launch start. */
    private int ticksElapsed = 0;
    /** Next tick to attempt reinit. */
    private int nextCheckTick = POLL_INTERVAL_TICKS;

    public GuiSteamResync(Screen parent, Consumer<String> onSteamIdSelected) {
        this(parent,
                () -> Minecraft.getInstance().setScreen(
                        new GuiSteamFriends(parent, null, onSteamIdSelected)),
                "steambridge.gui.resync_success_hint");
    }

    public GuiSteamResync(Screen parent, Runnable onSteamReady, String successHintKey) {
        super(Component.empty());
        this.parent = parent;
        this.onSteamReady = onSteamReady;
        this.successHintKey = successHintKey != null
                ? successHintKey
                : "steambridge.gui.resync_success_hint";
    }

    @Override
    protected void init() {
        this.addButton(GuiButtons.createCentered(this.textRenderer, this.width / 2, this.height - 40,
                new TranslatableText("gui.cancel"),
                b -> this.client.openScreen(parent), 100, this.width - 20));

        if (state == State.LAUNCHING) {
            statusLine1 = "\u00a7e" + I18n.translate("steambridge.gui.resync_launching");
            statusLine2 = "";
            launchAndScheduleRetry();
        }
    }

    private void launchAndScheduleRetry() {
        try {
            SteamAppIdHelper.ensureAppId(MinecraftClient.getInstance().runDirectory);
            SteamAppIdHelper.launchSteam();
            state = State.WAITING;
            statusLine1 = "\u00a7e" + I18n.translate("steambridge.gui.resync_starting");
            statusLine2 = "\u00a77" + I18n.translate("steambridge.gui.resync_starting_hint");
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("[Resync] Failed to launch Steam: {}", e.getMessage());
            state = State.WAITING;
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
        int remaining  = TIMEOUT_SECONDS - elapsedSec;

        if (ticksElapsed >= nextCheckTick) {
            nextCheckTick = ticksElapsed + POLL_INTERVAL_TICKS;

            SteamBridgeMod.LOG.info("[Resync] Attempting Steam reinit... elapsed={}s", elapsedSec);
            boolean ok = SteamManager.getInstance().reinit();
            if (ok) {
                state = State.SUCCESS;
                statusLine1 = "\u00a7a" + I18n.translate("steambridge.gui.resync_success");
                statusLine2 = "\u00a77" + I18n.translate(successHintKey);
                MinecraftClient.getInstance().execute(() ->
                        MinecraftClient.getInstance().openScreen(
                                new GuiSteamFriends(parent, null, onSteamIdSelected)));
                return;
            }
        }

        if (remaining <= 0) {
            state = State.FAILED;
            statusLine1 = "\u00a7c" + I18n.translate("steambridge.gui.resync_timeout");
            statusLine2 = "\u00a77" + I18n.translate("steambridge.gui.resync_timeout_hint");
            return;
        }

        statusLine1 = "\u00a7e" + I18n.translate("steambridge.gui.resync_countdown", remaining);
        statusLine2 = "";
    }

    @Override
    public void render(MatrixStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(matrixStack);

        String title = "\u00a7b" + I18n.translate("steambridge.gui.resync_title");
        drawCenteredText(matrixStack, this.textRenderer, title, this.width / 2, this.height / 2 - 50, 0xFFFFFF);
        drawCenteredText(matrixStack, this.textRenderer, statusLine1, this.width / 2, this.height / 2 - 20, 0xFFFFFF);
        if (!statusLine2.isEmpty()) {
            drawCenteredText(matrixStack, this.textRenderer, statusLine2, this.width / 2, this.height / 2, 0xAAAAAA);
        }

        if (state == State.WAITING) {
            int dots = (ticksElapsed / 8) % 4;
            StringBuilder sb = new StringBuilder("\u00a77");
            for (int i = 0; i < dots; i++) sb.append('.');
            drawCenteredText(matrixStack, this.textRenderer, sb.toString(), this.width / 2, this.height / 2 + 16, 0xFFFFFF);
        }

        super.render(matrixStack, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
