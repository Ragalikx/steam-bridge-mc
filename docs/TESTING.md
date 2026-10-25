# Проверки 26.x

Дата актуализации: 25 октября 2026 года. Для NeoForge 26.x добавлен отдельный Java 25 backend; подробности в `docs/NEOFORGE_26X.md`. Артефакты `0.0.0-dev` предназначены для проверки новой реализации, а не для публикации как подтверждённого релиза.

Ошибка пустого receive batch, найденная аудитом 25 октября, исправлена. Общие и FFM-тесты покрывают null/empty receive, graceful drain, повторные pointers и cleanup после ошибок. Добавлены проверки 32 локальных TCP-мостов и изоляции LimitExceeded. Подробности и пределы этих проверок: [RELIABILITY.md](RELIABILITY.md).

## Автоматические проверки

`python tools/test.py` выполняет 101 проверку общего кода на каждой из Java 8, 17 и 21. HTML-отчёты и результаты JSON сохраняются в `build/test-reports/`.

| Java | JNA | Netty | Gson | Результат |
| --- | --- | --- | --- | --- |
| 8 | 4.4.0 | 4.0.23.Final | 2.2.4 | 101 passed |
| 17 | 5.12.1 | 4.1.97.Final | 2.8.9 | 101 passed |
| 21 | 5.14.0 | 4.1.97.Final | 2.8.9 | 101 passed |

Проверяется:

- Владение handles, замена owner и compare-remove при запоздалой очистке.
- Остановка native calls: очистка ждёт текущий вызов, новые вызовы отклоняются.
- Освобождение native receive batch при ошибках payload, count и Release.
- Приоритет terminal callback перед snapshot; ошибки snapshot не возвращают прошлые метрики.
- Передача heap/direct/composite ByteBuf, сохранение readerIndex, освобождение pending buffers.
- Отмена до accept, возврат listener-порта, порядок ранних сообщений, лимиты очередей.
- 32 настоящих локальных TCP-моста на одном event loop: заполненная native-очередь первого не блокирует остальные 31; после снятия нагрузки порядок данных сохраняется. Отдельно проверены отмена одного из восьми мостов и LimitExceeded одного из пяти.
- 1000 циклов очередей: payload budget возвращается к нулю. Это не 1000 игровых Steam reconnect.
- TCP FIN ждёт отправки pending данных; peer close передаёт полученные байты до EOF.
- Дочитывание native tail после peer close, включая нехватку общего memory budget; отмена исходящих retry при закрытии peer.
- Настоящие UDP send/receive, timeout, close, offsets, peek, multicast options, ограниченная голосовая очередь.
- Explicit voice socket и локальный relay возвращают пакеты переменного размера и освобождают порт.
- Simple Voice Chat API: обычный IP сохраняет свой сокет, другая socket implementation не заменяется, клиент и сервер читают пакеты разной длины, bind(0) регистрирует настоящий порт.
- 200 гонок voice claim/stop: после закрытия не остаётся захваченного сокета. Старый callback и старый game owner не закрывают новое голосовое соединение. Старый socket не отправляет байты через заменившую его игровую сессию.
- Host voice admission: неизвестный peer отклоняется, восемь игровых peer получают по одному handle, дубликат не принимается, pending handles очищаются. Это fake driver, не восемь настоящих Steam игроков.
- Plasmo API события, ошибка connect, закрытие delegate, обычный IP, другой addon socket и поздняя остановка старого UDP-сервера. Настоящий локальный UDP relay передаёт пакеты переменного размера.
- Хостовый UDP relay передаёт реальные пакеты по IPv4/IPv6 и отбрасывает пакет с постороннего source port. Wildcard bind сохраняет семью адреса.
- Общий client/server lifecycle: поздняя готовность Steam не сбрасывает Minecraft login, старая очистка не снимает нового владельца; миры и баны сохраняются/читаются, повреждённые файлы не перезаписываются. Ключи миров не зависят от системной locale.
- JSON validation, миграция Forge CFG/TOML без изменения оригинала, неверный reload не перезаписывает файл.
- Ограничение cache профилей и однократное освобождение вытесненных ресурсов.

`python tools/verify_voice.py --download` и `python tools/verify_voice.py --panama --download` сверяют обе скомпилированные реализации SBM с 13 голосовыми JAR: девять для NeoForge 26.x и четыре прежних Plasmo 2.1.17. Проверяются внешние методы/поля, annotations и реализации API-интерфейсов. Fixtures закреплены по SHA-256 и не включаются в мод. Это API linkage без Minecraft/Steam. [Версии и ограничения](VOICE_COMPATIBILITY.md).

