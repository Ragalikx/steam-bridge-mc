package steambridge.panama;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

public final class LinuxRuntimeSmoke {
    private static void verify(Path path) {
        try (SteamLibrary library = new SteamLibrary(path)) {
            for (String export : NativeBindings.all().keySet())
                if (!library.hasSymbol(export))
                    throw new IllegalStateException("Missing export: " + export);
        }
    }

    public static void main(String[] args) throws Exception {
        SteamPlatform platform = SteamPlatform.current();
        Path before = null;
        if (platform == SteamPlatform.WINDOWS_X64) {
            before = NativeRuntime.extract();
            verify(before);
        } else {
            try {
                NativeRuntime.extract();
                throw new AssertionError("Linux started without its runtime");
            } catch (IllegalStateException missing) {
                if (!missing.getMessage().contains("Install SBM Linux Runtime")) throw missing;
            }
        }
        try (URLClassLoader addon =
                new URLClassLoader(
                        new URL[] {Path.of(args[0]).toUri().toURL()},
                        LinuxRuntimeSmoke.class.getClassLoader())) {
            Class.forName("steambridge.linuxruntime.LinuxRuntime", true, addon)
                    .getConstructor()
                    .newInstance();
            Path selected = NativeRuntime.extract();
            if (!selected.getFileName().toString().equals(platform.library()))
                throw new AssertionError("Selected the other OS library");
            if (before != null && !before.equals(selected))
                throw new AssertionError("Linux addon changed the Windows library");
            verify(selected);
        }
        System.out.println("nativeExportChecks=" + NativeBindings.all().size());
        System.out.println("nativeExports=" + String.join(",", NativeBindings.all().keySet()));
        System.out.println("Packaged runtime registration and native linkage passed: " + platform);
    }
}
