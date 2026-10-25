package steambridge.voice;

import com.codedisaster.steamworks.SteamID;

import steambridge.core.ConnectionRegistry;
import steambridge.core.GameAttemptOwner;

public interface VoiceNativeDriver {
    int createListenSocketP2P(int port);

    boolean closeListenSocket(int handle);

    boolean acceptConnection(int handle);

    boolean closeConnection(int handle, int reason, String debug);

    int connectP2P(SteamID host, int port, Object owner, ConnectionRegistry.Kind kind);

    boolean ownsConnectionOwner(int handle, Object owner);

    void sendVoiceBytes(int handle, byte[] data);

    GameAttemptOwner getActiveClient();

    Object getVoiceSessionToken(long steamId);
}
