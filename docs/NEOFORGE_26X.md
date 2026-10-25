# NeoForge 26.x: первая development-сборка

Состояние на 25 октября 2026 года. Текущая локальная ветка `26.x` переименована из `main`. Для Minecraft 26.1, 26.2 и 26.3 собирается один общий JAR со встроенной Windows DLL: `steambridge-neoforge-26.x-0.0.0-dev.jar`. Внутри общий backend Project Panama и три внутренних адаптера. На Windows дополнительных core-модов не требуется. На Linux x64 к этому же JAR добавляется один SBM Linux Runtime для всех трёх версий. Для игры нужны совместимые Minecraft и модпаки на обеих сторонах.

Это результат компиляции и автоматических проверок. Игровой запуск, Steam direct/Relay, передача мира и настоящий голос пока не подтверждены.

Выявленная аудитом 25 октября ошибка пустого receive batch исправлена. Добавлены общие и FFM-регрессии idle receive/graceful drain, управление native-очередью по peer, общая политика JNA/Panama и экспорт диагностики. UUID Minecraft сохраняется. Подробности и границы подтверждения: [RELIABILITY.md](RELIABILITY.md).

## Архитектура

`steam-panama/` содержит прямые FFM downcalls в `steam_api64.dll`, upcall обработчика состояния соединения, manual dispatch persona/avatar/shutdown callbacks и владение native-памятью через Arena. JNA, Java wrappers steamworks4j и его JNI bridge в новый пакет не включаются. Steam SDK DLL берётся из закреплённого артефакта steamworks4j 1.10.0; исходный JAR проверяется checksum lock.

Генератор `tools/generate_panama_sources.py` компилирует общий lifecycle, клиент/хост, социальные хранилища и voice adapters из существующих исходников с подстановкой FFM facade. JNA-specific callback structures и выделение памяти заменены собственными FFM реализациями. Изменение ожидаемой формы общего callback-кода останавливает генерацию, чтобы не пропустить несовместимое изменение молча.

`tools/generate_26x_adapter.py` собирает Minecraft-интеграцию из одного набора исходников в пакеты `steambridge.mc.v261`, `v262`, `v263`. Версионные различия локализованы в генераторе: GUI, экран открытия мира, translation lookup и publish API. При запуске версия выбирается через FMLLoader. Автоматическая регистрация несовместимых компонентов отключена; регистрируется выбранный адаптер. Mixin plugin добавляет только его mixins и пропускает dedicated side.

Публичный JAR собирается из трёх внутренних build outputs. Любое несовпадение общих классов или ресурсов останавливает объединение. Общий runtime и DLL включаются один раз. Отдельных веток и пользовательских JAR для 26.1/26.2/26.3 нет.

## Закреплённые цели компиляции

| Minecraft | NeoForge | Внутренний адаптер |
| --- | --- | --- |
| 26.1 | 26.1.0.19-beta | v261 |
| 26.2 | 26.2.0.93 | v262 |
| 26.3 | 26.3.0.85 | v263 |

Версии находятся в `adapters/neoforge-26.x/targets.json`. Используются JDK 25 и Gradle 9.2.1 с SHA-256 distribution lock, ModDevGradle 2.0.148. Исходной точкой послужили официальные MDK NeoForge. Метаданные допускают ветки 26.1-26.3; автоматический прогон выполнен для конкретных пар выше. Прочие hotfix-сочетания этим прогоном отдельно не подтверждены.

## Выполненные изменения интеграции

- GUI переведён на GuiGraphicsExtractor, новый ввод мыши, Identifier и явный ARGB. Учтены новые texture constructor/registration и PNG encoding аватаров.
- Обновлены Minecraft login connection, EventLoopGroupHolder и LevelLoadTracker.
- Для 26.2/26.3 изменены обращения к текущему экрану и открытию GUI, для 26.3 используется WorldOptionsScreen.
- GUI settings читаются по конкретным именам полей: ConnectScreen `aborted`/`parent`, поля редакторов сервера, выбранный server list и LAN settings. Поиск по порядку EditBox и изменение всех boolean-полей ConnectScreen удалены. Сохраняется выбранный порт, учитывается уже опубликованный LAN-сервер. При ошибке публикации Steam-хост не запускается с неработающим локальным портом.
- Receive batch освобождает native messages и при неверном размере payload, исключении чтения или ошибке Release; обработка cleanup продолжается для остальных записей. Повторный pointer не освобождается дважды.
- Прямой непрерывный Netty ByteBuf передаётся в синхронный Steam send через MemorySegment без промежуточного heap byte array. Heap/composite buffer копируется в ограниченную Arena, readerIndex сохраняется. Выигрыш производительности не измерен.

## Проверено автоматически

