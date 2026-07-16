/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import net.minecraft.client.Minecraft;

/**
 * Main-thread scheduling for 1.7.10 (no {@code Minecraft.addScheduledTask} name).
 * MCP: {@code func_152344_a} schedules on the client thread; {@code func_152345_ab} is
 * {@code isCallingFromMinecraftThread}.
 */
public final class ClientTasks {

    private ClientTasks() {}

    /** Runs {@code task} on the Minecraft client thread. */
    public static void run(Runnable task) {
        if (task == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            task.run();
            return;
        }
        if (mc.func_152345_ab()) {
            task.run();
        } else {
            mc.func_152344_a(task);
        }
    }
}
