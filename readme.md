# Steam Bridge - Minecraft 1.12.2 Forge Mod
[![Author](https://img.shields.io/badge/author-Ragalikx-blue)](https://github.com/Ragalikx)
[![MC](https://img.shields.io/badge/Minecraft-1.12.2-green)](https://www.minecraft.net)
[![Forge](https://img.shields.io/badge/Forge-14.23.5.2861-orange)](https://files.minecraftforge.net)
[![Discord](https://img.shields.io/badge/Discord-Join%20the%20server-5865F2?logo=discord&logoColor=white)](https://discord.gg/2xBnJ7awRC)

🇷🇺 [Читать на русском](readmeru.md)

**Steam Bridge** lets you play with friends over the network without forwarding ports, buying a white IP, or setting up a VPN.
The mod hooks into Minecraft's networking and routes it through **Steam Datagram Relay (SDR)**. Ping stays low and it gets through pretty much any NAT you throw at it.

> **Discord is the main hub for this project.** There's no separate website, so that's the place to get answers, news and roadmap updates, release announcements, and to report bugs: **[discord.gg/2xBnJ7awRC](https://discord.gg/2xBnJ7awRC)**


> ⚠️ **DEVELOPER INFORMATION**
>
> Official releases are planned for the following versions: **1.7.10, 1.8, 1.16.5, and 1.20.1**.
>
> * **Current Status:** All focus is currently on polishing **1.12.2** (under active testing). Porting to the other listed versions will begin only after gathering bug reports in Discord and fixing major issues, ensuring a stable codebase is transferred. Therefore, there is no need to make standalone ports for these versions — they are already in development.
>
> Porting the mod to **any other versions** (not included in the list above) is highly encouraged, provided the MIT license is followed.
> 
## Why Spacewar (AppID 480)?
The mod moves data through Steam's own API (ISteamNetworkingSockets), and Steam requires everyone involved to be sitting in the same "game" for that to work.

That's why **AppID 480 (Spacewar)** is used by default. It's Valve's old internal test app, and over the years the community has quietly adopted it for network experiments, co-op tricks, and non-Steam builds of games. Valve has never really cracked down on this, so it's the safest bet if you don't want to burn a real game license just to link two players together.

- **Note:** the AppID is hardcoded to Spacewar and there's no config option to swap it out - that's on purpose. Pointing this at a real game's AppID (especially anything running VAC or EAC) could get your account banned, so don't go digging for a workaround.

## Configuration Notes
- **Port 25565:** if you're hosting through Steam Bridge, make sure nothing else on your machine is already using this port. If you also want a regular Minecraft LAN game running alongside it, just point Steam Bridge's virtual port somewhere else in the config.
- **allowWithoutAuth:** controls whether Steam Session Ticket validation is enforced. Turning it off tightens security a bit, but it can cause connection issues on stricter/symmetric NAT setups.

## Development Environment
Versions used while building and testing the mod:
- **Java:** 8u492 (Eclipse Adoptium / Temurin, build 25.492-b09)
- **Minecraft Forge:** 1.12.2 - 14.23.5.2860
- **Gradle:** 4.10.3
- **OS:** Windows 11 (amd64), builds should work fine on 10 as well

## Platforms & Optimization
To keep the .jar small (around 775 KB), the release build only ships native libraries for **Windows x64**. Once this version has been tested properly, Linux support is planned.

> **Linux / macOS**
> If you want to build for Linux or macOS, open `build.gradle` and remove the `exclude` rules for `linux`/`darwin` and the `.so`/`.dylib` files, then rebuild. You'll likely need to uncomment a couple of packages too, and possibly poke around the code a bit before it runs cleanly on your OS.

## Dependencies & Licenses
- **Steam Bridge** - [MIT License](LICENSE). Copyright (c) 2026 [Ragalikx](https://github.com/Ragalikx).
- **steamworks4j** - [MIT License](third_party_licenses/steamworks4j_LICENSE.txt) (Daniel Ludwig / code-disaster).
- **Java Native Access (JNA)** - [Apache License 2.0](third_party_licenses/JNA_AL2.0.txt) (dual-licensed with LGPL 2.1; this project uses the Apache terms).

> **Disclaimer:** this is an educational, open-source community project and isn't affiliated with, endorsed by, or sponsored by Valve Corporation. "Steam" and the Steam logo belong to Valve Corporation. The mod bundles the Steamworks SDK (`steam_api64.dll`) and links against it through steamworks4j to reach `ISteamNetworkingSockets`; the SDK itself stays Valve's property and is redistributed here under the terms Valve provides to developers using the Steamworks API.

---

## Support the author

If the mod saved you a headache or two, a tip is always appreciated - never expected.

**Bitcoin (BTC)**
```
bc1q2e7hxvv90qm5menfc9m9nd8q43g4w6hmdfuhk3
```

**Litecoin (LTC)**
```
ltc1q075u480ug7c7wne9tv34yf8suskvtr68rd4q9y
```

**USDT (TRC-20, Tron network)**
```
TVzTdidAdYnQyTth1RoY8duyHQnxcuHDrC
```