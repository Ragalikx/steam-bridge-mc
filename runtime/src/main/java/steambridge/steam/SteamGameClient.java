package steambridge.steam;

import steambridge.core.GameAttemptOwner;

public interface SteamGameClient extends GameAttemptOwner {
    void disconnect();

    boolean ownsConnection(int handle, long steamId);

    void onConnectionStatusChanged(
            int handle, long steamId, SteamConnectionStatus status, int oldState);

    void onLocalBridgeClosed(int handle, String reason);
}
