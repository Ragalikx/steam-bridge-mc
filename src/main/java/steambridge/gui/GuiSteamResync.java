/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.SteamAppIdHelper;
import steambridge.SteamBridgeMod;
import steambridge.steam.SteamManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

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
        this.addRenderableWidget(GuiButtons.createCentered(this.font, this.width / 2, this.height - 40,
                Component.translatable("gui.cancel"),
                b -> this.minecraft.setScreen(parent), 100, this.width - 20));

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
                statusLine1 = "§a" + I18n.get("steambridge.gui.resync_success");
                statusLine2 = "§7" + I18n.get(successHintKey);
                Minecraft.getInstance().execute(() -> {
                    if (onSteamReady != null) onSteamReady.run();
                });
                return;
            }
        }

        if (remaining <= 0) {
            state = State.FAILED;
            statusLine1 = "§c" + I18n.get("steambridge.gui.resync_timeout");
            statusLine2 = "§7" + I18n.get("steambridge.gui.resync_timeout_hint");
            return;
        }

        statusLine1 = "§e" + I18n.get("steambridge.gui.resync_countdown", remaining);
        statusLine2 = "";
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(poseStack);

        String title = "§b" + I18n.get("steambridge.gui.resync_title");
        drawCenteredString(poseStack, this.font, title, this.width / 2, this.height / 2 - 50, 0xFFFFFF);
        drawCenteredString(poseStack, this.font, statusLine1, this.width / 2, this.height / 2 - 20, 0xFFFFFF);
        if (!statusLine2.isEmpty()) {
            drawCenteredString(poseStack, this.font, statusLine2, this.width / 2, this.height / 2, 0xAAAAAA);
        }

        if (state == State.WAITING) {
            int dots = (ticksElapsed / 8) % 4;
            StringBuilder sb = new StringBuilder("§7");
            for (int i = 0; i < dots; i++) sb.append('.');
            drawCenteredString(poseStack, this.font, sb.toString(), this.width / 2, this.height / 2 + 16, 0xFFFFFF);
        }

        super.render(poseStack, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
