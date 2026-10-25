package steambridge.steam;

public final class SteamServer extends SteamServerBase {
    public SteamServer() {
        super();
    }

    public SteamServer(AccessPolicy policy, String key) {
        super(policy, key, key);
    }

    public SteamServer(AccessPolicy policy, String key, String name) {
        super(policy, key, name);
    }

    @Override
    protected SteamRuntime<?, ?> runtime() {
        return SteamManager.getInstance();
    }

    @Override
    protected SteamBanStore bans() {
        return SteamSocial.Bans.get();
    }

    @Override
    protected String displayName(long steamId) {
        return SteamSocial.ProfileCache.get().getDisplayName(steamId);
    }

    @Override
    protected boolean createLoopbackBridge(
            int handle, long steamId, int port, java.util.function.IntConsumer bound) {
        return SteamTransport.createServerLoopbackBridge(handle, steamId, port, bound);
    }

    @Override
    protected void executeOnClient(Runnable task) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        mc.addScheduledTask(task);
    }

    @Override
    protected boolean integratedServerAvailable() {
        return net.minecraft.client.Minecraft.getMinecraft().getIntegratedServer() != null;
    }

    public boolean start() {
        return startHost();
    }
}
