package steambridge;

import steambridge.platform.Services;

public class SteamBridge {

  public static final String VERSION = BuildInfo.VERSION;

  public static void init() {

    Constants.LOG.info("=== Steam Bridge pre-init ({} 1.21.1) v{} ===", Services.PLATFORM.getPlatformName(), VERSION);
  }
}