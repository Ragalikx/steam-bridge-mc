package steambridge.linuxruntime;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

import steambridge.panama.NativeRuntime;

@Mod(value = "steambridge_linux_runtime", dist = Dist.CLIENT)
public final class LinuxRuntime {
    public LinuxRuntime() {
        NativeRuntime.registerLinux(
                1,
                () ->
                        LinuxRuntime.class.getResourceAsStream(
                                "/steambridge/linux-runtime/linux-x64/libsteam_api.so"));
    }
}
