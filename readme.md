# Steam Bridge

[Русский](readmeru.md) · [Discord](https://discord.gg/2xBnJ7awRC) · [Author](https://github.com/Ragalikx)

Steam Bridge lets friends join your single-player Minecraft world through Steam, without port forwarding, a VPN or a public IP. Traffic goes through Steam Networking Sockets: a direct P2P route when one can be found, otherwise a Valve relay. Both players need Steam running.

## Supported versions

| Minecraft | Loader | Java | Windows | Linux | Voice tunnel |
| --- | --- | --- | --- | --- | --- |
| 1.7.10 | Forge | 8 | ✅ | ✅ | ❌ |
| 1.8.9 | Forge | 8 | ✅ | ✅ | ❌ |
| 1.12.2 | Forge | 8 | ✅ | ✅ | ✅ |
| 1.16.5 | Forge | 8 | ✅ | ✅ | ✅ |
| 1.19.2 | Forge | 17 | ✅ | ✅ | ✅ |
| 1.19.2 | Fabric | 17 | ✅ | ✅ | ✅ |
| 1.20.1 | Fabric | 17 | ✅ | ✅ | ✅ |
| 1.20.1 | NeoForge | 17 | ✅ | ✅ | ✅ |
| 1.21.1 | Fabric | 21 | ✅ | ✅ | ✅ |
| 1.21.1 | NeoForge | 21 | ✅ | ✅ | ✅ |
| 26.1 | NeoForge | 25 | ✅ | ✅ | ✅ |
| 26.2 | NeoForge | 25 | ✅ | ✅ | ✅ |
| 26.3 | NeoForge | 25 | ✅ | ✅ | ✅ |

NeoForge 26.1, 26.2 and 26.3 share one JAR, `steambridge-neoforge-26.x-*.jar`. NeoForge 1.20.1 means the `net.neoforged:forge` 47.1.x line. Host and guest need the same Minecraft version, loader and Steam Bridge version. Forge 1.7.10 to 1.12.2 refuse a connection when the Steam Bridge versions differ.

## Installation

1. Put the Steam Bridge JAR for your version into `mods` on both computers. Fabric also needs Fabric API.
2. On Linux, also add the SBM Linux Runtime for your Java group (see below).
3. Start Steam, then Minecraft.
4. Host: open a single-player world and press **Open via Steam** on the LAN screen.
5. Guest: pick the host in the Steam friends screen, or enter the host's SteamID64 in Direct Connect.

## Linux

Linux x64 works with the main JAR plus one SBM Linux Runtime JAR. The runtime only carries the Linux Steam libraries; everything else stays in the main mod.

| Runtime | For |
| --- | --- |
| `steambridge-linux-runtime-java8` | Forge 1.7.10, 1.8.9, 1.12.2, 1.16.5 |
| `steambridge-linux-runtime-java17` | Forge 1.19.2, Fabric 1.19.2 and 1.20.1, NeoForge 1.20.1 |
| `steambridge-linux-runtime-java21` | Fabric and NeoForge 1.21.1 |
| `steambridge-linux-runtime-neoforge-26.x` | NeoForge 26.1, 26.2, 26.3 |

Pick the runtime by Minecraft version and loader, not by the Java you launch with. Install only one.

Requirements and known limits:

- **Use a launcher that is not sandboxed.** Minecraft must see the Steam process. A Flatpak launcher (for example Prism Launcher from Flathub) runs the game in its own process namespace, so the Steam library cannot find the running Steam client and exits the game. Use the Prism Launcher AppImage, a distribution package or another native launcher.
- **Steam itself** should be the regular desktop Steam client of the same user. Flatpak Steam and Steam Deck have not been tested.
- **Linux as guest:** on the tested setup, Steam's direct (ICE) route negotiation made the Steam networking service stall when Linux joined a host. Set `"iceEnabled": false` in `config/steambridge.json` on the Linux computer. Traffic then always uses a Valve relay. Linux as host works with the default settings.
- ARM, 32-bit Java and macOS are not supported.

Tested on Fedora 44 (Wayland) with the Prism Launcher 11.1.1 AppImage, the native Steam client and Temurin/Corretto JDKs 8, 17, 21 and 25, against a Windows 11 computer in both host and guest roles.

## Voice chat

Simple Voice Chat and Plasmo Voice work through a second Steam connection next to the game connection. Install the voice mod yourself on both computers, in the same version. Use only one of the two: they cannot share the voice tunnel. Forge 1.7.10 and 1.8.9 have no voice tunnel.

Simple Voice Chat is connected through its socket API. Older Simple Voice Chat builds without that API fall back to a JVM socket factory that only touches sockets created by the voice mods. Plasmo Voice uses its addon and UDP client APIs with a small local relay. Voice API, Plasmo, Kotlin and ASM classes are not bundled.

## Configuration

All versions read `config/steambridge.json`. A missing file is created on first start; old Forge and NeoForge `steambridge-client.toml`, `steambridge-common.toml` and `steambridge.cfg` files are migrated and kept. An invalid file is reported in the log and the previous settings stay active. Restart Minecraft after editing. See the [example configuration](docs/steambridge.example.json).

| Setting | Default | Meaning |
| --- | --- | --- |
| `allowWithoutAuth` | `true` | Allow Steam connections without Steam authentication |
| `virtualPort` | `0` | Steam virtual port, 0 to 65534; voice uses the next port |
| `interceptUdp` | `true` | Route voice mod traffic through Steam |
| `disableOnlineModeOnPublish` | `true` | Turn off online mode when the world is opened, so non-premium friends can join |
| `sameNameAsHost` | `allow` | `allow` lets a guest with the host's name in as a separate player, `kick` refuses it |
| `timeoutInitialSec` | `30` | Time allowed for a connection to establish |
| `timeoutConnectedSec` | `60` | Time without traffic before a connection is closed |
| `stunServers` | Google STUN | STUN servers for direct route discovery |
| `iceEnabled` | `true` | `false` forces Valve relays for hosting and joining |

Hosts choose who may join (everyone or Steam friends only) and the route (automatic, P2P preferred or relay only) on the LAN screen, and can send Steam invitations from session management. [Invitations](docs/STEAM_INVITES.md).

## Development

```text
core/             connection attempts, handle ownership, settings, bounded caches, UDP sockets
steam-jna/        JNA backend for the Java 8 to 21 targets and voice adapter sources
steam-panama/     Java 25 FFM backend for 26.x
transport-netty/  bounded queues, backpressure and the local bridge to Minecraft
runtime/          shared manager, client and server lifecycle, bans and world settings
integrations/     voice API contract checks against released JARs
adapters/         Minecraft and loader integration per target
build-logic/      shared-source compilation and packaging
tools/            builds, tests and native checks
```

Shared sources are compiled into every adapter JAR, so players install a single mod. Each adapter keeps its own Gradle wrapper because the oldest ForgeGradle versions cannot run under a modern Gradle.

Install JDK 8, 17, 21 and 25 and set `JAVA_HOME_8`, `JAVA_HOME_17`, `JAVA_HOME_21` and `JAVA_HOME_25`, or use the cache filled by `tools/fetch_jdks.py` on Windows. Python 3 is needed for the tools.

```text
python tools/test.py
python tools/verify_voice.py --download
python tools/verify_abi.py --download
python tools/build.py --variant fabric-1.21.1
python tools/build.py --variant neoforge-26.x
python tools/build.py --version 1.3.0
```

Builds default to version `0.0.0-dev`. Main JARs, SHA-256 files and `build-results.json` go to `dist/windows-x64/`, Linux runtimes to `dist/addons/`. The build checks each JAR for the expected Java target, bundled natives, licences, voice integration and mixin packaging. The ABI checks compare the JNA and Panama bindings with a pinned Steamworks SDK 1.62 snapshot. [Testing](docs/TESTING.md), [Linux runtime](docs/LINUX_RUNTIME.md), [Steamworks ABI](docs/STEAMWORKS_ABI.md), [reliability](docs/RELIABILITY.md), [logging](docs/LOGGING.md), [26.x port](docs/NEOFORGE_26X.md).

## License

Steam Bridge is [MIT licensed](LICENSE), copyright Ragalikx. Required dependency notices are included in the mod JARs. Steam belongs to Valve; this project is not affiliated with Valve.

The [previous per-branch documentation](docs/legacy/README.md) is kept for reference and does not describe the current builds.
