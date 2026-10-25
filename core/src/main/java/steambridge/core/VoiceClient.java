package steambridge.core;

import java.net.InetAddress;

public interface VoiceClient {
    boolean receiveVoice(byte[] data, InetAddress address, int port);

    void closeVoice();
}
