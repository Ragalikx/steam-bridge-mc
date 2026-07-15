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
| [1.21.1](https://github.com/Ragalikx/steam-bridge-mc/tree/NeoForge-1.21.1) | NeoForge | 21.1.234 | ✅ | ✅ | ❌ | Available |
| [1.21.1](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.21.1) | Fabric | 0.16.14 + API 0.116.13 | ✅ | ✅ | ❌ | Available |
| [1.20.1](https://github.com/Ragalikx/steam-bridge-mc/tree/NeoForge-1.20.1) | NeoForge/Forge | 47.1.106 | ✅ | ✅ | ❌ | Available |
| [1.20.1](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.20.1) | Fabric | 0.16.14 + API 0.92.2 | ✅ | ✅ | ❌ | Available |
| [1.16.5](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.16.5) | Forge | ? | ? | ? | ❌ | In development |
| [1.16.5](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.16.5) | Fabric | ? | ? | ? | ❌ | In development |
| [1.12.2](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.12.2) | Forge | 14.23.5.2860 | ✅ | ✅ | ❌ | Available |
| [1.7.10](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.7.10) | Forge | ? | ? | ? | ❌ | In development |

*(Fabric builds need Fabric API)*

---

## Developer info

What was used when building and testing each available branch. Natives ship inside the mod jar (or come from the loader - see JNA notes).

| Minecraft | Platform | Java | Gradle | steamworks4j | JNA |
|---|---|---|---|---|---|
| 1.21.1 | NeoForge | 21 | 8.13 | 1.10.0 | 5.14.0 (from NeoForge, not in mod jar) |
| 1.21.1 | Fabric | 21 | 8.13 | 1.10.0 | 5.14.0 (slim win64 in mod jar) |
| 1.20.1 | NeoForge/Forge | 17 | 8.13 | 1.10.0 | 5.12.1 (from NeoForge/FML, not in mod jar) |
| 1.20.1 | Fabric | 17 | 8.13 | 1.10.0 | 5.14.0 (slim win64 in mod jar) |
| 1.12.2 | Forge | 8 | 4.10.3 | 1.10.0 | 5.14.0 (slim win64 in mod jar) |

Notes:

- Builds/tests: Windows 11 (amd64). Linux run supported; macOS no.
- Steam AppID is fixed to **480 (Spacewar)**. Steam client must be running.
- Release versioning on modern branches: `-PmodVersion=1.145` -> `1.145+mc<mc_version>`. Without `-P`: `0.0.0-dev+mc<mc_version>`.
- NeoForge does not bundle JNA in the mod (module clash). Compile is pinned to the JNA version the loader already ships. Fabric and 1.12.2 shade a trimmed win64-only JNA.

### Building a release jar

```text
# Windows (PowerShell): quotes around -P are required
.\gradlew.bat build "-PmodVersion=1.145"

# Example: 1.145+mc1.20.1 or 1.145+mc1.21.1 (depends on branch)
# Dev without -P: 0.0.0-dev+mc...
```

---

## Priorities and technical notes

Priority is stable routing and usable latency for friends.

AppID 480 (Spacewar) is intentional. Do not swap it for a real game AppID (VAC/EAC risk and worse reliability).

---

## Configuration

- **Port 25565:** keep free when hosting via Steam Bridge. For a normal LAN server, pick another virtual port in config.
- **allowWithoutAuth:** `true` (default) skips Steam Session Ticket checks (helps with bad NAT, weaker security). `false` = strict checks.
- **interceptUdp:** installs a JVM-wide `DatagramSocket` factory so voice mods (Simple Voice Chat, Plasmo Voice, etc.) can use Steam next to game traffic. Only at launch, not at runtime.

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
