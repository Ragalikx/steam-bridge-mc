package steambridge.core;

public final class VoiceSocketPolicy {
    private VoiceSocketPolicy() {}

    public static boolean createdByVoiceMod() {
        return matches(Thread.currentThread().getStackTrace());
    }

    static boolean matches(StackTraceElement[] trace) {
        for (StackTraceElement frame : trace) {
            String name = frame.getClassName();
            if (name.startsWith("de.maxhenkel.voicechat.") || name.startsWith("su.plo.voice."))
                return true;
        }
        return false;
    }
}
