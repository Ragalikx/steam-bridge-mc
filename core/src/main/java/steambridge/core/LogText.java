package steambridge.core;

public final class LogText {
    private LogText() {}

    public static String safe(String value) {
        if (value == null) return "";
        return value.replace("${", "$ {").replace('\r', ' ').replace('\n', ' ');
    }
}
