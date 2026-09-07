# DeepSeek-TUI PyCharm Plugin

Минимальный плагин для PyCharm (Community), который связывает IDE с локально
запущенным агентом [DeepSeek-TUI](https://github.com/Hmbown/DeepSeek-TUI).

Идея — как Claude Code в IDE: ты открываешь файл (или выделяешь фрагмент),
пишешь запрос в панели, а открытый файл/выделение автоматически уходит
агенту как контекст. При этом агент работает в workspace проекта и имеет
доступ ко всем файлам, а не только к открытому.

## Как это работает

```
PyCharm (панель DeepSeek)
        │  HTTP + SSE (localhost:7878)
        ▼
deepseek serve --http   ← сам DeepSeek-TUI, запущенный отдельно
        │
        ▼
DeepSeek V4 (через твой API-ключ)
```

Плагин НЕ запускает модель сам и НЕ хранит ключ. Он лишь:
1. Читает открытый файл / выделение в редакторе
2. Складывает их с твоим текстом в один запрос
3. Шлёт его в Runtime API DeepSeek-TUI и стримит ответ обратно в панель

## Требования

- **PyCharm Community 2024.1+** (собиралось и тестировалось на 2024.1.4)
- **JDK 17** (нужен только для сборки плагина)
- **DeepSeek-TUI** установлен и авторизован (см. ниже)

## Установка DeepSeek-TUI (macOS)

```bash
brew tap Hmbown/deepseek-tui
brew install deepseek-tui

# авторизация (твой ключ DeepSeek API)
deepseek auth set --provider deepseek --api-key "sk-..."

# одноразовая инициализация (создаёт папки MCP/skills)
deepseek-tui setup
```

> **Альтернативные провайдеры.** DeepSeek-TUI умеет и другие бэкенды:
> NVIDIA NIM, Fireworks AI, self-hosted SGLang. Если используешь прокси или
> OpenRouter — смотри `docs/CONFIGURATION.md` в репозитории DeepSeek-TUI.
> Плагину это безразлично: он ходит только на локальный `localhost:7878`.

## Запуск сервера Runtime API

Перед работой с плагином подними сервер (в отдельном терминале или фоном):

```bash
deepseek serve --http          # слушает 127.0.0.1:7878 по умолчанию
```

Проверка, что он жив:

```bash
deepseek doctor --json         # health-проверка установки
curl http://127.0.0.1:7878/health
```

## Сборка плагина

### Способ 1 — из PyCharm (рекомендуется)

1. Открой этот репозиторий в PyCharm как **Gradle-проект**
   (File → Open → выбрать папку, где лежит `build.gradle.kts`).
2. Подожди, пока Gradle синхронизируется (первый раз скачает IntelliJ SDK,
   это долго — сотни мегабайт).
3. В правой панели Gradle открой `Tasks → intellij → runIde` и запусти.
   Откроется PyCharm с установленным плагином (песочница).

### Способ 2 — из терминала

```bash
# требуется JDK 17 и Gradle 8.x
gradle buildPlugin
```

Результат: `build/distributions/deepseek-tui-pycharm-<версия>.zip`

### Установка zip в PyCharm

1. PyCharm → Settings → Plugins → ⚙️ → **Install Plugin from Disk…**
2. Выбери `build/distributions/deepseek-tui-pycharm-0.1.0.zip`
3. Перезапусти PyCharm

## Использование

1. Запусти `deepseek serve --http` (отдельный терминал).
2. В PyCharm открой файл проекта, с которым хочешь работать
   (или выдели нужный фрагмент).
3. Справа открой панель **DeepSeek** (View → Tool Windows → DeepSeek).
4. Введи запрос, оставь галочку **«Attach open file / selection»** включённой.
5. Жми **Send** (или Enter в поле ввода).

Ответ стримится в панель. Повторные запросы идут в ту же сессию (thread) —
контекст разговора сохраняется, как в чате.

## Что уже умеет (MVP)

- Панель инструментов «DeepSeek» справа
- Отправка запроса + открытый файл / выделение как контекст
- Стриминг ответа по мере генерации
- Сквозной thread (история разговора в пределах открытого окна)
- Проверка, что сервер поднят (иначе — подсказка, как запустить)

## Чего пока нет (планируется)

- Кнопка «новый разговор» (сброс thread)
- Показ diff изменений, которые делает агент
- UI подтверждения действий (approval gates)
- Настройки хоста/порта/модели через Settings
- Выбор файлов из дерева проекта вручную

## Структура проекта

```
src/main/
├── kotlin/com/chebotarev/deepseekplugin/
│   ├── DeepSeekToolWindowFactory.kt   # регистрация панели в IDE
│   ├── DeepSeekPanel.kt               # UI: поле ввода, кнопка, вывод
│   ├── DeepSeekClient.kt              # HTTP/SSE-клиент к Runtime API
│   └── EditorContextProvider.kt       # чтение открытого файла/выделения
└── resources/META-INF/
    └── plugin.xml                     # дескриптор плагина
```

## Устранение проблем

| Симптом | Причина / решение |
|---|---|
| «DeepSeek-TUI server not running» | Запусти `deepseek serve --http`, проверь `curl http://127.0.0.1:7878/health` |
| Плагин собрался, но панели нет | PyCharm < 2024.1 или неправильный JDK при сборке |
| Ответ не стримится / обрывается | Проверь логи `deepseek serve --http`, что ключ валиден и есть баланс |
| Ошибка при `gradle build` про `com.intellij.java` | Не менять `type.set("PC")` на другой; плагин заточен под PyCharm Community |

## Лицензия

MIT. Основан на открытом Runtime API DeepSeek-TUI.
Плагин не аффилирован с DeepSeek Inc. и с проектом DeepSeek-TUI.
