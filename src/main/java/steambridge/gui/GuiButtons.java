/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;

/**
 * Buttons sized to their label so long locales do not clip past the widget.
 */
public final class GuiButtons {

    /** Horizontal padding around the label (both sides combined). */
    public static final int PAD_X = 12;
    public static final int HEIGHT = 20;
    public static final int MIN_WIDTH = 20;

    private GuiButtons() {}

    /** Pixel width needed for {@code msg} with padding, clamped to {@code [minW, maxW]}. */
    public static int fitWidth(FontRenderer font, String msg, int minW, int maxW) {
        int w = font.getStringWidth(msg != null ? msg : "") + PAD_X;
        if (w < minW) w = minW;
        if (maxW > 0 && w > maxW) w = maxW;
        return w;
    }

    public static int fitWidth(FontRenderer font, String msg) {
        return fitWidth(font, msg, MIN_WIDTH, 0);
    }

    public static GuiButton create(int id, FontRenderer font, int x, int y, String msg) {
        return create(id, font, x, y, msg, MIN_WIDTH, 0);
    }

    public static GuiButton create(int id, FontRenderer font, int x, int y, String msg, int minW, int maxW) {
        return new GuiButton(id, x, y, fitWidth(font, msg, minW, maxW), HEIGHT, msg);
    }

    /** Button whose right edge sits at {@code rightEdgeX}. */
    public static GuiButton createRightAligned(int id, FontRenderer font, int rightEdgeX, int y, String msg) {
        return createRightAligned(id, font, rightEdgeX, y, msg, MIN_WIDTH, 0);
    }

    public static GuiButton createRightAligned(int id, FontRenderer font, int rightEdgeX, int y, String msg,
                                               int minW, int maxW) {
        int w = fitWidth(font, msg, minW, maxW);
        return new GuiButton(id, rightEdgeX - w, y, w, HEIGHT, msg);
    }

    /** Horizontally centered at {@code centerX}. */
    public static GuiButton createCentered(int id, FontRenderer font, int centerX, int y, String msg) {
        return createCentered(id, font, centerX, y, msg, MIN_WIDTH, 0);
    }

    public static GuiButton createCentered(int id, FontRenderer font, int centerX, int y, String msg,
                                           int minW, int maxW) {
        int w = fitWidth(font, msg, minW, maxW);
        return new GuiButton(id, centerX - w / 2, y, w, HEIGHT, msg);
    }

    /**
     * Updates label and resizes the button to fit. Keeps the left edge fixed unless
     * {@code keepRightEdge} is true (then the right edge stays put).
     */
    public static void setMessageFit(FontRenderer font, GuiButton button, String msg, boolean keepRightEdge) {
        setMessageFit(font, button, msg, keepRightEdge, MIN_WIDTH, 0);
    }

    public static void setMessageFit(FontRenderer font, GuiButton button, String msg, boolean keepRightEdge,
                                     int minW, int maxW) {
        int oldW = button.width;
        int newW = fitWidth(font, msg, minW, maxW);
        button.displayString = msg;
        button.width = newW;
        if (keepRightEdge && newW != oldW) {
            button.x += oldW - newW;
        }
    }
}
