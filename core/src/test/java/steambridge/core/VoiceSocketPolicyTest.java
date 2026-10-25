package steambridge.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class VoiceSocketPolicyTest {
    private StackTraceElement frame(String name) {
        return new StackTraceElement(name, "open", "Socket.java", 1);
    }

    @Test
    public void unrelatedSocketDoesNotClaimVoiceTunnel() {
        assertFalse(
                VoiceSocketPolicy.matches(
                        new StackTraceElement[] {frame("example.mod.Telemetry")}));
        assertFalse(
                VoiceSocketPolicy.matches(
                        new StackTraceElement[] {frame("de.maxhenkel.voicechatfake.Socket")}));
        assertTrue(
                VoiceSocketPolicy.matches(
                        new StackTraceElement[] {
                            frame("java.net.DatagramSocket"),
                            frame("de.maxhenkel.voicechat.plugins.impl.ClientVoicechatSocketImpl")
                        }));
        assertTrue(
                VoiceSocketPolicy.matches(
                        new StackTraceElement[] {frame("su.plo.voice.socket.LegacyUdpSocket")}));
    }
}
