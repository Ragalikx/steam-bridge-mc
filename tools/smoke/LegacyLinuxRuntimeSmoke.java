package steambridge.steam;

import steambridge.core.LinuxNativeRuntime;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.*;

public final class LegacyLinuxRuntimeSmoke {
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void main(String[] args) throws Exception {
        try {
            LinuxNativeRuntime.extract("steam_api");
            throw new AssertionError("Linux provider appeared without its addon");
        } catch (IllegalStateException missing) {
            if (!missing.getMessage().contains("Install SBM Linux Runtime")) throw missing;
        }
        URL[] files = {new File(args[0]).toURI().toURL(), new File(args[1]).toURI().toURL()};
        try (URLClassLoader addon =
                new URLClassLoader(files, LegacyLinuxRuntimeSmoke.class.getClassLoader())) {
            Class<?> bootstrap =
                    Class.forName("steambridge.linuxruntime.RuntimeBootstrap", true, addon);
            if (args[2].equals("fabric")) {
                Class<?> entrypoint =
                        Class.forName(
                                "net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint",
                                true,
                                addon);
                Object instance =
                        MethodHandleProxies.asInterfaceInstance(
                                entrypoint,
                                MethodHandles.lookup()
                                        .unreflect(bootstrap.getMethod("initialize")));
                entrypoint.getMethod("onPreLaunch").invoke(instance);
            } else {
                Class<?> loader =
                        Class.forName("steambridge.linuxruntime.RuntimeLoader", true, addon);
                Class<? extends Annotation> annotation =
                        (Class) Class.forName(args[2], true, addon);
                Annotation metadata = loader.getAnnotation(annotation);
                String key = args[3];
                Method value = annotation.getMethod(key);
                if (!"steambridge_linux_runtime".equals(value.invoke(metadata)))
                    throw new AssertionError("Runtime @Mod annotation does not match this loader");
                loader.getConstructor().newInstance();
            }
            bootstrap.getMethod("initialize").invoke(null);
            Path api = LinuxNativeRuntime.extract("steam_api");
            Path bridge = LinuxNativeRuntime.extract("steamworks4j");
            if (!api.getParent().equals(bridge.getParent()))
                throw new AssertionError("Split native bundle");
            NativeLinkSmoke.main(new String[0]);
        }
        System.out.println("Packaged legacy Linux runtime contract passed: " + args[2]);
    }
}
