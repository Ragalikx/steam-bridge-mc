# Поля GUI и runtime mappings

Проверка от 23 октября 2026 года охватывает восемь адаптеров: Fabric 1.16.5/1.19.2/1.20.1/1.21.1, Forge 1.16.5/1.19.2, NeoForge 1.20.1/1.21.1. Для 26.x сохраняется отдельная проверка Verify26xContracts.java по всем трём Minecraft JAR.

В восьми адаптерах удалён поиск полей по типу и порядку. core/NamedField разрешает конкретное поле объявленного vanilla-класса, проверяет тип и кэширует Field. Это исключает выбор однотипного поля другого назначения и поля, скрытого подклассом модифицированного экрана. Отмена соединения меняет только флаг отмены, закрывает существующее TCP-соединение и очищает его ссылку. Выбранная multiplayer-запись читается через типизированный vanilla API.

`tools/gui-fields.json` содержит 104 привязки. Из него `tools/generate_gui_fields.py` создаёт восемь GuiFields. Сборка проверяет соответствие генерации metadata, поля в compile Minecraft JAR и закреплённые runtime mappings.

| Адаптеры | Runtime names | Дополнительная проверка |
| --- | --- | --- |
| Fabric, четыре версии | intermediary | Поля в intermediary JAR и Tiny mappings |
| Forge 1.16.5 | SRG field_... | Поля в joined SRG JAR; закреплены Mojang и MCP mappings |
| Forge 1.19.2, NeoForge 1.20.1 | SRG f_... | Сопоставление по закреплённым srgToOfficial mappings |
| NeoForge 1.21.1 | named | Поля в compile Minecraft/NeoForge JAR |

На Forge/Fabric 1.16.5 LAN-режим хранится строкой. Прежний поиск GameType/GameMode ничего не находил и подставлял Survival. Теперь строка преобразуется vanilla методом с резервным значением текущего сервера. В остальных версиях читается конкретный GameType. Порт сохраняется там, где vanilla экран предоставляет выбор порта. Уже опубликованный LAN-сервер используется повторно; после неуспешной публикации Steam-хост не запускается.

Повторение: `python tools/verify_gui.py --refresh-classpath`. Нужны JDK адаптеров и JDK 25 для Class-File API. Обычный tools/build.py тоже выполняет проверки после компиляции. Результаты: `build/gui-contract-results.json` и `adapters/<variant>/build/gui-contract-results.json`. Minecraft JAR и полные mappings остаются в локальном cache, в SBM включается только код привязок.

Это проверка структуры классов и mappings, без исполнения loader transformers, mixins, экрана или соседних модов. Старые Forge 1.7.10/1.8.9/1.12.2 не входят в эти 104 привязки и не переписаны этим этапом. Игровой прогон всех заявленных интеграций остаётся обязательным.