`python tools/verify_gui.py --refresh-classpath` проверяет 104 поля восьми GUI-адаптеров, runtime aliases и соответствие сгенерированного кода metadata. Fabric проверяется и по intermediary JAR, Forge 1.16.5 по SRG JAR. Для Forge 1.19.2/NeoForge 1.20.1 runtime aliases проверяются по закреплённым mappings. [Подробности](GUI_COMPATIBILITY.md). Проверка включена в обычный build.py.

`python tools/verify_abi.py --download` компилирует C++ fixture по SHA-закреплённым публичным заголовкам Valve GameNetworkingSockets 1.4.1 (commit `505c697d0abef5da2ff3be35aa4ea3687597c3e9`). Независимый Zig/Clang вычисляет 55 размеров и offsets для Windows x64 и Linux x64. На текущем хосте эти значения сравниваются с JNA на Java 8/17/21. Публичные заголовки являются baseline, а не точными заголовками упакованного Steamworks SDK 1.62. Его отдельная сверка теперь выполняется командой `python tools/verify_abi.py --sdk-mirror --panama --download`: используется закреплённый снимок SDK 1.62, проверяются 243 значения Panama и 92 значения JNA-сигнатур на каждой JVM, дополнительно к 55 layout checks. Оригинальный ZIP Valve не проверен. [Объём и источник](STEAMWORKS_ABI.md).

Выявлено и исправлено различие упаковки callback: Windows size 712, `m_info` offset 8; Linux size 704, offset 4. Отдельный тест проверяет запись/чтение Linux pack(4). Остальные пять отображённых структур имеют одинаковые layouts на этих двух платформах. Результат: `build/abi-results.json`; `exactSdkHeaders: false` обозначает публичный baseline. C cross-compilation не запускает Linux Java или Linux Steam runtime на Windows.

Сборщик проверяет natives выбранной ОС, общий код, допустимый class-file target, лицензии, наличие voice integration и регистрацию её в metadata. Voice API, Plasmo, Kotlin и ASM не должны случайно попасть внутрь JAR. Manifest содержит SHA-256 исходных кодовых inputs и dependency lock, рядом сохраняются хэши артефактов. Основные JAR содержат Windows natives. Для всех поддерживаемых версий собираются четыре Linux Runtime по группам Java 8/17/21/25. Упакованная JNA сохраняет Linux bootstrap своей версии. Компиляция не проверяет события и mixins в работающем Minecraft.

`python tools/verify_native.py` загружает Steam SDK/JNI, выделяет память JNA, проверяет Java model layout и 19 exports. Steam API не инициализируется, Spacewar не запускается. Сообщение Log4j о fallback logger относится к автономному стенду. Java layout и exports не доказывают соответствие всех offsets настоящему C ABI.

Последний локальный native smoke прошёл для всех 11 Windows JAR на соответствующей Java 8/17/21. Linux native runtime локально не проверен.

Для всех 11 старых интеграций Ubuntu CI должен запускать проверку готового JNA runtime с настоящими Linux библиотеками. Для NeoForge 26.x предусмотрены Linux FFM-тесты и packaged addon smoke. Workflow в GitHub пока не запускался.

## NeoForge 26.x на Java 25

Один JAR включает три внутренних адаптера. Выполнено 112 тестов общего кода/voice/FFM на Java 25, включая настоящие SDK identity calls без Steam initialization, callback upcall, освобождение receive batch при ошибках и закрытие библиотеки. `Verify26xContracts.java` проверяет mixin targets и reflection fields по настоящим Minecraft JAR для всех трёх целей. Проверка упаковки исключает JNA, JNI bridge steamworks4j и чужие natives. Размер и SHA-256: `dist/windows-x64/26x-build-results.json`.

Все 12 Windows-пакетов собраны. Упаковка, class targets, sidecar SHA-256 и общий отпечаток исходников проверены повторно. Тесты старого JNA backend на Java 8/17/21 также повторно прошли: по 101 на каждой JVM. Native smoke JNA проверяет только старые 11 вариантов; для 26.x используются Java 25 FFM tests.

Матрица 26.x: базовая игровая загрузка и UI каждой из 26.1/26.2/26.3, затем совместный набор Steam/voice/load scenarios. Объединение публикации в один файл не заменяет проверки трёх Minecraft-интеграций. Актуальные ограничения и точные NeoForge версии: `docs/NEOFORGE_26X.md`.

## Необязательный Linux Runtime

Четыре аддона объединены по Java 8/17/21/25. Матрица Minecraft/загрузчиков: [LINUX_RUNTIME.md](LINUX_RUNTIME.md). Группа выбирается по целевой версии Minecraft, а не по более новой JVM в лаунчере.

