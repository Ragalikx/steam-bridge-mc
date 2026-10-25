package steambridge.steam;

import static org.junit.Assert.*;

import org.junit.Test;

public class SteamNativeLibrariesTest {
    @Test
    public void matchesNativeFilesForWindowsAndLinux() {
        assertEquals("windows-x64", SteamNativeLibraries.platform("Windows 11", "amd64"));
        assertEquals("linux-x64", SteamNativeLibraries.platform("Linux", "x86_64"));
        assertEquals("steam_api64.dll", SteamNativeLibraries.fileName("steam_api", "windows-x64"));
        assertEquals("libsteam_api.so", SteamNativeLibraries.fileName("steam_api", "linux-x64"));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsArmInsteadOfLoadingX86Binary() {
        SteamNativeLibraries.platform("Linux", "aarch64");
    }
}
