/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;

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
    public static int fitWidth(FontRenderer font, ITextComponent msg, int minW, int maxW) {
        int w = font.width(msg) + PAD_X;
        if (w < minW) w = minW;
        if (maxW > 0 && w > maxW) w = maxW;
        return w;
    }

    public static int fitWidth(FontRenderer font, ITextComponent msg) {
        return fitWidth(font, msg, MIN_WIDTH, 0);
    }

    public static Button create(FontRenderer font, int x, int y, ITextComponent msg, Button.IPressable onPress) {
        return create(font, x, y, msg, onPress, MIN_WIDTH, 0);
    }

    public static Button create(FontRenderer font, int x, int y, ITextComponent msg, Button.IPressable onPress,
                                int minW, int maxW) {
        return new Button(x, y, fitWidth(font, msg, minW, maxW), HEIGHT, msg, onPress);
    }

    /** Button whose right edge sits at {@code rightEdgeX}. */
    public static Button createRightAligned(FontRenderer font, int rightEdgeX, int y, ITextComponent msg,
                                            Button.IPressable onPress) {
        return createRightAligned(font, rightEdgeX, y, msg, onPress, MIN_WIDTH, 0);
    }

    public static Button createRightAligned(FontRenderer font, int rightEdgeX, int y, ITextComponent msg,
                                            Button.IPressable onPress, int minW, int maxW) {
        int w = fitWidth(font, msg, minW, maxW);
        return new Button(rightEdgeX - w, y, w, HEIGHT, msg, onPress);
    }

    /** Horizontally centered at {@code centerX}. */
    public static Button createCentered(FontRenderer font, int centerX, int y, ITextComponent msg,
                                        Button.IPressable onPress) {
        return createCentered(font, centerX, y, msg, onPress, MIN_WIDTH, 0);
    }

    public static Button createCentered(FontRenderer font, int centerX, int y, ITextComponent msg,
                                        Button.IPressable onPress, int minW, int maxW) {
        int w = fitWidth(font, msg, minW, maxW);
        return new Button(centerX - w / 2, y, w, HEIGHT, msg, onPress);
    }

    /**
     * Updates label and resizes the button to fit. Keeps the left edge fixed unless
     * {@code keepRightEdge} is true (then the right edge stays put).
     */
    public static void setMessageFit(FontRenderer font, Button button, ITextComponent msg, boolean keepRightEdge) {
        setMessageFit(font, button, msg, keepRightEdge, MIN_WIDTH, 0);
    }

    public static void setMessageFit(FontRenderer font, Button button, ITextComponent msg, boolean keepRightEdge,
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
