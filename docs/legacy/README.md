# Steam Bridge - Minecraft Mod

[![Author](https://img.shields.io/badge/author-Ragalikx-blue)](https://github.com/Ragalikx)
[![Discord](https://img.shields.io/badge/Discord-Join-5865F2?logo=discord&logoColor=white)](https://discord.gg/2xBnJ7awRC)

🇷🇺 [Читать на русском](readmeru.md)

**Steam Bridge** is a mod that lets you open a world to friends without VPN, proxy, or tunnel software.
Game traffic goes through Steam Networking Sockets (SDR / P2P). Valve's relay network usually keeps latency as low as it can get, and connections work through most strict NAT setups.

> **Discord is the main hub for the project.** Questions, plans, releases, and bugs: **[discord.gg/2xBnJ7awRC](https://discord.gg/2xBnJ7awRC)**

---

## Supported versions

Minecraft version links open the matching code branch.

<table>
<thead>
<tr>
<th rowspan="2">Minecraft</th>
<th rowspan="2">Platform</th>
<th rowspan="2">Loader version</th>
<th rowspan="2">UDP Tunnel*</th>
<th colspan="3">OS</th>
<th rowspan="2">Status</th>
</tr>
<tr>
<th>Win</th>
<th>Linux</th>
<th>macOS</th>
</tr>
</thead>
<tbody>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/NeoForge-1.21.1">1.21.1</a></td>
<td>NeoForge</td>
<td>21.1.234</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Available</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.21.1">1.21.1</a></td>
<td>Fabric</td>
<td>0.16.14 + API 0.116.13</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Available</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/NeoForge-1.20.1">1.20.1</a></td>
<td>NeoForge/Forge</td>
<td>47.1.106</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Available</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.20.1">1.20.1</a></td>
<td>Fabric</td>
<td>0.16.14 + API 0.92.2</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Available</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.19.2">1.19.2</a></td>
<td>Forge</td>
<td>43.5.0</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Available</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.19.2">1.19.2</a></td>
<td>Fabric</td>
<td>0.16.14 + API 0.76.1</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Available</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.16.5">1.16.5</a></td>
<td>Forge</td>
<td>36.2.42</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Available</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.16.5">1.16.5</a></td>
<td>Fabric</td>
<td></td>
<td>⏳</td>
<td>⏳</td>
<td>⏳</td>
<td>❌</td>
<td>In development</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.12.2">1.12.2</a></td>
<td>Forge</td>
<td>14.23.5.2860</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Available</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.8.9">1.8.9</a></td>
<td>Forge</td>
<td>11.15.1.2318</td>
<td>❌</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Available</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.7.10">1.7.10</a></td>
<td>Forge</td>
<td>10.13.4.1614</td>
<td>❌</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Available</td>
</tr>
</tbody>
</table>

\*Fabric builds need Fabric API.

\*UDP Tunnel: optional JVM-wide `DatagramSocket` intercept (`interceptUdp`). Game traffic already goes over Steam; this also routes **UDP from voice mods** (Simple Voice Chat, Plasmo Voice, etc.) through the same Steam channel. Config toggle. Not on 1.7.10 / 1.8.9.

---

## Developer info

| Minecraft | Platform | Java (compile) | JVM for Gradle daemon | Gradle wrapper | Build plugin | steamworks4j | JNA |
|---|---|---|---|---|---|---|---|
| 1.21.1 | NeoForge 21.1.234 | 21 (`toolchain`) | 17+ (prefer 21) | 8.13 | `net.neoforged.gradle.userdev` **7.0.170** | 1.10.0 | 5.14.0 **compileOnly** (loader provides it; not in mod jar) |
| 1.21.1 | Fabric loader 0.16.14 / API 0.116.13+1.21.1 | 21 (`toolchain` + `release 21`) | 17+ (prefer 21) | 8.13 | `fabric-loom` **1.10.5** | 1.10.0 | 5.14.0 **shade** slim win64 (`slimJnaJar`) |
| 1.20.1 | NeoForge 1.20.1-47.1.106 | 17 (`toolchain`) | 17+ | 8.13 | `net.neoforged.moddev.legacyforge` **2.0.141** | 1.10.0 | 5.12.1 **compileOnly** (loader provides it; not in jar) |
| 1.20.1 | Fabric loader 0.16.14 / API 0.92.2+1.20.1 | 17 (`toolchain` + `release 17`) | 17+ | 8.13 | `fabric-loom` **1.10.5** | 1.10.0 | 5.14.0 **shade** slim win64 |
| 1.19.2 | MinecraftForge 1.19.2-43.5.0 | 17 (`toolchain`) | 17+ | 8.13 | `net.neoforged.moddev.legacyforge` **2.0.141** (builds **MinecraftForge**, not NeoForge) | 1.10.0 | 5.12.1 **compileOnly** (not in jar) |
| 1.19.2 | Fabric loader 0.16.14 / API 0.76.1+1.19.2 | 17 (`toolchain` + `release 17`) | 17+ | 8.13 | `fabric-loom` **1.10.5** | 1.10.0 | 5.14.0 **shade** slim win64 |
| 1.16.5 | MinecraftForge 1.16.5-36.2.42 | 8 (`toolchain` 8) | 8 or 17 | 7.5.1 | `net.minecraftforge.gradle` **5.1.69** | 1.10.0 | 4.4.0 **compileOnly** (game already has JNA 4.4.0; do not shade 5.x) |
| 1.16.5 | Fabric loader 0.14.25 / API 0.42.0+1.16 | 8 (`sourceCompatibility` + `release 8`) | 17+ (Gradle 8.8) | 8.8 | `fabric-loom` **1.6.12** | 1.10.0 | 5.14.0 **shade** slim win64 |
| 1.12.2 | MinecraftForge 1.12.2-14.23.5.2860 | 8 | 8 | 4.10.3 | classpath `net.minecraftforge.gradle:ForgeGradle:`**`3.+`** (dynamic range, `changing: true`) | 1.10.0 | 5.14.0 **shade** slim win64 + trim classes |
| 1.8.9 | MinecraftForge 1.8.9-11.15.1.2318-1.8.9 | 8 | 8 | 4.10.3 | classpath `net.minecraftforge.gradle:ForgeGradle:`**`2.1-SNAPSHOT`** | 1.10.0 | 5.14.0 **shade** (win64 natives; non-Windows natives stripped) |
| 1.7.10 | MinecraftForge 1.7.10-10.13.4.1614-1.7.10 | 8 | 8 | 7.4.2 | classpath `com.anatawa12.forge:ForgeGradle:`**`1.2-1.1.+`** (`changing: true`; apply plugin `forge`) | 1.10.0 | 5.14.0 **shade**: win64 natives only, **full** JNA Java classes (LaunchWrapper `Native.initIDs`) |

Notes:

- Build and tests: Windows 11 (amd64). Linux: in development; macOS: no.
- Steam AppID: **480 (Spacewar)**. Steam client must be running.
- Release: `-PmodVersion=1.145` -> `1.145+mc<mc_version>`. Without `-P`: `0.0.0-dev+mc<mc_version>`.
- **Gradle daemon JVM != compile target.** Loom **1.10.5** + Gradle **8.13** need **Java 17+** for the daemon. Toolchain / `options.release` only affect compilation. Forge **1.7.10 / 1.8.9 / 1.12.2**: Gradle on **Java 8** only.
- **Forge 1.19.2:** build plugin is NeoForged **ModDevGradle legacyforge**, game dependency is **MinecraftForge** `1.19.2-43.5.0` (`legacyForge { version = ... }`), not NeoForge.
- **NeoForge 1.20.1:** same plugin `moddev.legacyforge` **2.0.141**, but `neoForgeVersion = 1.20.1-47.1.106` (coordinate `net.neoforged:forge`).
- **JNA in jar:** NeoForge / Forge 1.19.2 / Forge 1.16.5: **no**. Fabric: slim win64 shade. Forge 1.12.2: aggressive class trim + win64. Forge 1.7.10: **full** JNA classes + win64 natives (not the same as Fabric slim).
- **Dynamic pins:** ForgeGradle **`3.+`** and **`1.2-1.1.+`** resolve the newest matching artifact at build time (no single frozen patch version in the script).

### Building a release jar

```text
# Windows (PowerShell): quotes around -P are required
# JAVA_HOME = "JVM for Gradle daemon" column for that branch

$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.x.x-hotspot"   # modern Fabric / Neo / Forge 1.19+
# $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-8.0.x-hotspot" # Forge 1.7.10 / 1.8.9 / 1.12.2
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
.\gradlew.bat --stop
.\gradlew.bat build "-PmodVersion=1.145"

# Example: 1.145+mc1.20.1 or 1.145+mc1.7.10 (depends on branch)
# Dev without -P: 0.0.0-dev+mc...
```

After changing `JAVA_HOME`, always run `.\gradlew.bat --stop`, or the old daemon stays alive.

---

## Important notes

Do not use a real game AppID. That can cause VAC/EAC issues, and the mod is not guaranteed to work in that setup.

---

## Configuration

- **Port 25565:** must be available when hosting through Steam Bridge. If another application is already using it, choose a different port in the mod configuration.
- **allowWithoutAuth:** `true` (default) skips Steam Session Ticket checks (easier with bad NAT, weaker security). `false` = strict checks.
- **interceptUdp:** (where present) installs a JVM-wide `DatagramSocket` factory so voice mods (Simple Voice Chat, Plasmo Voice, etc.) go through Steam next to game traffic. Launch-time only. Not on 1.7.10 / 1.8.9.

---

## Licenses

- **Steam Bridge** : [MIT License](LICENSE). Copyright (c) 2026 [Ragalikx](https://github.com/Ragalikx).
- **steamworks4j** : [MIT License](third_party_licenses/steamworks4j_LICENSE.txt) (Daniel Ludwig / code-disaster).
- **Java Native Access (JNA)** : [Apache License 2.0](third_party_licenses/JNA_AL2.0.txt).

> **Steam Bridge is not affiliated with Valve. "Steam" is a Valve trademark. The mod uses official Steamworks SDK libraries for networking. All rights to those libraries belong to Valve.**

---

## Support the author

Support is fully optional. If you want to fund development, use any of the options below:

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
