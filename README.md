# Steam Bridge - Minecraft 1.12.2 Forge Mod
[![Author](https://img.shields.io/badge/author-Ragalikx-blue)](https://github.com/Ragalikx)
[![MC](https://img.shields.io/badge/Minecraft-1.12.2-green)](https://www.minecraft.net)
[![Forge](https://img.shields.io/badge/Forge-14.23.5.2861-orange)](https://files.minecraftforge.net)

[Russian Version (RU) / Русскоязычная версия](#steam-bridge--русскоязычная-версия)

**Steam Bridge** is a mod that allows you to play with your friends over the network without port-forwarding, white IPs, or third-party VPNs.
It intercepts Minecraft's network traffic and routes it through **Steam Datagram Relay (SDR)**. This guarantees minimal ping and works through any NAT. 
## How it works and why Spacewar (AppID 480)?
To transmit data, the mod uses the Steam API (ISteamNetworkingSockets). Steam requires all players to be in the same "game" to connect.

By default, **AppID 480 (Spacewar)** is used. Officially, it's an old test game by Valve for developers, but in the community it is widely used for various network fixes, coop, and non-steam versions of games. Valve usually turns a blind eye to this, so it is the most popular and risk-free option to connect players without buying a dedicated game.

- **Warning:** The AppID is hardcoded to Spacewar (480) and cannot be changed via config - this is intentional. Pointing the mod at a real game's AppID (especially one with VAC/EAC) would risk a permanent ban on your Steam account.
## Configuration Notes
- **Port 25565:** If you are using Steam Bridge to host a server, make sure nothing else is using port 25565. If you need standard Minecraft LAN hosting alongside Steam, change the Steam Bridge virtual port in the mod's configuration menu.
- **allowWithoutAuth**: This config option handles Steam Session Ticket validation. Disabling it provides higher security but may break connections for certain symmetric NAT setups.

## Development Environment
The following versions were used to develop and build this mod:
- **Java**: 8 (1.8)
- **Minecraft Forge**: 1.12.2 - 14.23.5.2860
- **Gradle**: 4.10.3
## Platforms & Optimization
To achieve the smallest possible .jar file size (around 775 KB), this mod is compiled exclusively with native libraries for **Windows x64**. 

> **Linux / macOS Support**  
> *Want to play on Linux or macOS?* Open `build.gradle` and remove the `exclude` flags for `linux`/`darwin` and `.so`/`.dylib` libraries. Build the mod again to embed those native files. You may also need to explicitly uncomment some packages and perhaps tinker a bit in the code to get it fully working on your OS.
## Dependencies & Licenses
- **Steam Bridge**: Distributed under the [MIT License](LICENSE). Copyright (c) 2019-2026 [Ragalikx](https://github.com/Ragalikx).
- **steamworks4j**: [MIT License](third_party_licenses/steamworks4j_LICENSE.txt) (code-disaster).
- **Java Native Access (JNA)**: [Apache License 2.0](third_party_licenses/JNA_AL2.0.txt) (dual-licensed with LGPL 2.1; this project uses the Apache terms).

> **Disclaimer**: This is an educational open-source community project and is NOT affiliated with, endorsed, or sponsored by Valve Corporation. "Steam" and the Steam logo are trademarks or registered trademarks of Valve Corporation. This mod bundles the Steamworks SDK (`steam_api64.dll`) and links against it via steamworks4j to access `ISteamNetworkingSockets`; the SDK itself remains the property of Valve Corporation and is redistributed here under the terms Valve provides to developers using the Steamworks API.

---
# Steam Bridge - Русскоязычная Версия
[![Author](https://img.shields.io/badge/author-Ragalikx-blue)](https://github.com/Ragalikx)
[![MC](https://img.shields.io/badge/Minecraft-1.12.2-green)](https://www.minecraft.net)
[![Forge](https://img.shields.io/badge/Forge-14.23.5.2861-orange)](https://files.minecraftforge.net)

[English Version (EN) / Английская версия](#steam-bridge---minecraft-1122-forge-mod)

**Steam Bridge** - это модификация, которая позволяет играть с друзьями по сети без открытия портов (port-forwarding), использования белого IP и сторонних VPN.
Мод перехватывает сетевой трафик Minecraft и пускает его напрямую через **Steam Datagram Relay (SDR)**. Это обеспечивает минимально возможный пинг и пробивает любые виды NAT-сетей.
## Как это работает и почему Spacewar (AppID 480)?
Для передачи данных мод использует API Steam (ISteamNetworkingSockets). Steam требует, чтобы все игроки находились в одной "игре". 

По умолчанию используется **AppID 480 (Spacewar)**. Вообще, это старенькая тестовая игра от Valve для разработчиков, но в народе её часто используют для различных сетевых фиксов, коопа и пираток. Valve на это обычно закрывает глаза, поэтому это самый популярный и безопасный вариант для объединения игроков без покупки выделенной игры.

- **Важно:** AppID жёстко зафиксирован на Spacewar (480) и не может быть изменён через конфиг - это сделано намеренно. Использование AppID реальной игры (особенно с VAC/EAC) рискует привести к блокировке вашего аккаунта Steam.
## Настройка
- **Порт 25565:** Если включен Steam-хостинг, мод перехватывает трафик виртуально. Если возникает конфликт, или вам нужен одновременно классический LAN-сервер (помимо Steam), укажите другой порт в конфиге мода (не 25565).
- **allowWithoutAuth**: Флаг в настройках. Отключение повышает безопасность (проверяет билеты сессий Steam), но может вызывать сбои подключения при строгом NAT.

## Среда разработки
Для работы над проектом и его компиляции использовались следующие версии (указаны для справки, а не как строгие требования):
- **Java**: 8 (1.8)
- **Minecraft Forge**: 1.12.2 - 14.23.5.2860
- **Gradle**: 4.10.3
## Платформы и оптимизация (Mac / Linux)
Ради оптимизации размера мода (около 775 КБ вместо 2+ МБ), в собранном .jar оставлены нативные библиотеки исключительно для **Windows x64**.

> **Совместимость с Mac / Linux**  
> Если вам нужно скомпилировать мод для Linux или macOS, зайдите в `build.gradle` и удалите флаги `exclude` для вашей платформы (`linux`, `darwin`, `.so`, `.dylib`), после чего соберите проект заново. Возможно, вам понадобится раскомментировать некоторые пакеты и каплю повозиться в коде, чтобы всё окончательно завелось на вашей ОС.
## Лицензии
- **Steam Bridge**: Проект распространяется по лицензии [MIT](LICENSE). Copyright (c) 2019-2026 [Ragalikx](https://github.com/Ragalikx).
- **steamworks4j**: [MIT](third_party_licenses/steamworks4j_LICENSE.txt) (code-disaster).
- **JNA**: [Apache License 2.0](third_party_licenses/JNA_AL2.0.txt) (двойная лицензия с LGPL 2.1; в проекте используются условия Apache).

> **Отказ от ответственности (Disclaimer)**: Это образовательный open-source проект сообщества. Проект НЕ связан с Valve Corporation, не одобряется и не спонсируется ей. "Steam" и логотип Steam являются торговыми марками Valve Corporation. Мод включает в себя Steamworks SDK (`steam_api64.dll`) и обращается к нему через steamworks4j для доступа к `ISteamNetworkingSockets`; сам SDK остаётся собственностью Valve Corporation и распространяется здесь на условиях, предоставляемых Valve разработчикам, использующим Steamworks API.

---

## Support the author / Поддержать автора

[![Litecoin](https://img.shields.io/badge/LTC-ltc1q075u480ug7c7wne9tv34yf8suskvtr68rd4q9y-a6a9aa?logo=litecoin&logoColor=white)](litecoin:ltc1q075u480ug7c7wne9tv34yf8suskvtr68rd4q9y)

`ltc1q075u480ug7c7wne9tv34yf8suskvtr68rd4q9y`
