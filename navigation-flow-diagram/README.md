# Navigation Flow Diagram

Плагин IntelliJ IDEA и самостоятельный анализатор переходов для `dev.inmo.navigation`.
Ищет места, где конфигурация передаётся в навигацию, связывает их с View и строит граф переходов.
Анализатор поддерживает фабрики View, интеракторы с
`NavigationNode<Config, Base>` и обёртки над операциями навигации.

## Работа в IDEA

Версия плагина **0.1.2** предназначена для **IntelliJ IDEA 2026.3, ветка 263**,
начиная с build **263.7307**, с включённым Kotlin. Проверена на **263.7307.200**.
Идентификатор плагина: `dev.inmo.idea.navigation.flow.diagram`.

1. Установите ZIP плагина через **Settings → Plugins → ⚙ → Install Plugin from Disk**.
2. Откройте проект и дождитесь индексации IDEA и фоновой задачи **Analyzing dev.inmo.navigation**.
3. Синий значок у имени View или конфигурации показывает места её передачи в навигацию.
   Один результат открывается сразу, несколько — в списке с файлом и строкой.
4. Зелёный значок у `push`, `replace` или распознанной обёртки ведёт к View назначения.
   Реализации из разных source sets сохраняются как отдельные цели.
5. Откройте **View → Tool Windows → Navigation Flow Diagram**. Вкладка **Diagram** показывает граф;
   двойной щелчок по узлу открывает исходник. Фильтр **Screen** оставляет выбранный экран и его соседей.
   Во вкладке **Transitions** двойной щелчок или Enter открывает конкретный вызов.
6. **Copy Mermaid** и **Export .mmd** экспортируют видимый граф. Вкладка **Unresolved** объясняет
   случаи, для которых анализ не смог доказать связь.

Сплошная стрелка обозначает переход, пунктирная — вложенный View, например `InjectNavigationNode`.
Обратные операции и вызовы с неизвестным концом остаются в таблице и JSON: инструмент не придумывает
экран, на который приведёт `pop` или `drop`.

Анализ выполняется в фоне; сбор маркеров и выбор цели читают готовый результат.
Правки объединяются с задержкой 300 мс и запускают повторный анализ проекта. Устаревшие результаты
не используются для перехода к исходнику. **Refresh** позволяет повторить отменённый анализ.
Для самой диаграммы не нужны браузер, сервер или доступ в интернет.

## Самостоятельный CLI

CLI работает на **JDK 17+**, без установленной или запущенной IDEA. Дистрибутив включает
Kotlin parser; приложения и их зависимости компилировать не требуется.

```bash
navigation-flow-diagram /path/to/kotlin-project \
  --format mermaid --output navigation.mmd

navigation-flow-diagram /path/to/kotlin-project \
  --format dot --output navigation.dot

navigation-flow-diagram /path/to/kotlin-project \
  --format json --output navigation.json
```

Без `--output` результат выводится в stdout, диагностические сообщения — в stderr.
JSON содержит полные имена типов, привязки View, операции, позиции в файлах и диагностику.
Пути CLI относительны корню проекта, строки и столбцы начинаются с 1, смещения — с 0.
Mermaid можно открыть в совместимом Markdown-просмотрщике; DOT — средствами Graphviz.

По умолчанию исключены тестовые source sets, скрытые каталоги, `build`, `out`, `node_modules`,
`vendor` и символьные ссылки. Флаг `--include-tests` включает тестовые исходники.
Порядок вывода стабилен. Ошибки аргументов возвращают код 2, ошибки чтения/записи — 1.

## Что анализируется

- Связь View с конфигурацией через generic-типы `ComposeView` / `NavigationNode` и фабрики
  `NavigationNodeFactory.Typed`; наследники конфигураций учитываются отдельно.
- `push`, `replace`, `reversedReplace`, `createSubChain`, операции над поддеревом,
  Compose-инъекции и распознанные в исходниках обёртки.
- Источник перехода из типа View или параметра `NavigationNode<SourceConfig, Base>`
  в обработчике интерактора, включая вызовы через другую цепочку.
- Явные типы, доступные объявления, import aliases, type aliases и прямое перенаправление
  параметров конфигурации в навигационные вызовы.

Это **статический анализ Kotlin PSI**, а не исполнение приложения или полный анализ Kotlin с
classpath каждого модуля. Граф показывает возможные связи во всех включённых source sets;
условия выполнения, порядок фабрик и выбор DI-контейнера не вычисляются. Произвольные провайдеры,
сложный поток данных, рефлексия и неоднозначные типы могут остаться неразрешёнными.
Обычные методы с именами `push` / `replace` на посторонних типах не должны считаться навигацией.

## Сборка и проверка

Плагин включён в общую Gradle-сборку. Все команды выполняются из корня репозитория.
Нужны JDK 25 для плагина и JDK 17 для анализатора/CLI; общие настройки описаны
в [корневом README](../README.md).

```bash
./gradlew :navigation-flow-diagram:analyzer:test :navigation-flow-diagram:cli:test :navigation-flow-diagram:idea-plugin:check
./gradlew :navigation-flow-diagram:idea-plugin:buildPlugin :navigation-flow-diagram:cli:installDist
./gradlew :navigation-flow-diagram:idea-plugin:runIde -PlocalIdePath=/path/to/intellij-idea
```

ZIP: `navigation-flow-diagram/idea-plugin/build/distributions/navigation-flow-diagram-0.1.2.zip`.
Общий `./build-plugins.sh` также копирует его в `local.dists/`.
CLI: `navigation-flow-diagram/cli/build/install/navigation-flow-diagram/bin/navigation-flow-diagram`.
Задача `:navigation-flow-diagram:cli:distZip` собирает переносимый архив CLI отдельно от ZIP плагинов.

Анализатор и CLI используют standalone-компилятор Kotlin из общего каталога версий,
независимо от bundled Kotlin в IDEA. Необязательный `-PkotlinCompilerHome=/path/to/kotlinc`
позволяет использовать локальный компилятор. Компилятор не включается в ZIP плагина:
там используются PSI-классы bundled Kotlin. `localIdePath` выбирает общий SDK для всех плагинов.

## Устройство проекта

- `analyzer/` — Kotlin PSI-анализ, модель графа и общий экспорт Mermaid/DOT/JSON.
- `cli/` — чтение дерева исходников и запуск анализатора в standalone Kotlin parser.
- `idea-plugin/` — фоновый анализ проекта, gutter-маркеры, переходы и окно диаграммы.

Тесты JUnit проверяют связи и исключение ложных совпадений, экспорт и обход каталогов.
IDE fixtures проверяют настоящие PSI-листья, маркеры, обновления после правок и удаления файлов.
Исходные API: [IntelliJ line markers](https://plugins.jetbrains.com/docs/intellij/line-marker-provider.html)
и [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html).
