package steambridge.steam;

public interface SteamGameServer {
    void stop();

    boolean isRunning();

    boolean ownsListenSocket(int handle);

    boolean ownsConnection(int handle);

    Object getVoiceSessionToken(long steamId);

    void onConnectionStatusChanged(
            int handle, long steamId, SteamConnectionStatus status, int oldState);

    void onLocalBridgeClosed(int handle);
}