- 112 тестов Java 25, без ошибок и пропусков. Используются общие тесты core/transport/runtime и voice adapters, а также новые FFM проверки.
- SDK 1.62 ABI: 243 проверки FFM, включая 41 downcall и callback; для JNA проверены 55 layouts и 92 значения сигнатур на каждой JVM. Оригинальный ZIP Valve не проверен, источник SDK зафиксирован в `STEAMWORKS_ABI.md`.
- Загрузка настоящей Steam SDK DLL и наличие необходимых exports без Steam initialization. Тесты выявили SteamFriends interface v018 и проверяют именно её.
- SteamID проходит Clear/Set/Get через настоящий C ABI; проверены native bool и layout identity.
- FFM upcall передаёт callback в Java; скопированные данные сохраняются после закрытия исходной Arena. Это синтетический callback, не событие настоящего Steam-соединения.
- Ошибки payload/count/Release не оставляют необработанную часть batch; закрытая native library не принимает вызовы.
- Для каждой целевой версии по настоящему Minecraft bytecode проверены mixin targets, redirect invocation, pendingConnection и именованные GUI/LAN-поля. Это проверка контрактов; Mixin transformer внутри игры ещё не запускался.
- Обе реализации голосовых адаптеров проверены по API 13 закреплённых голосовых JAR, включая девять NeoForge 26.x: Plasmo 2.1.17/2.2.0-beta.2 и Simple Voice Chat 2.6.25. Это проверка bytecode linkage; настоящий голос ещё не проверен. Версии и ограничения: `VOICE_COMPATIBILITY.md`.
- Все три внутренних адаптера компилируются и объединяются в один JAR. Проверено отсутствие JNA/JNI bridge, чужих platform natives и случайно включённых voice API.
- Старые Java 8/17/21 варианты повторно прошли по 101 тесту, всего 303 выполнения. Все 12 текущих Windows-пакетов собраны и проверены по SHA-256.

Итоговые проверки Linux Runtime: `build/20261025-reliability-results.json`, `build/20261025-reliability-build.log`, `build/linux-runtime-results.json`. Предыдущая сверка GUI/voice: `build/20261023-gui-voice-regression-results.json`. ABI: `build/abi-results.json`. Отчёт Java 25: `steam-panama/build/reports/tests/test/index.html` и `build/panama-test-results.json`. Manifest новой сборки: `dist/windows-x64/26x-build-results.json`.

## Сборка

```text
python tools/fetch_jdks.py
python tools/build.py --variant neoforge-26.x
```

Можно задать `JAVA_HOME_25` вручную. Технический entry point `tools/build_26x.py` выполняет ту же сборку трёх внутренних целей. Папки `adapters/neoforge-26.x/build/targets/*` являются внутренними результатами разработки; для установки и публикации предназначен только объединённый файл из `dist/windows-x64`.

FFM требует native access. Тестовый запуск задаёт `--enable-native-access=ALL-UNNAMED`. При собственных JVM arguments лаунчера нужно учитывать это разрешение; мод не меняет настройки лаунчера автоматически.

## Что ещё предстоит подтвердить

1. Игровая загрузка всех трёх версий, применение выбранных mixins, расположение GUI-кнопок и управление миром через новую vanilla UI. Другие моды меню требуют отдельного сценария.
2. Настоящие Steam initialization, friends/avatar callbacks, direct/Relay, reconnect, отмена, kick, закрытие мира, остановка Steam и тяжёлый модпак. Проверяются логи обеих сторон.
3. Каждый voice-мод отдельно. Общие API adapters и локальный UDP проверены автоматически; конкретные релизные JAR voice-модов для 26.x ещё не проверены на реальном Minecraft.
ABI используемых привязок сверено с закреплённым снимком SDK 1.62: 243 проверки Panama, включая 41 downcall и connection callback. Источник является публичным зеркалом; оригинальный ZIP Valve не получен. Детали и ограничения: `STEAMWORKS_ABI.md`.

Сборка остаётся development до этих проверок. Коммит, push, GitHub Release и загрузка на CurseForge/Modrinth не выполнялись.

## Linux

Добавлен необязательный SBM Linux Runtime для всех трёх целей. Основной JAR выбирает библиотеку по ОС: Windows использует встроенную DLL, Linux требует зарегистрированный addon provider. Linux .so остаётся в аддоне. Ошибка Windows DLL не переключает backend на другую ОС.

Добавлены четыре различающихся размера/смещения FFM ABI. По 243 проверки выполняются для Windows и Linux. Ubuntu CI job подготовлен для исполнения тестов с Linux .so, но пока не запускался. На текущем Windows-хосте проверены оба layout-профиля, извлечение ресурсов, регистрация аддона и Windows native linkage. Настоящий Linux запуск Minecraft/Steam остаётся открытым.

Установка, API и проверки: [LINUX_RUNTIME.md](LINUX_RUNTIME.md).