`python tools/verify_legacy_linux_runtime.py` проверяет все 11 старых вариантов: реальные loader-аннотации/Fabric preLaunch API, регистрацию из готового JAR, два закреплённых ELF, class targets, metadata, SHA-256, JNA bootstrap и загрузку native текущей ОС. На Windows исполняются DLL. Этот стенд не запускает Minecraft или полный загрузчик.

`python tools/verify_linux_runtime.py` проверяет metadata, единственный регистрационный класс, закреплённый x64 ELF, отсутствие JNI/JNA и sidecar SHA-256. Через ClassLoader запускается конструктор собранного аддона. На Windows проверяется сохранение DLL до/после регистрации; на Linux отсутствие provider должно завершаться подсказкой, затем addon разрешает загрузку .so. Проверяются exports текущей ОС и наличие всех 41 функций в dynamic symbol table Linux ELF. На Windows эта ELF-проверка не исполняет Linux-код.

Шесть общих тестов проверяют регистрацию JNA provider, извлечение обеих .so, неверный API, повреждённые ресурсы/cache и конкурирующую запись. Девять отдельных Java 25 тестов проверяют выбор ОС и архитектуры, provider API, отсутствие аддона, повреждённые native/cache, параллельное извлечение и оба callback profiles. ABI C++ probe проверяет по 243 значения FFM для обеих платформ. [Установка и ограничения](LINUX_RUNTIME.md).

## Один ручной прогон после доводки

1. На всех 11 старых вариантах и трёх целях общего 26.x JAR проверить запуск, metadata/mixins, меню, Steam-друзей, обычное подключение по IP, Steam-вход и повторный вход. Сохранять логи обоих игроков при каждом разрыве.
2. Расширенные сценарии выполнить на представителях Java 8, 17 и 21: тяжёлый модпак и мир, direct P2P и принудительный Relay, ESC, смена хоста, закрытие мира, остановка Steam, kick с сохранением причины. Дополнительно проверить loader-specific сценарий там, где его интеграция отличается.
3. Проверить 5 и больше участников, включая медленного клиента. Измерить задержки, throughput, очереди, heap/direct/native память, GC, потоки и сокеты. Ёмкость и превосходство по скорости пока не подтверждены.
4. Голос: Simple Voice Chat API, старый fallback на соответствующей версии, Plasmo 2.1.17 на современных загрузчиках, custom port, bind(0), game-port режим, reconnect, kick, отсутствие voice-мода, IPv6 bind и мод с конкурирующей socket implementation. Каждый voice-мод проверяется отдельно; одновременный SVC/Plasmo tunnel не поддерживается. Границы и лицензии описаны в `docs/VOICE_COMPATIBILITY.md`.
5. Обновлять длинный список друзей и делать resync: число текстур и память должны стабилизироваться. Cache ограничен 256 записями на карту, но реальные Minecraft texture lifetimes проверяются в игре.
6. Прогнать Windows на настоящих Steam-клиентах. Сверить release 1.2 и ветку 26.x на одинаковом модпаке и мире. Для первоначального разрыва нужен лог хоста с совпадающим временем.

После найденной игровой ошибки повторяется затронутый сценарий и связанные проверки. Обещать отсутствие повторной проверки после любого будущего исправления нельзя.

## Проверки перед публикацией

- Сверка используемых layouts/signatures с закреплённым снимком SDK 1.62 выполнена. При получении доступа к оригинальному ZIP Valve можно отдельно сравнить его заголовки и DLL; оригинальный архив пока не проверен. Источник и границы подтверждения описаны в `STEAMWORKS_ABI.md`.
- После одобрения владельцем запустить GitHub CI, проверить SHA-256 и attestations скачанных артефактов. Сейчас workflow проверен локальными инструментами, но на GitHub не выполнялся.
- Отдельно измерить воспроизводимость сборки. Изменяемый ForgeGradle 2.1 snapshot заменён timestamp-артефактом и SHA-256; десять критичных native/API JAR проверяются до компиляции. Вся транзитивная цепочка Minecraft этими locks не покрыта.

## Дальнейшая архитектура

JSON, TCP/UDP транспорт, voice lifecycle, состояния подключения, SDK runtime, клиент/хост, баны, настройки миров и cache профилей теперь общие. Фасады адаптеров сохраняют GUI, Minecraft login pipeline, scheduler и загрузку текстур. Сборка в каждый JAR включает общий код; отдельный core-мод не требуется.

Для старых JVM значительное уменьшение потребует замены JNA и отдельной проверки native backend. В 26.x JNA уже исключена за счёт Panama; текущий общий JAR содержит только Windows native и Java-код. Рекордный размер и выигрыш производительности без измерений не заявляются.
