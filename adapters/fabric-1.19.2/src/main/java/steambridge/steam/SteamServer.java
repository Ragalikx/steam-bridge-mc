package steambridge.steam;

public final class SteamServer extends SteamServerBase {
    public SteamServer() {
        super();
    }

    public SteamServer(AccessPolicy policy, String key) {
        super(policy, key);
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
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(task);
    }

    @Override
    protected boolean integratedServerAvailable() {
        return net.minecraft.client.Minecraft.getInstance().getSingleplayerServer() != null;
    }

    public void start() {
        startHost();
    }
}
