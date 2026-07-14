# Steam Bridge - Minecraft Mod

[![Author](https://img.shields.io/badge/author-Ragalikx-blue)](https://github.com/Ragalikx)
[![Discord](https://img.shields.io/badge/Discord-Join-5865F2?logo=discord&logoColor=white)](https://discord.gg/2xBnJ7awRC)

🇷🇺 [Читать на русском](readmeru.md)

**Steam Bridge** is a mod that lets you host a Minecraft world for friends without VPN, proxy, or tunnel software.
It works at the network layer and routes traffic through the Steam Networking Sockets API, using it as the transport for all game traffic. This keeps latency low via Steam's global server infrastructure and allows stable connections even through strict NAT.

> **Discord is the main hub for the project.** Questions, development plans, release announcements, and bug reports all live there: **[discord.gg/2xBnJ7awRC](https://discord.gg/2xBnJ7awRC)**

---

## Supported versions

All active branches are listed below. Minecraft version links in the table point directly to the corresponding branch.

| Minecraft version | Platform | Loader version | Windows | Linux | macOS | Status |
|---|---|---|---|---|---|---|
| [1.21.1](https://github.com/Ragalikx/steam-bridge-mc/tree/NeoForge-1.21.1) | NeoForge | 21.1.234 | ✅ | ⏳ (Planned) | ❌ | Available |
| [1.21.1](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.21.1) | Fabric | 0.16.14 + API 0.116.13 | ✅ | ⏳ (Planned) | ❌ | Available |
| [1.20.1](https://github.com/Ragalikx/steam-bridge-mc/tree/NeoForge-1.20.1) | NeoForge/Forge | 47.1.106 | ✅ | ⏳ (Planned) | ❌ | Available |
| [1.20.1](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.20.1) | Fabric | ? | ? | ? | ❌ | In development |
| [1.16.5](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.16.5) | Forge | ? | ? | ? | ❌ | In development |
| [1.16.5](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.16.5) | Fabric | ? | ? | ? | ❌ | In development |
| [1.12.2](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.12.2) | Forge | 14.23.5.2860 | ✅ | ⏳ (Planned) | ❌ | Available |
| [1.7.10](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.7.10) | Forge | ? | ? | ? | ❌ | In development |

*(Fabric builds require Fabric API)*

---

## Developer info

Versions used during development. Native libraries are bundled with the mod, no extra linking steps needed.

| Minecraft version | Platform | Java version | Gradle version |
|---|---|---|---|
| 1.21.1 | NeoForge | Java 21 | 8.13 |
| 1.21.1 | Fabric | Java 21 | 8.13 |
| 1.20.1 | NeoForge/Forge | Java 17 | 8.13 |
| 1.12.2 | Forge | Java 8 | 4.10.3 |

---

## Priorities and technical notes

The main development priority is stable traffic routing and minimal connection latency.

One thing worth knowing: the mod uses AppID 480 (Spacewar), a legacy Valve test application well-suited for this purpose. The ID is hardcoded and cannot be changed via config. Using a real game's AppID (especially one with VAC or EAC anti-cheat) could cause issues with the license agreement and may result in a ban from that specific game. It also would not work as reliably as Spacewar.

---

## Configuration

- **Port 25565:** when hosting through Steam Bridge, make sure this port is free locally. If you need a regular LAN server alongside it, set a different virtual port in the config.
- **allowWithoutAuth:** when `true` (default), Steam Session Ticket checks are skipped. That can help behind strict or symmetric NAT, but weakens session security. Set `false` for stricter validation.
- **interceptUdp:** installs a JVM-wide `DatagramSocket` factory so voice-chat mods (Simple Voice Chat, Plasmo Voice, etc.) can ride Steam next to game traffic. Takes effect only at launch; cannot be toggled at runtime.

## Building a release jar

```text
# Windows (PowerShell): quotes around -P are required
.\gradlew.bat build "-PmodVersion=1.145"

# Resulting version: 1.145+mc1.21.1  (jar / fabric.mod.json / BuildInfo)
# Dev build without -P: 0.0.0-dev+mc1.21.1
# Requires Java 21 for the Gradle JVM (Loom).
```

---

## Licenses

- **Steam Bridge** : [MIT License](LICENSE). Copyright (c) 2026 [Ragalikx](https://github.com/Ragalikx).
- **steamworks4j** : [MIT License](third_party_licenses/steamworks4j_LICENSE.txt) (Daniel Ludwig / code-disaster).
- **Java Native Access (JNA)** : [Apache License 2.0](third_party_licenses/JNA_AL2.0.txt).

> **Disclaimer:** this project is educational and is not affiliated with Valve Corporation. "Steam" and the Steam logo are trademarks of Valve. The mod bundles `steam_api64.dll` for Steam networking calls; that file remains the property of Valve.

---

## Support the author

Donations are not required, but if the mod has been useful for playing with friends, any support is appreciated.

**Bitcoin (BTC)**
```
bc1q2e7hxvv90qm5menfc9m9nd8q43g4w6hmdfuhk3
```

**Litecoin (LTC)**
```
ltc1q075u480ug7c7wne9tv34yf8suskvtr68rd4q9y
```

**USDT (TRC-20 network, Tron)**
```
TVzTdidAdYnQyTth1RoY8duyHQnxcuHDrC
```
