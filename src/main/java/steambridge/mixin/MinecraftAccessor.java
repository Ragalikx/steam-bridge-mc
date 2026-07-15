/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access Minecraft.pendingConnection (remapped at runtime by Mixin). */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {

    @Accessor("pendingConnection")
    void setPendingConnection(Connection connection);
}
