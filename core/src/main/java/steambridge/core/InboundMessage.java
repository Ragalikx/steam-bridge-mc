package steambridge.core;

public interface InboundMessage {
    int getConnection();

    byte[] getData();
}
