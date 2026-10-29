package steambridge.core;

import java.util.UUID;

public final class SteamInvite {
    private static final String PREFIX = "sbm:1:";
    public final long host;
    public final int port;
    public final String connect;

    private SteamInvite(long host, int port, String connect) {
        this.host = host;
        this.port = port;
        this.connect = connect;
    }

    public static String create(long host, int port) {
        String value =
                PREFIX + host + ":" + port + ":" + UUID.randomUUID().toString().replace("-", "");
        if (parse(value) == null) throw new IllegalArgumentException("Invalid invite target");
        return value;
    }

    public static SteamInvite parse(String connect) {
        if (connect == null || connect.length() > 100 || !connect.startsWith(PREFIX)) return null;
        String[] parts = connect.substring(PREFIX.length()).split(":", -1);
        if (parts.length != 3
                || parts[0].length() != 17
                || !parts[1].matches("[0-9]{1,5}")
                || !parts[2].matches("[0-9a-f]{32}")) return null;
        long host = SteamAddress.parse(parts[0]);
        int port = Integer.parseInt(parts[1]);
        if (host == 0 || port > 65534) return null;
        return new SteamInvite(host, port, connect);
    }

    public boolean matches(long sender, int localPort, String presence) {
        return sender == host && localPort == port && connect.equals(presence);
    }
}
