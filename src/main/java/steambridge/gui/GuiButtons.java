/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

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
    public static int fitWidth(Font font, Component msg, int minW, int maxW) {
        int w = font.width(msg) + PAD_X;
        if (w < minW) w = minW;
        if (maxW > 0 && w > maxW) w = maxW;
        return w;
    }

    public static int fitWidth(Font font, Component msg) {
        return fitWidth(font, msg, MIN_WIDTH, 0);
    }

    public static Button create(Font font, int x, int y, Component msg, Button.OnPress onPress) {
        return create(font, x, y, msg, onPress, MIN_WIDTH, 0);
    }

    public static Button create(Font font, int x, int y, Component msg, Button.OnPress onPress,
                                int minW, int maxW) {
        return new Button(x, y, fitWidth(font, msg, minW, maxW), HEIGHT, msg, onPress);
    }

    /** Button whose right edge sits at {@code rightEdgeX}. */
    public static Button createRightAligned(Font font, int rightEdgeX, int y, Component msg,
                                            Button.OnPress onPress) {
        return createRightAligned(font, rightEdgeX, y, msg, onPress, MIN_WIDTH, 0);
    }

    public static Button createRightAligned(Font font, int rightEdgeX, int y, Component msg,
                                            Button.OnPress onPress, int minW, int maxW) {
        int w = fitWidth(font, msg, minW, maxW);
        return new Button(rightEdgeX - w, y, w, HEIGHT, msg, onPress);
    }

    /** Horizontally centered at {@code centerX}. */
    public static Button createCentered(Font font, int centerX, int y, Component msg,
                                        Button.OnPress onPress) {
        return createCentered(font, centerX, y, msg, onPress, MIN_WIDTH, 0);
    }

    public static Button createCentered(Font font, int centerX, int y, Component msg,
                                        Button.OnPress onPress, int minW, int maxW) {
        int w = fitWidth(font, msg, minW, maxW);
        return new Button(centerX - w / 2, y, w, HEIGHT, msg, onPress);
    }

    /**
     * Updates label and resizes the button to fit. Keeps the left edge fixed unless
     * {@code keepRightEdge} is true (then the right edge stays put).
     */
    public static void setMessageFit(Font font, Button button, Component msg, boolean keepRightEdge) {
        setMessageFit(font, button, msg, keepRightEdge, MIN_WIDTH, 0);
    }

    public static void setMessageFit(Font font, Button button, Component msg, boolean keepRightEdge,
                                     int minW, int maxW) {
        int oldW = button.getWidth();
        int newW = fitWidth(font, msg, minW, maxW);
        button.setMessage(msg);
        button.setWidth(newW);
        if (keepRightEdge && newW != oldW) {
            button.x += oldW - newW;
        }
    }
}
