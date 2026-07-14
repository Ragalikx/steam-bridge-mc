/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import steambridge.event.SteamClientEvents;
import steambridge.gui.VanillaGuiIntegration;

/**
 * Fabric substitute for NeoForge {@code ScreenEvent.Opening}:
 * rewrite or cancel the screen passed to {@link Minecraft#setScreen}.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void steambridge$onSetScreen(Screen screen, CallbackInfo ci) {
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
            // Replacement path: cancel the original open, open the substitute.
            // Steam disconnect already cleared activeClient when swapping disconnect screens,
            // so the re-entry does not loop.
            ci.cancel();
            ((Minecraft) (Object) this).setScreen(next);
        }
    }
}
