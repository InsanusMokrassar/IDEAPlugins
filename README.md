# IDEAPlugins

Три плагина для **IntelliJ IDEA 2026.3 (263.7307.200)** в одном Gradle-проекте.
У каждого плагина свой идентификатор, версия и установочный ZIP.

Общая Gradle group — `dev.inmo`. Пакеты кода и идентификаторы используют префикс `dev.inmo.idea`:
`dev.inmo.idea.koin.dependency.navigation`, `dev.inmo.idea.navigation.flow.diagram`,
`dev.inmo.idea.function.call.graph`.

| Папка | Плагин | ZIP в `local.dists` |
| --- | --- | --- |
| [`koin-dependency-navigation`](koin-dependency-navigation/README.md) | Переходы между регистрациями и получениями зависимостей Koin | `koin-dependency-navigation-1.0.4.zip` |
| [`navigation-flow-diagram`](navigation-flow-diagram/README.md) | Переходы и диаграмма `dev.inmo.navigation`; также самостоятельный CLI | `navigation-flow-diagram-0.1.2.zip` |
| [`function-call-graph`](function-call-graph/README.md) | Диаграмма вызовов Kotlin/JVM и Java | `function-call-graph-0.1.2.zip` |

## Собрать все плагины

Нужны **JDK 25**, **JDK 17** для анализатора/CLI Navigation Flow Diagram и Bash для скрипта.
Настройте `JAVA_HOME` на JDK 25. Единственный Gradle Wrapper 9.5.0 находится в корне:

```bash
./build-plugins.sh
```

Скрипт работает из любого текущего каталога и передаёт аргументы в Gradle.
Он запускает `collectPluginZips`, которая собирает ровно три плагина и обновляет `local.dists/`.
Архив CLI и ZIP от прежних сборок в выборку не попадают: используются выходы задач `buildPlugin`.
Старые ZIP в `local.dists` удаляются только после успешной сборки всех трёх; прочие файлы сохраняются.

По умолчанию Gradle загружает IDEA 2026.3. Для установленной IDE:

```bash
IDEA_PATH=/path/to/intellij-idea
JAVA_HOME="$IDEA_PATH/jbr" ./build-plugins.sh -PlocalIdePath="$IDEA_PATH"
```

`localIdePath` применяется ко всем трём плагинам. В Windows без Bash можно запустить
`gradlew.bat collectPluginZips`. Папка `local.dists`, а также любые файлы и папки с именем
`local.*` на любой глубине игнорируются корневым `.gitignore`.

Установите каждый ZIP через **Settings → Plugins → ⚙ → Install Plugin from Disk**.
Это три самостоятельных плагина, которые можно включать и обновлять по отдельности.
При переходе с прежних идентификаторов удалите старые плагины: новые устанавливаются отдельно.

## Проверки и разработка

Все команды выполняются из корня репозитория:

```bash
# Тесты всех модулей, проверка структуры и Plugin Verifier для всех трёх плагинов.
./gradlew check

# Те же проверки вместе со сборкой ZIP в local.dists.
./build-plugins.sh check

# Работа с отдельным плагином.
./gradlew :koin-dependency-navigation:buildPlugin
./gradlew :navigation-flow-diagram:idea-plugin:runIde
./gradlew :function-call-graph:test

# Самостоятельный CLI: JDK 17+, не входит в local.dists.
./gradlew :navigation-flow-diagram:cli:installDist
```

При необходимости добавьте `-PlocalIdePath=/path/to/intellij-idea` к любой команде.
`./gradlew build` также выполняет общие проверки и собирает три ZIP.
Отчёты тестов находятся в `<модуль>/build/reports/tests/test/`, отчёты совместимости —
в `<модуль>/build/reports/pluginVerifier/`. У каждого плагина отдельная IDE sandbox.

## Общая конфигурация

- `gradle.properties` — версия IDEA, диапазон совместимости `263.7307–263.*`, JDK 25
  для плагинов и независимая версия общего архива `bundleVersion`.
- `gradle/libs.versions.toml` — версии Kotlin, IntelliJ Platform Gradle Plugin и библиотек.
- `build.gradle.kts` — общие репозитории, SDK, toolchain, тесты, Plugin Verifier и сбор ZIP.
- `settings.gradle.kts` — состав модулей.
- `koin-dependency-navigation/build.gradle.kts`, `navigation-flow-diagram/build.gradle.kts` и
  `function-call-graph/build.gradle.kts` — независимые версии плагинов.

У Navigation Flow Diagram сохранены `analyzer`, `cli` и `idea-plugin`. Анализатор и CLI используют
JDK 17; Kotlin compiler остаётся `compileOnly` для анализатора и не включается в ZIP плагина.
Общая конфигурация не добавляет зависимостей между тремя плагинами.

## GitHub Actions и публикация

Workflow **Package IDEAPlugins** выполняет общие проверки и собирает единый
`IDEAPlugins-1.0.0.zip` с тремя установочными ZIP плагинов внутри. Имя архива и GitHub Release
берётся из текущего имени репозитория через `GITHUB_REPOSITORY`, а версия — из
`bundleVersion` в корневом `gradle.properties`. Версии отдельных плагинов независимы.

Один и тот же архив публикуется в GitHub Release **IDEAPlugins 1.0.0** с тегом
`IDEAPlugins-v1.0.0` и сохраняется как Actions artifact на 14 дней, без дополнительной ZIP-обёртки.
При ошибке отчёты всех модулей сохраняются на 7 дней. Для установки распакуйте общий архив,
затем установите каждый из трёх ZIP через **Install Plugin from Disk**.

Локально после `./build-plugins.sh` общий архив можно получить той же командой, что в CI:

```bash
GITHUB_REPOSITORY=InsanusMokrassar/IDEAPlugins python3 .github/scripts/package_plugins.py
```

Результат: `build/distributions/IDEAPlugins-1.0.0.zip`. В `local.dists` остаются только
три отдельных ZIP. Упаковка проверяет состав по plugin ID, сохраняет исходные ZIP без изменений
и использует фиксированные метаданные, чтобы повторная упаковка давала тот же SHA-256.

Теги и опубликованные ZIP не заменяются. Повторный запуск того же коммита проверяет SHA-256
существующего архива; иной коммит или содержимое требуют новой `bundleVersion` перед публикацией.
Прежние теги и релизы Koin с именами `v…` остаются без изменений.
Незавершённый draft можно завершить только при совпадающем теге и отсутствии конфликта assets.
Публикации выполняются последовательно; устаревший коммит не публикуется поверх нового `main`.

Проверки публикации без сети:

```bash
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
```

Gradle Wrapper проверяет SHA-256 дистрибутива. Подробности работы и ограничения каждого плагина
описаны в README соответствующей папки.
