# Проверка используемого Steamworks ABI

Состояние на 23 октября 2026 года. Проверяется ABI, используемый SBM: структуры сетевого транспорта, manual dispatch, нативные функции и callback. Это не проверка всех неиспользуемых интерфейсов Steamworks и не игровая сессия.

## Источник SDK

Закреплён снимок [Steamworks SDK 1.62](https://github.com/rlabrecque/SteamworksSDK/tree/34d9338aa892213861bd9f84c4dd9c5b7fd23de7), commit `34d9338aa892213861bd9f84c4dd9c5b7fd23de7`. Его Readme начинается с `v1.62 14th March 2025`. SHA-256 файлов находятся в `tools/steamworks-sdk-1.62.json`.

Источник является публичным зеркалом. Оно нормализует окончания строк SDK в LF. Сравнение предоставленных заголовков с закреплённым снимком нормализует только CRLF в LF; любые другие изменения отклоняются. Все используемые `.h` проверяются до компиляции. Старый GNS baseline не принимается за точный SDK.

DLL снимка побайтово совпадает с `steam_api64.dll` из закреплённого `steamworks4j:1.10.0`, используемой в SBM. SHA-256 DLL: `e082bf5c9f881c822b1540a76b74f9d15e18019a73ebd206a559595badcb7f65`.

[Официальный архив Valve](https://partner.steamgames.com/downloads/steamworks_sdk_162.zip) перенаправляет на вход в Steamworks. Он не получен. Побайтовая идентичность оригинальным заголовкам из этого ZIP не подтверждена. В отчёте намеренно различаются `exactSdkHeaders: true` (сверка с закреплённым снимком версии 1.62) и `officialArchiveVerified: false` (оригинальный ZIP не проверен). Совпадение DLL не доказывает происхождение каждого заголовка.

SDK скачивается только в пользовательский cache. Заголовки, Readme SDK, C++ probes и fixtures не включаются в JAR мода.

## Что сравнивается

- C++ compiler вычисляет 55 размеров и смещений шести сетевых структур для Windows x64 и Linux x64. JNA сравнивается с ними на Java 8/17/21.
- Panama использует именованные константы offsets и SDK configuration values. Для Windows x64 и Linux x64 сравниваются по 74 значения, включая manual dispatch, persona/avatar callbacks, флаги и конфигурацию транспорта.
- Дескрипторы 41 FFM downcall и одного connection callback сравниваются с объявлениями из `steam_api_flat.h`. Возвращаемый тип, число и типы аргументов вычисляются через C++ function traits. Всего для Panama получается по 243 проверки на каждую из двух платформ. Четыре Linux значения вынесены в отдельный профиль: размер callback 704, m_info offset 4, m_eOldState offset 700, размер CallbackMsg_t 20. Packed callback проверяется Java-тестом. SDK Linux .so закреплена отдельным hash и совпадает с native-зависимостью аддона.
- Для JNA непосредственно читаются Java-объявления `FlatApi` и callback. Проверяются 20 сигнатур, всего 92 отдельных значения на каждой Java 8/17/21. Java boolean при таком сравнении считается 32-битным типом JNA и не проходит как однобайтовый C++ bool.
- Проверяется совпадение SDK DLL с fixture закреплённой зависимости сборки.

C++ компилируется до LLVM IR без запуска Steam. FFM descriptor check не вызывает сетевые функции SDK. Проверка типов не подтверждает успешную Steam initialization, реальный callback lifecycle или устойчивость игровой сессии.

## Исправления, найденные при сверке

`SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_SteamNetConnectionStatusChanged` возвращает bool. Прежнее объявление void исправлено в обеих привязках. Отказ установки callback теперь останавливает инициализацию вместо продолжения без обработчика статуса соединений.

В JNA C++ bool представлен byte, включая возвращаемые значения CloseListenSocket, CloseConnection, GetConnectionInfo, SetConfigValue и аргумент linger. Внешний Java API сохраняет boolean и явно преобразует 0/1.

Если SDK не подтверждает снятие callback, Java target перестаёт доставлять события, а callback/stub сохраняется до завершения SteamAPI.shutdown. Это позволяет не освобождать callback-память, пока SDK ещё может держать указатель. После успешного shutdown отложенные ресурсы освобождаются.

Для Panama введён единый реестр FFM descriptors. Возвращаемый тип и число аргументов проверяются перед нативным вызовом. Определение ABI по Java-объектам при каждом вызове удалено. Выигрыш производительности не измерялся.

## Повторение проверки

```text
python tools/verify_abi.py --sdk-mirror --panama --download
```

Для собственного распакованного SDK 1.62:

```text
python tools/verify_abi.py --sdk-headers C:/path/to/sdk/public/steam --panama
```

Для прежнего независимого GNS baseline остаётся `python tools/verify_abi.py --download`. Такой отчёт имеет `exactSdkHeaders: false`.

Результаты: `build/abi-results.json`, `build/abi/panama-results.json`, `build/20261023-sdk-provenance-tests.json`. Итоговый прогон тестов и сборок: `build/20261023-sdk-final-validation.log`. CI настроен на закреплённый SDK snapshot и Java 25, но на GitHub ещё не запускался.

До release остаются Minecraft/mixin/UI проверки, настоящие Steam direct/Relay, голос на конкретных версиях модов, многопользовательская нагрузка и измерения производительности. Эти пункты не закрываются проверкой ABI.
