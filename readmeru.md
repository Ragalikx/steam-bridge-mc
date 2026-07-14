# Steam Bridge - Minecraft Mod

[![Author](https://img.shields.io/badge/author-Ragalikx-blue)](https://github.com/Ragalikx)
[![Discord](https://img.shields.io/badge/Discord-%D0%9F%D1%80%D0%B8%D1%81%D0%BE%D0%B5%D0%B4%D0%B8%D0%BD%D0%B8%D1%82%D1%8C%D1%81%D1%8F-5865F2?logo=discord&logoColor=white)](https://discord.gg/2xBnJ7awRC)

🇬🇧 [Read in English](readme.md)

**Steam Bridge** - это модификация, позволяющая открывать мир без использования VPN, Proxy или Tunnel соединений.
Мод работает на уровне сетевого кода Minecraft и маршрутизирует трафик через Steam Datagram Relay (SDR). Он интегрирует Steam Networking Sockets в сетевой стек игры, используя его в качестве транспортного уровня передачи данных. Это обеспечивает низкую задержку благодаря серверам Steam и их широкому покрытию по всему миру, а также позволяет стабильно подключаться даже через строгий NAT.

> **Discord выступает как основная площадка проекта.** Там можно найти ответы на вопросы, посмотреть планы по разработке, узнать о релизах и сообщить о багах: **[discord.gg/2xBnJ7awRC](https://discord.gg/2xBnJ7awRC)**

---

## Поддерживаемые версии

Ниже собраны все актуальные ветки проекта. (Версии Minecraft в таблице кликабельны - они ведут на соответствующую ветку с кодом).

| Версия Minecraft | Платформа | Версия загрузчика | Windows | Linux | macOS | Статус |
|---|---|---|---|---|---|---|
| [1.21.1](https://github.com/Ragalikx/steam-bridge-mc/tree/NeoForge-1.21.1) | NeoForge | ? | ✅ | ⏳ (В планах на будущее) | ❌ | Доступно |
| [1.21.1](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.21.1) | Fabric | ? | ? | ? | ❌ | В разработке |
| [1.20.1](https://github.com/Ragalikx/steam-bridge-mc/tree/NeoForge-1.20.1) | NeoForge/Forge | ? | ✅ | ⏳ (В планах на будущее) | ❌ | Доступно |
| [1.20.1](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.20.1) | Fabric | ? | ? | ? | ❌ | В разработке |
| [1.16.5](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.16.5) | Forge | ? | ? | ? | ❌ | В разработке |
| [1.16.5](https://github.com/Ragalikx/steam-bridge-mc/tree/Fabric-1.16.5) | Fabric | ? | ? | ? | ❌ | В разработке |
| [1.12.2](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.12.2) | Forge | 14.23.5.2861 | ✅ | ⏳ (В планах на будущее) | ❌ | Доступно |
| [1.7.10](https://github.com/Ragalikx/steam-bridge-mc/tree/Forge-1.7.10) | Forge | ? | ? | ? | ❌ | В разработке |

*(Для сборок на базе Fabric обязательно требуется наличие Fabric API)*

---

## Инфо для разработчиков

Данные версии использовались разработчиком при создании мода. Нативные библиотеки поставляются вместе с модом, поэтому дополнительных действий для их линковки не требуется.

| Версия Minecraft | Платформа | Версия Java | Версия Gradle |
|---|---|---|---|
| 1.21.1 | NeoForge / Fabric | Java 21 | 8.x |
| 1.20.1 | NeoForge/Forge / Fabric | Java 17 | 8.x |
| 1.16.5 | Forge / Fabric | Java 8 / 11 | 7.x |
| 1.12.2 | Forge | Java 8 | 4.10.3 |
| 1.7.10 | Forge | Java 8 | 4.4.1 |

---

## Приоритеты и технические риски

Основной приоритет разработки заключается в стабильной маршрутизации трафика и минимизации задержек при подключении игроков.

Важный момент: для работы сети используется AppID 480 (Spacewar). Это старое тестовое приложение Valve, которое хорошо подходит для подобных задач. Мод жестко привязан к этому ID, и изменить его через конфигурацию нельзя. Попытка подставить AppID реальной игры (особенно с поддержкой античитов VAC или EAC) может привести к проблемам с лицензионным соглашением, и ваш аккаунт могут заблокировать в этой конкретной игре. При этом совершенно не факт, что данный способ будет работать так же стабильно, как Spacewar.

---

## Настройка

- **Порт 25565:** при хостинге через Steam Bridge убедитесь, что этот порт свободен. Если нужен обычный LAN сервер, просто укажите другой виртуальный порт в конфиге.
- **allowWithoutAuth:** параметр отвечает за проверку Steam Session Ticket. Отключение немного повышает безопасность, но может вызвать проблемы с подключением при строгом или симметричном NAT.

---

## Лицензии

- **Steam Bridge** : [MIT License](LICENSE). Copyright (c) 2026 [Ragalikx](https://github.com/Ragalikx).
- **steamworks4j** : [MIT License](third_party_licenses/steamworks4j_LICENSE.txt) (Daniel Ludwig / code-disaster).
- **Java Native Access (JNA)** : [Apache License 2.0](third_party_licenses/JNA_AL2.0.txt).

> **Отказ от ответственности:** проект носит образовательный характер и никак не связан с Valve Corporation. "Steam" и логотип Steam принадлежат Valve. Мод включает `steam_api64.dll` для вызовов функций сети, сам файл остается собственностью Valve.

---

## Поддержать автора

Донаты не обязательны, но если мод оказался полезен для игры с друзьями, поддержка приветствуется.

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
