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

| Minecraft | Платформа | Загрузчик | Windows | Linux | macOS | Статус |
|---|---|---|---|---|---|---|
| [1.21.1](https://github.com/Ragalikx/steam-bridge-mc/tree/NeoForge-1.21.1) | NeoForge | 21.1.234 | ✅ | ✅ | ❌ | Доступно |
| [1.21.1](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.21.1) | Fabric | 0.16.14 + API 0.116.13+1.21.1 | ✅ | ✅ | ❌ | Доступно |
| [1.20.1](https://github.com/Ragalikx/steam-bridge-mc/tree/NeoForge-1.20.1) | NeoForge/Forge | 47.1.106 | ✅ | ✅ | ❌ | Доступно |
| [1.20.1](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.20.1) | Fabric | 0.16.14 + API 0.92.2+1.20.1 | ✅ | ✅ | ❌ | Доступно |
| [1.16.5](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.16.5) | Forge | ? | ? | ? | ❌ | В разработке |
| [1.16.5](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.16.5) | Fabric | ? | ? | ? | ❌ | В разработке |
| [1.12.2](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.12.2) | Forge | 14.23.5.2860 | ✅ | ✅ | ❌ | Доступно |
| [1.7.10](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.7.10) | Forge | ? | ? | ? | ❌ | В разработке |

*(Для Fabric нужен Fabric API)*

---

## Инфо для разработчиков

Окружения, в которых собирались и тестировались доступные ветки. Нативки лежат в jar мода.

| Minecraft | Платформа | Java (игра / compile) | Gradle | Плагин сборки | Загрузчик / API | steamworks4j | JNA |
|---|---|---|---|---|---|---|---|
| 1.21.1 | NeoForge | 21 | 8.13 | NeoGradle userdev 7.0.170 | NeoForge 21.1.234 | 1.10.0 | 5.14.0 (compileOnly, от загрузчика) |
| 1.21.1 | Fabric | 21 | 8.13 | Fabric Loom 1.10.5 | Loader 0.16.14, API 0.116.13+1.21.1 | 1.10.0 | 5.14.0 (slim win64 в jar) |
| 1.20.1 | NeoForge/Forge | 17 | 8.13 | NeoForge moddev legacyforge 2.0.141 | 1.20.1-47.1.106 | 1.10.0 | 5.12.1 (compileOnly, от загрузчика) |
| 1.20.1 | Fabric | 17 | 8.13 | Fabric Loom 1.10.5 | Loader 0.16.14, API 0.92.2+1.20.1 | 1.10.0 | 5.14.0 (slim win64 в jar) |
| 1.12.2 | Forge | 8 | 4.10.3 | ForgeGradle 3.x | Forge 14.23.5.2860 | 1.10.0 | (как в 1.12.2 сборке) |

Заметки:

- Сборка и тесты: Windows 11 (amd64). Linux для запуска поддерживается, macOS - нет.
- AppID Steam зафиксирован: **480 (Spacewar)**. Клиент Steam должен быть запущен.
- Версии релиза на новых ветках: `-PmodVersion=1.145` -> `1.145+mc<mc_version>`. Без `-P`: `0.0.0-dev+mc<mc_version>`.

---

## Приоритеты и технические риски

Главное - стабильная маршрутизация и нормальный пинг.

AppID 480 (Spacewar) выбран специально. Не подставляйте AppID реальной игры (VAC/EAC и хуже стабильность).

---

## Настройка

- **Порт 25565:** при хосте через Steam Bridge держите свободным. Для обычного LAN укажите другой виртуальный порт в конфиге.
- **allowWithoutAuth:** `true` (по умолчанию) - без проверки Steam Session Ticket (проще через плохой NAT, слабее безопасность). `false` - строгая проверка.
- **interceptUdp:** ставит JVM-wide фабрику `DatagramSocket`, чтобы войс-моды (Simple Voice Chat, Plasmo Voice и т.п.) шли через Steam рядом с игровым трафиком. Только при запуске, в runtime не переключается.

## Сборка релизного jar

```text
# Windows (PowerShell): кавычки вокруг -P обязательны
.\gradlew.bat build "-PmodVersion=1.145"

# Пример: 1.145+mc1.20.1 или 1.145+mc1.21.1 (зависит от ветки)
# Dev без -P: 0.0.0-dev+mc...
# Java берите из таблицы для этой ветки (8 / 17 / 21).
```

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
