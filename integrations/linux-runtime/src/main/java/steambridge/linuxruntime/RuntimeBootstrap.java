package steambridge.linuxruntime;

import steambridge.core.LinuxNativeRuntime;

public final class RuntimeBootstrap {
    private static boolean registered;

    private RuntimeBootstrap() {}

    public static synchronized void initialize() {
        if (registered) return;
        LinuxNativeRuntime.register(
                1,
                name ->
                        RuntimeBootstrap.class.getResourceAsStream(
                                "/steambridge/linux-runtime/linux-x64/" + name));
        registered = true;
    }
}
