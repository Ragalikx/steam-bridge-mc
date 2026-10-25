package steambridge.bootstrap;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class VersionMixinPlugin implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage) {}

    public String getRefMapperConfig() {
        return null;
    }

    public boolean shouldApplyMixin(String targetClass, String mixinClass) {
        return mixinClass.startsWith("steambridge." + VersionSelector.namespace() + ".mixin.");
    }

    public void acceptTargets(Set<String> own, Set<String> others) {}

    public List<String> getMixins() {
        if (net.neoforged.fml.loading.FMLLoader.getCurrent().getDist()
                != net.neoforged.api.distmarker.Dist.CLIENT) return List.of();
        String prefix = VersionSelector.namespace() + ".mixin.";
        return List.of(prefix + "ConnectScreenMixin", prefix + "ServerLoginHelloMixin");
    }

    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
