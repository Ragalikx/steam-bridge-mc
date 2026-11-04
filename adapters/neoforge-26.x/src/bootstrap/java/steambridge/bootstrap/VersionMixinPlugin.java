package steambridge.bootstrap;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Shared by steambridge.v261/v262/v263.mixins.json. Each config's package is exactly one
 * steambridge.mc.v26X.mixin package: Mixin refuses direct loading of any class inside a declared
 * mixin package, so a broader package would block the mod's own classes. Only the config matching
 * the running game version contributes mixins.
 */
public final class VersionMixinPlugin implements IMixinConfigPlugin {
    private String mixinPackage = "";

    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage == null ? "" : mixinPackage;
    }

    public String getRefMapperConfig() {
        return null;
    }

    public boolean shouldApplyMixin(String targetClass, String mixinClass) {
        return mixinClass.startsWith(currentPackage() + ".");
    }

    public void acceptTargets(Set<String> own, Set<String> others) {}

    public List<String> getMixins() {
        if (net.neoforged.fml.loading.FMLLoader.getCurrent().getDist()
                != net.neoforged.api.distmarker.Dist.CLIENT) return List.of();
        if (!mixinPackage.equals(currentPackage())) return List.of();
        return List.of("ConnectScreenMixin", "ServerLoginHelloMixin");
    }

    private static String currentPackage() {
        return "steambridge." + VersionSelector.namespace() + ".mixin";
    }

    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
