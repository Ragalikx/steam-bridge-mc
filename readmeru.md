# Steam Bridge - Minecraft Mod

[![Author](https://img.shields.io/badge/author-Ragalikx-blue)](https://github.com/Ragalikx)
[![Discord](https://img.shields.io/badge/Discord-%D0%9F%D1%80%D0%B8%D1%81%D0%BE%D0%B5%D0%B4%D0%B8%D0%BD%D0%B8%D1%82%D1%8C%D1%81%D1%8F-5865F2?logo=discord&logoColor=white)](https://discord.gg/2xBnJ7awRC)

🇬🇧 [Read in English](readme.md)

**Steam Bridge** - мод, с которым можно открыть мир друзьям без VPN, proxy и туннелей.
Трафик игры идёт через Steam Networking Sockets (SDR / P2P). Пинг обычно нормальный за счёт сети Steam, и через строгий NAT часто всё равно получается зайти.

> **Discord - основная площадка проекта.** Вопросы, планы, релизы и баги: **[discord.gg/2xBnJ7awRC](https://discord.gg/2xBnJ7awRC)**

---

## Поддерживаемые версии

Ссылки на версии Minecraft ведут на нужную ветку с кодом.

<table>
<thead>
<tr>
<th rowspan="2">Minecraft</th>
<th rowspan="2">Платформа</th>
<th rowspan="2">Версия загрузчика</th>
<th rowspan="2">UDP Tunnel*</th>
<th colspan="3">ОС</th>
<th rowspan="2">Статус</th>
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
<td>Доступно</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.21.1">1.21.1</a></td>
<td>Fabric</td>
<td>0.16.14 + API 0.116.13</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Доступно</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/NeoForge-1.20.1">1.20.1</a></td>
<td>NeoForge/Forge</td>
<td>47.1.106</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Доступно</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.20.1">1.20.1</a></td>
<td>Fabric</td>
<td>0.16.14 + API 0.92.2</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Доступно</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.19.2">1.19.2</a></td>
<td>Forge</td>
<td>43.5.0</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Доступно</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.19.2">1.19.2</a></td>
<td>Fabric</td>
<td>0.16.14 + API 0.76.1</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Доступно</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.16.5">1.16.5</a></td>
<td>Forge</td>
<td>36.2.42</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Доступно</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.16.5">1.16.5</a></td>
<td>Fabric</td>
<td></td>
<td>⏳</td>
<td>⏳</td>
<td>⏳</td>
<td>❌</td>
<td>В разработке</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.12.2">1.12.2</a></td>
<td>Forge</td>
<td>14.23.5.2860</td>
<td>✅</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Доступно</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.8.9">1.8.9</a></td>
<td>Forge</td>
<td>11.15.1.2318</td>
<td>❌</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Доступно</td>
</tr>
<tr>
<td><a href="https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.7.10">1.7.10</a></td>
<td>Forge</td>
<td>10.13.4.1614</td>
<td>❌</td>
<td>✅</td>
<td>⏳</td>
<td>❌</td>
<td>Доступно</td>
</tr>
</tbody>
</table>

\*Для Fabric нужен Fabric API.

\*UDP Tunnel: опциональный JVM-wide перехват `DatagramSocket` (`interceptUdp`). Игровой трафик и так идёт через Steam; это дополнительно пускает **UDP войсов** (Simple Voice Chat, Plasmo Voice и т.п.) в тот же Steam-канал. Включается в конфиге. На 1.7.10 / 1.8.9 нет.

---

## Инфо для разработчиков

Точные пины с tip каждой ветки (`build.gradle` / `gradle.properties` / `gradle-wrapper.properties`). Перепроверено по git, не из памяти.

| Minecraft | Платформа | Java (compile) | JVM для демона Gradle | Gradle wrapper | Плагин сборки (точный id / coordinate) | steamworks4j | JNA |
|---|---|---|---|---|---|---|---|
| 1.21.1 | NeoForge 21.1.234 | 21 (`toolchain`) | 17+ (лучше 21) | 8.13 | `net.neoforged.gradle.userdev` **7.0.170** | 1.10.0 | 5.14.0 **compileOnly** (даёт loader; не в jar мода) |
| 1.21.1 | Fabric loader 0.16.14 / API 0.116.13+1.21.1 | 21 (`toolchain` + `release 21`) | 17+ (лучше 21) | 8.13 | `fabric-loom` **1.10.5** | 1.10.0 | 5.14.0 **shade** slim win64 (`slimJnaJar`) |
| 1.20.1 | NeoForge 1.20.1-47.1.106 | 17 (`toolchain`) | 17+ | 8.13 | `net.neoforged.moddev.legacyforge` **2.0.141** | 1.10.0 | 5.12.1 **compileOnly** (даёт loader; не в jar) |
| 1.20.1 | Fabric loader 0.16.14 / API 0.92.2+1.20.1 | 17 (`toolchain` + `release 17`) | 17+ | 8.13 | `fabric-loom` **1.10.5** | 1.10.0 | 5.14.0 **shade** slim win64 |
| 1.19.2 | MinecraftForge 1.19.2-43.5.0 | 17 (`toolchain`) | 17+ | 8.13 | `net.neoforged.moddev.legacyforge` **2.0.141** (собирает **MinecraftForge**, не NeoForge) | 1.10.0 | 5.12.1 **compileOnly** (не в jar) |
| 1.19.2 | Fabric loader 0.16.14 / API 0.76.1+1.19.2 | 17 (`toolchain` + `release 17`) | 17+ | 8.13 | `fabric-loom` **1.10.5** | 1.10.0 | 5.14.0 **shade** slim win64 |
| 1.16.5 | MinecraftForge 1.16.5-36.2.42 | 8 (`toolchain` 8) | 8 или 17 | 7.5.1 | `net.minecraftforge.gradle` **5.1.69** | 1.10.0 | 4.4.0 **compileOnly** (JNA 4.4.0 уже у игры; 5.x не shade) |
| 1.16.5 | Fabric loader 0.14.25 / API 0.42.0+1.16 | 8 (`sourceCompatibility` + `release 8`) | 17+ (Gradle 8.8) | 8.8 | `fabric-loom` **1.6.12** | 1.10.0 | 5.14.0 **shade** slim win64 |
| 1.12.2 | MinecraftForge 1.12.2-14.23.5.2860 | 8 | 8 | 4.10.3 | classpath `net.minecraftforge.gradle:ForgeGradle:`**`3.+`** (dynamic range, `changing: true`) | 1.10.0 | 5.14.0 **shade** slim win64 + trim classes |
| 1.8.9 | MinecraftForge 1.8.9-11.15.1.2318-1.8.9 | 8 | 8 | 4.10.3 | classpath `net.minecraftforge.gradle:ForgeGradle:`**`2.1-SNAPSHOT`** | 1.10.0 | 5.14.0 **shade** (win64 natives; non-Windows natives вырезаны) |
| 1.7.10 | MinecraftForge 1.7.10-10.13.4.1614-1.7.10 | 8 | 8 | 7.4.2 | classpath `com.anatawa12.forge:ForgeGradle:`**`1.2-1.1.+`** (`changing: true`; apply plugin `forge`) | 1.10.0 | 5.14.0 **shade**: только win64 natives, **полные** Java-классы JNA (LaunchWrapper `Native.initIDs`) |

Заметки:

- Сборка и тесты: Windows 11 (amd64). Linux: в разработке; macOS - нет.
- AppID Steam: **480 (Spacewar)**. Клиент Steam должен быть запущен.
- Релиз: `-PmodVersion=1.145` -> `1.145+mc<mc_version>`. Без `-P`: `0.0.0-dev+mc<mc_version>`.
- **JVM демона Gradle != target compile.** Loom **1.10.5** + Gradle **8.13** требуют **Java 17+** для демона. Toolchain / `options.release` только для компиляции. Forge **1.7.10 / 1.8.9 / 1.12.2** - Gradle только на **Java 8**.
- **Forge 1.19.2:** плагин сборки - NeoForged **ModDevGradle legacyforge**, но игровая зависимость - **MinecraftForge** `1.19.2-43.5.0` (`legacyForge { version = ... }`), не NeoForge.
- **NeoForge 1.20.1:** тот же плагин `moddev.legacyforge` **2.0.141**, но `neoForgeVersion = 1.20.1-47.1.106` (coordinate `net.neoforged:forge`).
- **JNA в jar:** NeoForge / Forge 1.19.2 / Forge 1.16.5 - **нет**. Fabric - slim win64 shade. Forge 1.12.2 - aggressive class trim + win64. Forge 1.7.10 - **полные** классы JNA + win64 natives (это не Fabric-slim).
- **Динамические пины:** ForgeGradle **`3.+`** и **`1.2-1.1.+`** подтягивают новейший подходящий артефакт при сборке (в скрипте нет одной замороженной patch-версии).

### Сборка релизного jar

```text
# Windows (PowerShell): кавычки вокруг -P обязательны
# JAVA_HOME = колонка "JVM для Gradle" для этой ветки

$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.x.x-hotspot"   # современный Fabric / Neo / Forge 1.19+
# $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-8.0.x-hotspot" # Forge 1.7.10 / 1.8.9 / 1.12.2
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
.\gradlew.bat --stop
.\gradlew.bat build "-PmodVersion=1.145"

# Пример: 1.145+mc1.20.1 или 1.145+mc1.7.10 (зависит от ветки)
# Dev без -P: 0.0.0-dev+mc...
```

После смены `JAVA_HOME` всегда `.\gradlew.bat --stop`, иначе останется старый daemon.

---

## Приоритеты и технические риски

Главное - стабильная маршрутизация и нормальный пинг.

AppID 480 (Spacewar) выбран специально. Не подставляйте AppID реальной игры (VAC/EAC и хуже стабильность).

---

## Настройка

- **Порт 25565:** при хосте через Steam Bridge держите свободным. Для обычного LAN укажите другой виртуальный порт в конфиге.
- **allowWithoutAuth:** `true` (по умолчанию) - без проверки Steam Session Ticket (проще через плохой NAT, слабее безопасность). `false` - строгая проверка.
- **interceptUdp:** (где есть) ставит JVM-wide фабрику `DatagramSocket`, чтобы войс-моды (Simple Voice Chat, Plasmo Voice и т.п.) шли через Steam рядом с игровым трафиком. Только при запуске. На портах 1.7.10 / 1.8.9 нет.

---

## Лицензии

- **Steam Bridge** : [MIT License](LICENSE). Copyright (c) 2026 [Ragalikx](https://github.com/Ragalikx).
- **steamworks4j** : [MIT License](third_party_licenses/steamworks4j_LICENSE.txt) (Daniel Ludwig / code-disaster).
- **Java Native Access (JNA)** : [Apache License 2.0](third_party_licenses/JNA_AL2.0.txt).

> **Отказ от ответственности:** образовательный проект, не связан с Valve. "Steam" - торговая марка Valve. В моде могут быть нативные библиотеки Steam для сети; они остаются собственностью Valve.

---

## Поддержать автора

Донаты не обязательны. Если мод пригодился для игры с друзьями - поддержка приветствуется.

**Bitcoin (BTC)**
```
bc1q2e7hxvv90qm5menfc9m9nd8q43g4w6hmdfuhk3
```

**Litecoin (LTC)**
```
ltc1q075u480ug7c7wne9tv34yf8suskvtr68rd4q9y
```

**USDT (сеть TRC-20, Tron)**
```
TVzTdidAdYnQyTth1RoY8duyHQnxcuHDrC
```
