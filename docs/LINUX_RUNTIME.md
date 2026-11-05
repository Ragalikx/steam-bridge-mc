# SBM Linux Runtime

Необязательный клиентский мод для Linux x64. Для всех поддерживаемых версий SBM собираются четыре runtime JAR по группам Java. Требуется обновлённый основной SBM с API регистрации native provider; старые release/dev JAR без него не подходят.

| Runtime | Совместимые версии и загрузчики |
| --- | --- |
| Java 8 | Forge 1.7.10, 1.8.9, 1.12.2, 1.16.5 |
| Java 17 | Forge 1.19.2; Fabric 1.19.2, 1.20.1; NeoForge 1.20.1 |
| Java 21 | Fabric и NeoForge 1.21.1 |
| Java 25 | NeoForge 26.1, 26.2, 26.3 |

Выбор определяется строкой Minecraft/загрузчика в таблице. Например, Minecraft 1.20.1 с более новой JVM всё равно использует runtime группы Java 17. Установка нескольких runtime одновременно не поддерживается: у них один mod ID. Это объединение Linux-аддонов; основные старые SBM JAR остаются отдельными для своих Minecraft-интеграций.

| ОС | Файлы в mods |
| --- | --- |
| Windows x64 | Подходящий основной SBM |
| Linux x64 | Тот же основной SBM и один runtime из таблицы |

SBM выбирает платформу по ОС и архитектуре Java. Windows использует встроенные DLL, даже при установленном Linux Runtime. Ошибка DLL не включает Linux backend. Linux использует зарегистрированный provider; без него выводится подсказка установить runtime. ARM, 32-bit Java и macOS не поддержаны. Steam должен работать в той же пользовательской среде. Стороннего приложения и скачивания native при запуске нет.

## Устройство

Java 8/17/21 используют общий JNA backend. Аддон содержит два класса регистрации, libsteam_api.so и JNI bridge libsteamworks4j.so, metadata загрузчиков и лицензии. Core, транспорт, GUI, voice и JSON-настройки остаются в основном SBM. Классы Minecraft, JNA и ASM в аддон не включены. Java 25 использует существующий Panama runtime: один класс регистрации и libsteam_api.so, без JNA/JNI bridge.

Fabric регистрирует provider через preLaunch method reference. Forge/NeoForge регистрируют его в конструкторе мода, до клиентского setup основного SBM. Java 8 wrapper имеет аннотации старого cpw FML и нового Forge; один JAR используется обоими семействами. Проверка готового класса использует настоящие аннотации/API каждого загрузчика. Ресурсы читает загрузчик класса аддона, без ручного сканирования mods.

JNA может потребоваться Minecraft до создания модов. Поэтому основной JNA-пакет, если он включает свою JNA, сохраняет bootstrap libjnidispatch.so для Linux вместе с Windows bootstrap. Bootstrap соответствует версии упакованной JNA. При внешней JNA используется библиотека загрузчика. Steam Linux natives находятся в аддоне. Panama-пакет JNA не содержит.

Источник и cache native сверяются по закреплённому SHA-256. Для JNA обе .so проверяются до записи и извлекаются в один каталог, ключ которого включает оба хэша. libsteamworks4j.so не имеет RUNPATH и находит libsteam_api.so по SONAME только потому, что steamworks4j загружает steam_api первой; этот порядок обязателен. Извлечение использует временные файлы и повторную проверку результата при одновременном запуске экземпляров. Повреждённый cache отклоняется без перезаписи; ошибка называет файл, который нужно удалить.

Аддон Java 8 объявляет acceptableRemoteVersions="*": FML 1.7.10–1.12.2 регистрирует сетевую проверку для каждого @Mod, включая clientSideOnly, и без этого Linux-хост LAN отклонял бы гостей без аддона (например, с Windows). Аддон Java 21 помечен dist=CLIENT и не загружается на выделенном сервере NeoForge. Путь: ~/.cache/steambridge/native/. Несовместимый API и повторная регистрация provider отклоняются.

## ABI и происхождение библиотек

В Panama есть профили Windows/Linux. Проверяемые различия SDK:

| Значение | Windows x64 | Linux x64 |
| --- | --- | --- |
| Размер connection callback | 712 | 704 |
| Смещение m_info | 8 | 4 |
| Смещение m_eOldState | 704 | 700 |
| Размер CallbackMsg_t | 24 | 20 |

Linker использует ABI текущей JVM. Packed Linux callback проверяется отдельной записью/чтением, включая невыровненный SteamID внутри m_info. C++ probe сопоставляет по 243 значения FFM layouts/constants/signatures с SDK 1.62 для каждой платформы. JNA callback также учитывает Linux packing; его исполнение на Linux входит в подготовленный CI.

Библиотеки берутся из закреплённой зависимости steamworks4j 1.10.0. libsteam_api.so побайтово совпадает с закреплённым SDK snapshot 1.62:

- libsteam_api.so: 0f2c41c20644503c17e13498203986493332fc8296dbd78493bc1fed352ec0cc.
- libsteamworks4j.so: 4d255292c9ee257087053f8b1c0f0ab5a8f831193d3e6f37a10e5c046d6e1886.

Locks: tools/linux-runtime.json и tools/linux-runtime-jna.json. Источник SDK и ограничение проверки оригинального ZIP Valve описаны в STEAMWORKS_ABI.md.

## Сборка и проверки

```text
python tools/build.py
python tools/test.py
python tools/verify_legacy_linux_runtime.py
python tools/verify_linux_runtime.py
python tools/verify_abi.py --sdk-mirror --panama --download
```

Основные SBM находятся в dist/windows-x64/. Название каталога сохраняется ради существующего сборщика; эти же JAR используются на Linux с аддоном. Runtime находятся в dist/addons/:

- steambridge-linux-runtime-java8-0.0.0-dev.jar
- steambridge-linux-runtime-java17-0.0.0-dev.jar
- steambridge-linux-runtime-java21-0.0.0-dev.jar
- steambridge-linux-runtime-neoforge-26.x-0.0.0-dev.jar

Рядом лежат .sha256. Матрица групп хранится в tools/linux-runtime-groups.json. Сборка одного варианта также создаёт его runtime. Для 26.x сборщик требует одинакового содержимого аддона на всех трёх NeoForge.

На Windows пройдены 101 тест на каждой Java 8/17/21 и 112 на Java 25. Шесть новых общих тестов проверяют JNA provider, отсутствие аддона, API, SHA-256, повреждённые ресурсы/cache и конкурирующее извлечение обеих .so. Отдельные проверки Panama охватывают выбор ОС и оба callback profiles.

Контрактный smoke всех 11 старых SBM JAR использует настоящие loader-аннотации или Fabric PreLaunchEntrypoint, затем регистрирует упакованный provider, проверяет извлечение и 19 native exports хоста. Для Java 25 проверяются конструктор аддона, регистрация, 41 export хоста и те же 41 функции в Linux ELF. На Windows эти проверки исполняют DLL, а Linux .so только читают и сверяют по хэшу/символам. Minecraft-загрузчики при этом не запускаются.

Ubuntu CI подготовлен для всех 11 старых интеграций и Java 25: он должен загружать настоящие Linux .so. CI ещё не запускался. Локально Linux JVM, Minecraft и Steam не запускались. До игровых проверок Linux остаётся development.

Ручной прогон включает запуск каждой Minecraft-интеграции, Steam, соединения Windows/Linux в обоих направлениях, direct/Relay, голос и reconnect. Отдельные старые голосовые ограничения не меняются от установки Linux Runtime.
