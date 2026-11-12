# Заказы: профиль разработки

Стек подтверждён AGENTS.md и структурой: Kotlin/Compose/MVVM, Room, отдельные core:domain и core:data. Действующие бизнес-правила и порядок передачи задач Kimi сохранять из AGENTS.md.

Команды текущего CI для изменения кода:
- `bash ./gradlew :core:domain:test --no-daemon`
- `bash ./gradlew :app:lintDebug --no-daemon`
- `bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:data:assembleDebugAndroidTest --no-daemon`

Последняя команда собирает Android-тесты, но не выполняет их. CI также запускает `:core:data:connectedDebugAndroidTest :app:connectedDebugAndroidTest` на Android 35 и `python3 tests/verify_migration_sql.py`. Для изменения Room/миграций отдельно выполнить доступные инструментальные тесты на подготовленном эмуляторе; прочитать конфигурацию core:data до выбора команды. Миграционный SQL также проверяется имеющимся tests/verify_migration_sql.py; прочитать его параметры перед запуском.

Сохранять точные денежные вычисления, независимость domain, разделение доставки/товарного расхода/передачи денег, null и ноль, историю курса и корректировок. Отображение целых сумм без ,00 не менять на хранение без копеек.

По прямому решению Дмитрия от 12.11.2026 репозиторий оставлен Public. Не добавлять ключи, базы и реальные заказы; видимость без отдельного поручения не менять.
