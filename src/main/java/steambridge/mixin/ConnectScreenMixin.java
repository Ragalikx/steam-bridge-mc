/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import steambridge.SteamBridgeMod;
import steambridge.gui.VanillaGuiIntegration;

/**
 * Intercepts every vanilla multiplayer connect path before DNS/TCP.
 * ServerData is remapped by Mixin (reflection-based list selection breaks at runtime).
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void steambridge$onStartConnecting(
            Screen parent,
            Minecraft minecraft,
            ServerAddress address,
            ServerData serverData,
            boolean isTransfer,
            TransferState transferState,
            CallbackInfo ci) {
        if (VanillaGuiIntegration.trySteamConnectFromServerData(parent, serverData)) {
            SteamBridgeMod.LOG.info(
                    "Mixin: cancelled ConnectScreen.startConnecting for Steam address {}",
                    serverData != null ? serverData.ip : "?");
            ci.cancel();
        }
    }
}
