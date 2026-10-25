package steambridge.bootstrap;

public final class VersionSelector {
    private VersionSelector() {}

    public static String namespace() {
        String version =
                net.neoforged.fml.loading.FMLLoader.getCurrent().getVersionInfo().mcVersion();
        return namespace(version);
    }

    public static String namespace(String version) {
        for (String target : new String[] {"26.1", "26.2", "26.3"}) {
            if (version.equals(target) || version.startsWith(target + "."))
                return "mc.v" + target.replace(".", "");
        }
        throw new IllegalStateException("Unsupported Minecraft version: " + version);
    }
}
