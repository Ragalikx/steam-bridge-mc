package steambridge.core;

import java.net.InetSocketAddress;

public interface VoiceTransport {
    boolean isClientActive();

    VoiceClient voiceClient();

    boolean claimVoiceClient(VoiceClient client, int port);

    void releaseVoiceClient(VoiceClient client);

    void sendFromClientImpl(byte[] data);

    default void sendFromClient(VoiceClient owner, byte[] data) {
        if (voiceClient() == owner) sendFromClientImpl(data);
    }

    void registerBoundPort(int port);

    default void registerBoundAddress(InetSocketAddress address) {
        registerBoundPort(address.getPort());
    }

    default void unregisterBoundPort(int port) {}

    default void registerBoundAddress(Object owner, InetSocketAddress address) {
        registerBoundAddress(address);
    }

    default void unregisterBoundAddress(Object owner, int port) {
        unregisterBoundPort(port);
    }
}
