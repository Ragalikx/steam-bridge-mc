/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 */
package steambridge.mixin;

import net.minecraft.network.ClientConnection;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import steambridge.SteamBridgeMod;
import steambridge.steam.SteamManager;

/**
 * Log who closes the Minecraft connection while a Steam session is active.
 */
@Mixin(ClientConnection.class)
public class ClientConnectionMixin {

    @Inject(method = "disconnect", at = @At("HEAD"))
    private void steambridge$logDisconnect(Text reason, CallbackInfo ci) {
        if (SteamManager.getInstance().getActiveClient() == null
                && SteamManager.getInstance().getActiveServer() == null) {
            return;
        }
        String msg = reason != null ? reason.getString() : "null";
        SteamBridgeMod.LOG.warn(
                "[SteamBridge] ClientConnection.disconnect: reason='{}'",
                SteamBridgeMod.safeLog(msg));
        if (SteamBridgeMod.LOG.isDebugEnabled()) {
            SteamBridgeMod.LOG.debug("[SteamBridge] ClientConnection.disconnect stack", new Throwable("disconnect"));
        }
    }
}
