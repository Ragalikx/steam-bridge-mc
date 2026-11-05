# Steam Bridge - Minecraft Mod

[![Author](https://img.shields.io/badge/author-Ragalikx-blue)](https://github.com/Ragalikx)
[![Discord](https://img.shields.io/badge/Discord-%D0%9F%D1%80%D0%B8%D1%81%D0%BE%D0%B5%D0%B4%D0%B8%D0%BD%D0%B8%D1%82%D1%8C%D1%81%D1%8F-5865F2?logo=discord&logoColor=white)](https://discord.gg/2xBnJ7awRC)

🇬🇧 [Read in English](readme.md)

**Steam Bridge** - мод, с которым можно открыть мир друзьям без VPN, proxy и туннелей.
Трафик игры идёт через Steam Networking Sockets (SDR / P2P). Благодаря сети релеев Valve задержка обычно остаётся настолько низкой, насколько это возможно, а подключение работает даже через большинство сложных NAT.

> **Discord - основная площадка проекта.** Вопросы, планы, релизы и баги: **[discord.gg/2xBnJ7awRC](https://discord.gg/2xBnJ7awRC)**

---

## Поддерживаемые версии

Все версии собираются из этой ветки.

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
<th>Linux**</th>
<th>macOS</th>
</tr>
</thead>
<tbody>
<tr><td>26.3</td><td>NeoForge</td><td>26.3.0.85</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Доступно</td></tr>
<tr><td>26.2</td><td>NeoForge</td><td>26.2.0.93</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Доступно</td></tr>
<tr><td>26.1</td><td>NeoForge</td><td>26.1.0.19-beta</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Доступно</td></tr>
<tr><td>1.21.1</td><td>NeoForge</td><td>21.1.234</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Доступно</td></tr>
<tr><td>1.21.1</td><td>Fabric</td><td>0.16.14 + API 0.116.13</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Доступно</td></tr>
<tr><td>1.20.1</td><td>NeoForge/Forge</td><td>47.1.106</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Доступно</td></tr>
<tr><td>1.20.1</td><td>Fabric</td><td>0.16.14 + API 0.92.2</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Доступно</td></tr>
<tr><td>1.19.2</td><td>Forge</td><td>43.5.0</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Доступно</td></tr>
<tr><td>1.19.2</td><td>Fabric</td><td>0.16.14 + API 0.76.1</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Доступно</td></tr>
<tr><td>1.16.5</td><td>Forge</td><td>36.2.42</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Доступно</td></tr>
<tr><td>1.12.2</td><td>Forge</td><td>14.23.5.2860</td><td>✅</td><td>✅</td><td>✅</td><td>❌</td><td>Доступно</td></tr>
<tr><td>1.8.9</td><td>Forge</td><td>11.15.1.2318</td><td>❌</td><td>✅</td><td>✅</td><td>❌</td><td>Доступно</td></tr>
<tr><td>1.7.10</td><td>Forge</td><td>10.13.4.1614</td><td>❌</td><td>✅</td><td>✅</td><td>❌</td><td>Доступно</td></tr>
</tbody>
</table>

NeoForge 26.1, 26.2 и 26.3 используют один jar, `steambridge-neoforge-26.x`. Для Fabric нужен Fabric API. У хоста и гостя должны совпадать версия Minecraft, загрузчик и версия Steam Bridge.

\*UDP Tunnel: пускает **UDP войсов** (Simple Voice Chat, Plasmo Voice) через Steam рядом с игровым трафиком (`interceptUdp`). На 1.7.10 / 1.8.9 нет.

\*\*Для Linux рядом с основным модом нужен [Linux Runtime](#linux).

---

## Linux

На Linux x64 ставится тот же основной jar и один jar **SBM Linux Runtime**. В нём только Linux-библиотеки Steam. Выбирайте его по версии Minecraft и загрузчику, ставьте только один:

| Runtime | Для |
|---|---|
| `steambridge-linux-runtime-java8` | Forge 1.7.10, 1.8.9, 1.12.2, 1.16.5 |
| `steambridge-linux-runtime-java17` | Forge 1.19.2, Fabric 1.19.2 / 1.20.1, NeoForge 1.20.1 |
| `steambridge-linux-runtime-java21` | Fabric / NeoForge 1.21.1 |
| `steambridge-linux-runtime-neoforge-26.x` | NeoForge 26.1 / 26.2 / 26.3 |

- **Лаунчер не должен быть в песочнице.** Flatpak-лаунчер (например Prism Launcher из Flathub) скрывает от игры процесс Steam, и Steam закрывает игру. Используйте AppImage Prism Launcher, пакет дистрибутива или другой нативный лаунчер.
- Нужен обычный десктопный клиент Steam того же пользователя.
- **Linux как гость:** если подключение зависает, укажите `"iceEnabled": false` в `config/steambridge.json`. Трафик пойдёт через релеи Valve.
- ARM, 32-битная Java и macOS не поддерживаются.

Проверено на Fedora 44 с AppImage Prism Launcher и нативным Steam, в паре с Windows 11 в роли хоста и гостя. Подробности: [docs/LINUX_RUNTIME.md](docs/LINUX_RUNTIME.md).

---

## Информация для разработчиков

Все версии лежат в одной ветке. Общий код в `core/`, `runtime/`, `transport-netty/`, `steam-jna/` (Java 8-21) и `steam-panama/` (Java 25), каждая версия Minecraft в `adapters/<target>/` со своим Gradle wrapper.

Нужны JDK 8, 17, 21 и 25 и переменные `JAVA_HOME_8`, `JAVA_HOME_17`, `JAVA_HOME_21`, `JAVA_HOME_25` (или `tools/fetch_jdks.py` на Windows), а также Python 3.

```text
python tools/test.py                            # тесты
python tools/build.py                           # все версии, 0.0.0-dev
python tools/build.py --variant forge-1.12.2    # одна версия
python tools/build.py --version 1.3             # релиз
```

Основные jar попадают в `dist/windows-x64/` (они же для Linux), Linux Runtime в `dist/addons/`.

Заметки:

- AppID Steam: **480 (Spacewar)**. Клиент Steam должен быть запущен.
- Релиз: `--version 1.3` -> `1.3+mc<mc_version>`. Без него: `0.0.0-dev+mc<mc_version>`.
- **Forge 1.19.2:** собирается NeoForged ModDevGradle legacyforge против **MinecraftForge** 1.19.2-43.5.0.
- **NeoForge 1.20.1:** coordinate `net.neoforged:forge` 1.20.1-47.1.106.
- Старые ветки по версиям оставлены для истории и больше не обновляются.

---
## Важные замечания

Не используйте AppID реальных игр. Это может привести к проблемам с VAC/EAC, при этом, в такой ситуации, работоспособность мода - не гарантирована.

---

## Настройка

Все версии используют `config/steambridge.json`.

- **Порт 25565:** при запуске мира через Steam Bridge порт должен быть свободен. Если он уже используется другим процессом, выберите другой порт в конфигурации мода.
- **allowWithoutAuth:** `true` (по умолчанию) - без проверки Steam Session Ticket (проще при проблемах с NAT, слабее безопасность). `false` - строгая проверка.
- **interceptUdp:** `true` (по умолчанию) пускает войс-моды (Simple Voice Chat, Plasmo Voice) через Steam рядом с игровым трафиком. На 1.7.10 / 1.8.9 нет.
- **iceEnabled:** `false` - только релеи Valve (см. [Linux](#linux)).

[Пример конфигурации](docs/steambridge.example.json).

---

## Лицензии

- **Steam Bridge** : [MIT License](LICENSE). Copyright (c) 2026 [Ragalikx](https://github.com/Ragalikx).
- **steamworks4j** : [MIT License](third_party_licenses/steamworks4j_LICENSE.txt) (Daniel Ludwig / code-disaster).
- **Java Native Access (JNA)** : [Apache License 2.0](third_party_licenses/JNA_AL2.0.txt).

> **Steam Bridge не связан с Valve. "Steam" является торговой маркой Valve. Мод использует официальные библиотеки Steamworks SDK для работы сети. Все права на них принадлежат Valve.**

---

## Поддержать автора

Поддержка проекта полностью добровольная. Если есть желание поддержать разработку, можно воспользоваться любым из способов ниже:

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
