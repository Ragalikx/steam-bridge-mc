# Steam Bridge - Minecraft Mod

[![Author](https://img.shields.io/badge/author-Ragalikx-blue)](https://github.com/Ragalikx)
[![Discord](https://img.shields.io/badge/Discord-Join-5865F2?logo=discord&logoColor=white)](https://discord.gg/2xBnJ7awRC)

🇷🇺 [Читать на русском](readmeru.md)

**Steam Bridge** is a mod that lets you open a world to friends without VPN, proxy, or tunnel software.
Game traffic goes through Steam Networking Sockets (SDR / P2P). Valve's relay network usually keeps latency as low as it can get, and connections work through most strict NAT setups.

> **Discord is the main hub for the project.** Questions, plans, releases, and bugs: **[discord.gg/2xBnJ7awRC](https://discord.gg/2xBnJ7awRC)**

---

## Supported versions

All versions are built from this branch.

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
<th>Linux**</th>
<th>macOS</th>
</tr>
</thead>
<tbody>
<tr><td>26.3</td><td>NeoForge</td><td>26.3.0.85</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Available</td></tr>
<tr><td>26.2</td><td>NeoForge</td><td>26.2.0.93</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Available</td></tr>
<tr><td>26.1</td><td>NeoForge</td><td>26.1.0.19-beta</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Available</td></tr>
<tr><td>1.21.1</td><td>NeoForge</td><td>21.1.234</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Available</td></tr>
<tr><td>1.21.1</td><td>Fabric</td><td>0.16.14 + API 0.116.13</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Available</td></tr>
<tr><td>1.20.1</td><td>NeoForge/Forge</td><td>47.1.106</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Available</td></tr>
<tr><td>1.20.1</td><td>Fabric</td><td>0.16.14 + API 0.92.2</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Available</td></tr>
<tr><td>1.19.2</td><td>Forge</td><td>43.5.0</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Available</td></tr>
<tr><td>1.19.2</td><td>Fabric</td><td>0.16.14 + API 0.76.1</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Available</td></tr>
<tr><td>1.16.5</td><td>Forge</td><td>36.2.42</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Available</td></tr>
<tr><td>1.12.2</td><td>Forge</td><td>14.23.5.2860</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Available</td></tr>
<tr><td>1.8.9</td><td>Forge</td><td>11.15.1.2318</td><td>❌</td><td>✅</td><td>✅</td><td>❌</td><td>Available</td></tr>
<tr><td>1.7.10</td><td>Forge</td><td>10.13.4.1614</td><td>❌</td><td>✅</td><td>✅</td><td>❌</td><td>Available</td></tr>
</tbody>
</table>

NeoForge 26.1, 26.2 and 26.3 use one jar, `steambridge-neoforge-26.x`. Fabric builds need Fabric API. Host and guest need the same Minecraft version, loader and Steam Bridge version.

\*UDP Tunnel: routes **UDP from voice mods** (Simple Voice Chat, Plasmo Voice) through Steam next to game traffic (`interceptUdp`). Not on 1.7.10 / 1.8.9.

\*\*Linux needs the [Linux Runtime](#linux) next to the main mod.

---

## Linux

Linux x64 uses the same main jar plus one **SBM Linux Runtime** jar. The runtime only carries the Linux Steam libraries. Pick it by Minecraft version and loader, and install only one:

| Runtime | For |
|---|---|
| `steambridge-linux-runtime-java8` | Forge 1.7.10, 1.8.9, 1.12.2, 1.16.5 |
| `steambridge-linux-runtime-java17` | Forge 1.19.2, Fabric 1.19.2 / 1.20.1, NeoForge 1.20.1 |
| `steambridge-linux-runtime-java21` | Fabric / NeoForge 1.21.1 |
| `steambridge-linux-runtime-neoforge-26.x` | NeoForge 26.1 / 26.2 / 26.3 |

- **Use a launcher that is not sandboxed.** A Flatpak launcher (for example Prism Launcher from Flathub) hides the Steam process from the game and Steam exits it. Use the Prism Launcher AppImage, a distribution package or another native launcher.
- Use the regular desktop Steam client of the same user.
- **Linux as guest:** if joining stalls, set `"iceEnabled": false` in `config/steambridge.json`. Traffic then goes through Valve relays.
- ARM, 32-bit Java and macOS are not supported.

Tested on Fedora 44 with the Prism Launcher AppImage and the native Steam client, against Windows 11 in both host and guest roles. Details: [docs/LINUX_RUNTIME.md](docs/LINUX_RUNTIME.md).

---

## Developer info

All targets live in one branch. Shared code is in `core/`, `runtime/`, `transport-netty/`, `steam-jna/` (Java 8 to 21) and `steam-panama/` (Java 25), each Minecraft target in `adapters/<target>/` with its own Gradle wrapper.

Install JDK 8, 17, 21 and 25 and set `JAVA_HOME_8`, `JAVA_HOME_17`, `JAVA_HOME_21`, `JAVA_HOME_25` (or run `tools/fetch_jdks.py` on Windows). Python 3 is needed.

```text
python tools/test.py                            # tests
python tools/build.py                           # all targets, version 0.0.0-dev
python tools/build.py --variant forge-1.12.2    # one target
python tools/build.py --version 1.3             # release
```

Main jars go to `dist/windows-x64/` (used on Linux as well), Linux runtimes to `dist/addons/`.

Notes:

- Steam AppID: **480 (Spacewar)**. Steam client must be running.
- Release: `--version 1.3` -> `1.3+mc<mc_version>`. Without it: `0.0.0-dev+mc<mc_version>`.
- **Forge 1.19.2:** built with NeoForged ModDevGradle legacyforge against **MinecraftForge** 1.19.2-43.5.0.
- **NeoForge 1.20.1:** coordinate `net.neoforged:forge` 1.20.1-47.1.106.
- Old per-version branches are kept for history and are no longer updated.

---

## Important notes

Do not use a real game AppID. That can cause VAC/EAC issues, and the mod is not guaranteed to work in that setup.

---

## Configuration

All versions use `config/steambridge.json`.

- **Port 25565:** must be available when hosting through Steam Bridge. If another application is already using it, choose a different port in the mod configuration.
- **allowWithoutAuth:** `true` (default) skips Steam Session Ticket checks (easier with bad NAT, weaker security). `false` = strict checks.
- **interceptUdp:** `true` (default) routes voice mods (Simple Voice Chat, Plasmo Voice) through Steam next to game traffic. Not on 1.7.10 / 1.8.9.
- **iceEnabled:** `false` forces Valve relays (see [Linux](#linux)).

[Example configuration](docs/steambridge.example.json).

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
