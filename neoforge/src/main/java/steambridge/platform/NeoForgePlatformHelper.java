package steambridge.platform;

import java.nio.file.Path;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import steambridge.platform.services.IPlatformHelper;

public class NeoForgePlatformHelper implements IPlatformHelper {

  @Override
  public String getPlatformName() {

    return "NeoForge";
  }

  @Override
  public boolean isModLoaded(String modId) {

    return ModList.get().isLoaded(modId);
  }

  @Override
  public boolean isDevelopmentEnvironment() {

    return !FMLLoader.isProduction();
  }

  @Override
  public Path getGameDirectory() {

    return FMLLoader.getGamePath();
  }
}