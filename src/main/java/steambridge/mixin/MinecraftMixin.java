/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import steambridge.event.SteamClientEvents;
import steambridge.gui.VanillaGuiIntegration;

/** Fabric substitute for Forge GuiOpenEvent. */
@Mixin(MinecraftClient.class)
public class MinecraftMixin {

    @Inject(method = "openScreen", at = @At("HEAD"), cancellable = true)
    private void steambridge$onOpenScreen(Screen screen, CallbackInfo ci) {
        if (screen == null) {
            return;
        }

        Screen next = SteamClientEvents.onSetScreen(screen);
        if (next == null) {
            ci.cancel();
            return;
        }
        next = VanillaGuiIntegration.onSetScreen(next);
        if (next == null) {
            ci.cancel();
            return;
        }
        if (next != screen) {
            ci.cancel();
            ((MinecraftClient) (Object) this).openScreen(next);
        }
    }
}
