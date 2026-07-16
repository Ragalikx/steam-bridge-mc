/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Buttons sized to their label so long locales do not clip past the widget. */
public final class GuiButtons {

    public static final int PAD_X = 12;
    public static final int HEIGHT = 20;
    public static final int MIN_WIDTH = 20;

    private GuiButtons() {}

    public static int fitWidth(TextRenderer textRenderer, Text msg, int minW, int maxW) {
        int w = textRenderer.getWidth(msg) + PAD_X;
        if (w < minW) w = minW;
        if (maxW > 0 && w > maxW) w = maxW;
        return w;
    }

    public static int fitWidth(TextRenderer textRenderer, Text msg) {
        return fitWidth(textRenderer, msg, MIN_WIDTH, 0);
    }

    public static ButtonWidget create(TextRenderer textRenderer, int x, int y, Text msg, ButtonWidget.PressAction onPress) {
        return create(textRenderer, x, y, msg, onPress, MIN_WIDTH, 0);
    }

    public static ButtonWidget create(TextRenderer textRenderer, int x, int y, Text msg, ButtonWidget.PressAction onPress,
                                int minW, int maxW) {
        return new ButtonWidget(x, y, fitWidth(textRenderer, msg, minW, maxW), HEIGHT, msg, onPress);
    }

    public static ButtonWidget createRightAligned(TextRenderer textRenderer, int rightEdgeX, int y, Text msg,
                                            ButtonWidget.PressAction onPress) {
        return createRightAligned(textRenderer, rightEdgeX, y, msg, onPress, MIN_WIDTH, 0);
    }

    public static ButtonWidget createRightAligned(TextRenderer textRenderer, int rightEdgeX, int y, Text msg,
                                            ButtonWidget.PressAction onPress, int minW, int maxW) {
        int w = fitWidth(textRenderer, msg, minW, maxW);
        return new ButtonWidget(rightEdgeX - w, y, w, HEIGHT, msg, onPress);
    }

    public static ButtonWidget createCentered(TextRenderer textRenderer, int centerX, int y, Text msg,
                                        ButtonWidget.PressAction onPress) {
        return createCentered(textRenderer, centerX, y, msg, onPress, MIN_WIDTH, 0);
    }

    public static ButtonWidget createCentered(TextRenderer textRenderer, int centerX, int y, Text msg,
                                        ButtonWidget.PressAction onPress, int minW, int maxW) {
        int w = fitWidth(textRenderer, msg, minW, maxW);
        return new ButtonWidget(centerX - w / 2, y, w, HEIGHT, msg, onPress);
    }

    public static void setMessageFit(TextRenderer textRenderer, ButtonWidget button, Text msg, boolean keepRightEdge) {
        setMessageFit(textRenderer, button, msg, keepRightEdge, MIN_WIDTH, 0);
    }

    public static void setMessageFit(TextRenderer textRenderer, ButtonWidget button, Text msg, boolean keepRightEdge,
                                     int minW, int maxW) {
        int oldW = button.getWidth();
        int newW = fitWidth(textRenderer, msg, minW, maxW);
        button.setMessage(msg);
        button.setWidth(newW);
        if (keepRightEdge && newW != oldW) {
            button.x += oldW - newW;
        }
    }
}
