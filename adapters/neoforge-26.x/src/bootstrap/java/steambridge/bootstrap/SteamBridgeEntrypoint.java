package steambridge.bootstrap;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(value = "steambridge", dist = Dist.CLIENT)
public final class SteamBridgeEntrypoint {
    public SteamBridgeEntrypoint(IEventBus events, ModContainer container) {
        try {
            String name = "steambridge." + VersionSelector.namespace() + ".SteamBridgeMod";
            Class.forName(name)
                    .getConstructor(IEventBus.class, ModContainer.class)
                    .newInstance(events, container);
        } catch (ReflectiveOperationException failure) {
            Throwable cause =
                    failure instanceof java.lang.reflect.InvocationTargetException invocation
                            ? invocation.getCause()
                            : failure;
            throw new IllegalStateException("Steam Bridge adapter initialization failed", cause);
        }
    }
}
