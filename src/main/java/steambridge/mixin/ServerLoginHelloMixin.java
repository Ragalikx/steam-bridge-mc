/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import steambridge.SteamBridgeConfig;

/**
 * Vanilla gives a remote guest the host GameProfile if the nicks match.
 * That hangs the guest in DELAY_ACCEPT while the host is in the world.
 * Remote Steam joiners skip that shortcut. Optional kick is a config flag.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginHelloMixin {

    @Shadow @Final
    Connection connection;

    @Shadow @Final
    MinecraftServer server;

    @Shadow
    public abstract void disconnect(Component reason);

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void steambridge$sameNamePolicy(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (this.connection.isMemoryConnection()) {
            return;
        }
        GameProfile host = this.server.getSingleplayerProfile();
        if (host == null || packet.name() == null) {
            return;
        }
        if (!packet.name().equalsIgnoreCase(host.getName())) {
            return;
        }
        if (SteamBridgeConfig.kickOnSameNameAsHost()) {
            this.disconnect(Component.translatable("steambridge.disconnect.same_name_as_host"));
            ci.cancel();
        }
    }

    @Redirect(
        method = "handleHello",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getSingleplayerProfile()Lcom/mojang/authlib/GameProfile;"
        )
    )
    private GameProfile steambridge$noHostTakeover(MinecraftServer server) {
        if (this.connection.isMemoryConnection()) {
            return server.getSingleplayerProfile();
        }
        return null;
    }
}
