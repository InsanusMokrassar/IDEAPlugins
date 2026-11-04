# Koin DI Navigation

Плагин для IntelliJ IDEA **2026.2.4 (ветка 262)** с Kotlin K2. Добавляет переходы между регистрациями Koin и местами получения зависимостей. Подходит для DI внутри MicroUtils `StartPlugin.setupDI` / `StartupLauncher` и обычных Koin-модулей.

## Установка и работа

1. Скачайте ZIP из [GitHub Releases](https://github.com/InsanusMokrassar/PluginsDIIdiaHighlight/releases/latest) или соберите `build/distributions/PluginsDIIdiaHighlight-1.0.2.zip` командой ниже.
2. В IDEA откройте **Settings → Plugins → ⚙ → Install Plugin from Disk**, выберите ZIP и перезапустите IDE.
3. Откройте Kotlin-проект, выполните Gradle Sync и дождитесь индексации IDEA, затем фоновой задачи **Indexing Koin DI navigation**. Значки появятся после подготовки кэша.
4. Синий значок слева от `single` / `factory` ведёт к получениям зависимости. Зелёный значок слева от `get` / `getOrNull` ведёт к регистрациям. Один результат открывается сразу; несколько — в списке с именем файла и строкой. При отсутствии результата появляется подсказка.

Значки можно отключить в **Settings → Editor → General → Gutter Icons → Koin DI navigation**.

Для обновления с предыдущей версии установите новый ZIP тем же способом: идентификатор плагина сохранён. После открытия проекта в IDEA кэш подготовится автоматически.

## Что распознаётся

- Koin: `single`, `factory`, `scoped`, `singleOf`, `factoryOf`, `scopedOf`; `get`, `getOrNull`, `getAll`, `inject`, `injectOrNull` у Koin/Scope/KoinComponent; `koinInject` из Koin Compose.
- MicroUtils: `singleWithBinds`, `factoryWithBinds`, варианты `WithRandomQualifier` и `WithRandomQualifierAndBinds`, `singleSuspend`, `factorySuspend`, строковые qualifiers, `getAllDistinct`, `getAny`, `lazyInject`.
- Выведенные Kotlin-типы: `single { Service() }`, `Consumer(get())`; import aliases и полные имена.
- Прямые обёртки в исходниках проекта: функция с expression body или единственным `return` и свойства с таким getter. Например, `singleMeStateFlow`, `Koin.meStateFlow` и `Scope.meStateFlow` из WishlistApp. Обёртка должна напрямую возвращать распознанный DI-вызов; глубина цепочки ограничена шестью уровнями. Учитываются также `named` / `bind` из options-блока возвращаемого вызова.
- `bind` / `binds`, известные строковые и типовые qualifiers, ссылки на неизменяемые свойства с qualifiers. `getAll` / `getAllDistinct` / `getAny` учитывают также регистрации со случайными qualifiers.

Вызовы определяются по разрешённым Kotlin-символам. Обычные `List.single()`, `Map.get()`, `Result.getOrNull()` и `ParametersHolder.get()` не считаются получением DI.

## Границы статического анализа

Поиск показывает **кандидатов во всех исходниках проекта**, включая другие модули и source sets: регистрация может находиться в приложении, зависящем от модуля с `get()`. Плагин не исполняет `StartupLauncher`, не определяет выбранный набор `StartPlugin`, активный Koin-контейнер, scope, условия регистрации, порядок overrides или конфигурацию конкретного запуска. Поэтому несколько регистраций — ожидаемый результат.

Koin использует ключ класса (`KClass`) и qualifier, стирая аргументы generic-типа. Поэтому `List<String>` и `List<Int>` с одинаковым qualifier являются кандидатами друг для друга; полный выведенный тип отображается в подсказке. `get<Lazy<Service>>()` остаётся поиском `Lazy`, а `inject<Service>()` — поиском `Service`.

Динамический qualifier сопоставляется только при общей статически распознаваемой ссылке. Вычисляемые значения, изменяемые переменные, произвольный control flow и reflection не интерпретируются. У `WithBinds` автоматически добавляются supertypes только при стандартном `bindFilter`; переданный фильтр не исполняется. Произвольные обёртки распознаются только в исходниках проекта, включая generated source roots. Встроенные операции Koin/MicroUtils работают по их символам без attached sources. Цели переходов — исходники проекта.

## Сборка и тесты

Нужен **JDK 25**. Gradle Wrapper 9.5.0 включён. По умолчанию сборка загружает IDEA 2026.2.4; можно использовать уже установленную IDE:

```bash
IDEA_PATH=/path/to/intellij-idea
JAVA_HOME="$IDEA_PATH/jbr" ./gradlew test verifyPluginStructure buildPlugin \
  -PlocalIdePath="$IDEA_PATH"
```

ZIP появится в `build/distributions/`. Для отдельного экземпляра IDE с плагином:

```bash
JAVA_HOME="$IDEA_PATH/jbr" ./gradlew runIde -PlocalIdePath="$IDEA_PATH"
```

Проверка бинарной совместимости с выбранной IDE: `./gradlew verifyPlugin -PlocalIdePath="$IDEA_PATH"` (также с JDK 25). Verifier использует bundled-зависимости выбранной IDE в offline-режиме. Диапазон совместимости намеренно ограничен веткой 262 из-за изменений Kotlin Analysis API; рекомендация Gradle удалить `until-build` здесь не применяется.

Тесты используют IntelliJ fixtures, настоящий K2 Analysis API, Koin **4.2.2** и MicroUtils **0.30.1**. Проверяются оба направления переходов, выведенные типы, qualifiers, bindings, обёртки/свойства и отсутствие ложных маркеров у обычных Kotlin API. Отдельные проверки покрывают повторные переходы без Kotlin-анализа, обновление кэша после правок, изменение обёртки в другом файле, отмену подготовки и настоящий фоновый обработчик с UI callbacks. HTML-отчёт: `build/reports/tests/test/index.html`.

## Автоматическая сборка и публикация

Workflow **Package plugin** запускается при каждом push в основную ветку `main` и вручную через **Actions → Package plugin → Run workflow**. Он выполняет тесты, проверку структуры и совместимости, затем собирает установочный ZIP. Версия берётся из `plugin.xml` внутри готового архива, исходное значение задаётся в `build.gradle.kts`.

Архив сохраняется как Actions artifact на 14 дней и публикуется в GitHub Release с тегом `v<версия>`, например `v1.0.2`. Повторная сборка той же версии заменяет ZIP, обновляет описание релиза и перемещает тег на собранный коммит. Предыдущий ZIP удаляется после успешной загрузки нового. Для новой версии достаточно изменить `version` в `build.gradle.kts`.

При ошибке workflow сохраняет доступные отчёты тестов и Plugin Verifier в артефакте `verification-reports-<run>-<attempt>` на 7 дней. Их можно скачать со страницы соответствующего запуска в Actions.

Публикации выполняются последовательно. Повторный запуск старого коммита сохранит Actions artifact, но пропустит Release, если `main` уже указывает на другой коммит. Используется штатный `GITHUB_TOKEN` с `contents: write`; отдельный PAT не нужен. Релизы доступны пользователям с доступом к приватному репозиторию.

## Индексация и производительность

С версии **1.0.1** анализ проекта вынесен из обработчика клика. Встроенный `FileBasedIndex` IDEA сохраняет синтаксические имена вызовов, алиасы и прямые обёртки вместе с обычной индексацией. После готовности индексов плагин в фоне разрешает Kotlin-символы в файлах-кандидатах и собирает таблицы регистраций и получений по типу зависимости. Клик ищет результат в этих таблицах. `ast-index` для работы плагина не требуется.

Семантический кэш хранится в памяти проекта и заново готовится после открытия проекта в IDEA. Первичная подготовка большого проекта занимает время; её состояние видно в фоновых задачах IDEA. Правки объединяются с задержкой 250 мс. Уже обработанные файлы сохраняются при прерывании анализа записью; обычная правка тела функции обновляет затронутые файлы. Кэш также отслеживает исходники используемых обёрток и qualifiers, включая превращение обычной функции в DI-обёртку. Изменения объявлений, состава обёрток или корней проекта могут потребовать полной фоновой перепроверки.

Если нажать на существующий значок во время обновления, появится подсказка, а переход выполнится после готовности кэша. Разрешение Kotlin-символов и обход проекта выполняются вне EDT. Подписи списка переходов готовятся заранее и не обращаются к PSI из Swing renderer.

В тестовом стенде на 200 файлах / 1 800 DI-точках проверяются 100 повторных запросов без повторного анализа файлов и сохранение кэша после правки посторонней функции. Этот тест не является замером задержки на реальном пользовательском проекте.

API-основание: [IntelliJ line markers](https://plugins.jetbrains.com/docs/intellij/line-marker-provider.html), [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html), [Kotlin Analysis API](https://kotlin.github.io/analysis-api/).
