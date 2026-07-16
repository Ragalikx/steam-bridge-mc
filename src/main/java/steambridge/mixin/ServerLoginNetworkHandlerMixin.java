/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 */
package steambridge.mixin;

import net.minecraft.network.ClientConnection;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import steambridge.steam.SteamManager;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Steam LoopbackBridge always appears as 127.0.0.1 to the integrated server.
 * Compression on that path races with the client proxy and can kill the TCP leg
 * during login. Treat Steam bridge peers as local so SetCompression is skipped.
 */
@Mixin(ServerLoginNetworkHandler.class)
public class ServerLoginNetworkHandlerMixin {

    @Redirect(
            method = "acceptPlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/ClientConnection;isLocal()Z"
            )
    )
    private boolean steambridge$steamBridgeIsLocal(ClientConnection connection) {
        if (connection.isLocal()) {
            return true;
        }
        if (SteamManager.getInstance().getActiveServer() == null) {
            return false;
        }
        SocketAddress addr = connection.getAddress();
        if (addr instanceof InetSocketAddress) {
            InetSocketAddress inet = (InetSocketAddress) addr;
            if (inet.getAddress() != null && inet.getAddress().isLoopbackAddress()) {
                return true;
            }
        }
        return false;
    }
}
