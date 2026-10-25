package steambridge.core;

public final class SteamAddress {
    private SteamAddress() {}

    public static long parse(String address) {
        if (address == null) return 0L;
        String host = address.trim();
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        if (host.length() != 17) return 0L;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c < '0' || c > '9') return 0L;
        }
        try {
            long id = Long.parseLong(host);
            long account = id & 0xffffffffL;
            int instance = (int) (id >>> 32) & 0xfffff;
            int type = (int) (id >>> 52) & 15;
            int universe = (int) (id >>> 56);
            return account != 0 && instance == 1 && type == 1 && universe == 1 ? id : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
