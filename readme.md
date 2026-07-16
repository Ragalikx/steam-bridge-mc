# Steam Bridge - Minecraft Mod

[![Author](https://img.shields.io/badge/author-Ragalikx-blue)](https://github.com/Ragalikx)
[![Discord](https://img.shields.io/badge/Discord-Join-5865F2?logo=discord&logoColor=white)](https://discord.gg/2xBnJ7awRC)

🇷🇺 [Читать на русском](readmeru.md)

**Steam Bridge** is a mod that lets you host a Minecraft world for friends without VPN, proxy, or tunnel software.
It routes game traffic through the Steam Networking Sockets API (SDR / P2P). Latency stays low thanks to Steam's relay network, and connections usually work even behind strict NAT.

> **Discord is the main hub for the project.** Questions, plans, releases, and bug reports: **[discord.gg/2xBnJ7awRC](https://discord.gg/2xBnJ7awRC)**

---

## Supported versions

Minecraft version links open the matching branch.

| Minecraft | Platform | Loader version | Windows | Linux | macOS | Status |
|---|---|---|---|---|---|---|
| [1.21.1](https://github.com/Ragalikx/steam-bridge-mc/tree/NeoForge-1.21.1) | NeoForge | 21.1.234 | ✅ | ⏳ (In development) | ❌ | Available |
| [1.21.1](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.21.1) | Fabric | 0.16.14 + API 0.116.13 | ✅ | ⏳ (In development) | ❌ | Available |
| [1.20.1](https://github.com/Ragalikx/steam-bridge-mc/tree/NeoForge-1.20.1) | NeoForge/Forge | 47.1.106 | ✅ | ⏳ (In development) | ❌ | Available |
| [1.20.1](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.20.1) | Fabric | 0.16.14 + API 0.92.2 | ✅ | ⏳ (In development) | ❌ | Available |
| [1.19.2](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.19.2) | Forge | 43.5.0 | ✅ | ⏳ (In development) | ❌ | In development |
| [1.19.2](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.19.2) | Fabric | 0.16.14 + API 0.76.1 | ✅ | ⏳ (In development) | ❌ | In development |
| [1.16.5](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.16.5) | Forge | 36.2.42 | ✅ | ⏳ (In development) | ❌ | In development |
| [1.16.5](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.16.5) | Fabric | 0.14.25 + API 0.42.0 | ✅ | ⏳ (In development) | ❌ | In development |
| [1.12.2](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.12.2) | Forge | 14.23.5.2860 | ✅ | ⏳ (In development) | ❌ | Available |
| [1.8.9](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.8.9) | Forge | 11.15.1.2318 | ✅ | ⏳ (In development) | ❌ | In development |
| [1.7.10](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.7.10) | Forge | 10.13.4.1614 | ✅ | ⏳ (In development) | ❌ | In development |

*(Fabric builds need Fabric API)*

### Feature parity (all listed branches)

| Feature | Notes |
|---|---|
| Host world via Steam (Open via Steam / Share to LAN UI) | Yes |
| Friends picker to fill a SteamID when adding a server | Yes (Edit Server / Direct Connect) |
| Multiplayer list: SteamID entries get MOTD, status, no TCP ping, sort to top, avatar when ready | Yes |
| Join by SteamID (saved list or direct connect) | Yes |
| Access policy (friends / everyone) + transport route (auto / P2P / relay) | Yes |
| Host management (kick / ban list) | Yes |
| Voice UDP intercept (`interceptUdp`) | Modern branches (1.12.2+ where wired). **1.7.10 / 1.8.9: no voice chat port** |

---

## Developer info

What was used when building and testing each branch. Natives ship inside the mod jar (or come from the loader - see JNA notes).

| Minecraft | Platform | Java (compile / game) | Gradle JVM for build | Gradle | Build plugin | steamworks4j | JNA |
|---|---|---|---|---|---|---|---|
| 1.21.1 | NeoForge | 21 | 17+ (prefer 21) | 8.13 | NeoGradle userdev 7.0.170 | 1.10.0 | 5.14.0 (from NeoForge, not in mod jar) |
| 1.21.1 | Fabric | 21 | 17+ (prefer 21) | 8.13 | fabric-loom 1.10.5 | 1.10.0 | 5.14.0 (slim win64 in mod jar) |
| 1.20.1 | NeoForge/Forge | 17 | 17+ | 8.13 | MDG legacyforge 2.0.141 | 1.10.0 | 5.12.1 (from NeoForge/FML, not in mod jar) |
| 1.20.1 | Fabric | 17 | 17+ | 8.13 | fabric-loom 1.10.5 | 1.10.0 | 5.14.0 (slim win64 in mod jar) |
| 1.19.2 | Forge | 17 | 17+ | 8.13 | MDG legacyforge 2.0.141 | 1.10.0 | 5.12.1 (compileOnly, not in jar) |
| 1.19.2 | Fabric | 17 | 17+ | 8.13 | fabric-loom 1.10.5 | 1.10.0 | 5.14.0 (slim win64 in mod jar) |
| 1.16.5 | Forge | 8 | 8 or 17 | 7.5.1 | ForgeGradle 5.1.69 | 1.10.0 | 4.4.0 (game ships it; not shaded) |
| 1.16.5 | Fabric | 8 bytecode | 17+ (Loom 1.6.12) | 8.8 | fabric-loom 1.6.12 | 1.10.0 | 5.14.0 (slim win64 in mod jar) |
| 1.12.2 | Forge | 8 | 8 | 4.10.3 | ForgeGradle 3.+ | 1.10.0 | 5.14.0 (slim win64 in mod jar) |
| 1.8.9 | Forge | 8 | 8 | 4.10.3 | ForgeGradle 2.1-SNAPSHOT | 1.10.0 | 5.14.0 (slim win64 in mod jar) |
| 1.7.10 | Forge | 8 | 8 | 7.4.2 | ForgeGradle 1.2 (anatawa12) | 1.10.0 | 5.14.0 (slim win64 in mod jar) |

Notes:

- Builds/tests: Windows 11 (amd64). Linux: in development; macOS no.
- Steam AppID is fixed to **480 (Spacewar)**. Steam client must be running.
- Release versioning: `-PmodVersion=1.145` -> `1.145+mc<mc_version>`. Without `-P`: `0.0.0-dev+mc<mc_version>`.
- **Gradle JVM is not the same as compile target.** Fabric Loom 1.10.x and Gradle 8.13 need a **Java 17+** daemon even when the game is older. Toolchain only covers `compileJava`, not plugin load. Legacy Forge 1.7.10 / 1.8.9 / 1.12.2 must run Gradle on **Java 8**.
- NeoForge does not bundle JNA in the mod (module clash). Compile is pinned to the JNA version the loader already ships. Fabric and older Forge shade a trimmed win64-only JNA (except Forge 1.16.5, which uses the game JNA 4.4.0).

### Building a release jar

```text
# Windows (PowerShell): quotes around -P are required
# Set JAVA_HOME to the row "Gradle JVM for build" for that branch first.

$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.x.x-hotspot"   # modern Fabric / Neo / Forge 1.19+
# $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-8.0.x-hotspot" # Forge 1.7.10 / 1.8.9 / 1.12.2
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
.\gradlew.bat --stop
.\gradlew.bat build "-PmodVersion=1.145"

# Example: 1.145+mc1.20.1 or 1.145+mc1.7.10 (depends on branch)
# Dev without -P: 0.0.0-dev+mc...
```

Always run `.\gradlew.bat --stop` when switching `JAVA_HOME` so the old daemon dies.

---

## Priorities and technical notes

Priority is stable routing and usable latency for friends.

AppID 480 (Spacewar) is intentional. Do not swap it for a real game AppID (VAC/EAC risk and worse reliability).

---

## Configuration

- **Port 25565:** keep free when hosting via Steam Bridge. For a normal LAN server, pick another virtual port in config.
- **allowWithoutAuth:** `true` (default) skips Steam Session Ticket checks (helps with bad NAT, weaker security). `false` = strict checks.
- **interceptUdp:** (where supported) installs a JVM-wide `DatagramSocket` factory so voice mods (Simple Voice Chat, Plasmo Voice, etc.) can use Steam next to game traffic. Only at launch, not at runtime. Not on the 1.7.10 / 1.8.9 ports.

---

## Licenses

- **Steam Bridge** : [MIT License](LICENSE). Copyright (c) 2026 [Ragalikx](https://github.com/Ragalikx).
- **steamworks4j** : [MIT License](third_party_licenses/steamworks4j_LICENSE.txt) (Daniel Ludwig / code-disaster).
- **Java Native Access (JNA)** : [Apache License 2.0](third_party_licenses/JNA_AL2.0.txt).

> **Disclaimer:** educational project, not affiliated with Valve. "Steam" is a Valve trademark. The mod may bundle Steam native binaries for networking calls; those remain Valve property.

---

## Support the author

Donations are optional. If the mod helped you play with friends, any support is appreciated.

**Bitcoin (BTC)**
```
bc1q2e7hxvv90qm5menfc9m9nd8q43g4w6hmdfuhk3
```

**Litecoin (LTC)**
```
ltc1q075u480ug7c7wne9tv34yf8suskvtr68rd4q9y
```

**USDT (TRC-20, Tron)**
```
TVzTdidAdYnQyTth1RoY8duyHQnxcuHDrC
```
