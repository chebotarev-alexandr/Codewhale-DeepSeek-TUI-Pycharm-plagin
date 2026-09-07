# Codewhale PyCharm Plugin

Минимальный плагин для PyCharm (Community), который связывает IDE с локально
запущенным агентом [Codewhale](https://github.com/Hmbown/CodeWhale) (ранее —
DeepSeek-TUI).

Идея — как Claude Code в IDE: ты открываешь файл (или выделяешь фрагмент),
пишешь запрос в панели, а открытый файл/выделение автоматически уходит
агенту как контекст. При этом агент работает в workspace проекта и имеет
доступ ко всем файлам, а не только к открытому.

## Важно: DeepSeek-TUI переименован в Codewhale

Проект тот же (автор Hmbown), но с версии v0.8.41 он называется `codewhale`,
а старое имя `deepseek-tui` устарело. Подробности миграции:
[docs/REBRAND.md](https://github.com/Hmbown/CodeWhale/blob/main/docs/REBRAND.md).

- Бинарь `deepseek` → теперь `codewhale` (короткий `codew`)
- Конфиг `~/.deepseek/` → `~/.codewhale/` (старое читается как fallback)
- **Модели и API DeepSeek не изменились**: `DEEPSEEK_API_KEY`,
  `deepseek-v4-pro` и т.д. работают как раньше
- Runtime API тот же, что и раньше: `app-server --http` на `127.0.0.1:7878`

## Как это работает

```
PyCharm (панель Codewhale)
        │  HTTP + SSE (localhost:7878)
        ▼
codewhale app-server --http   ← запускается плагином (или вручную)
        │
        ▼
DeepSeek V4 (через твой API-ключ)
```

Плагин НЕ запускает модель сам и НЕ хранит ключ. Он лишь:
1. Читает открытый файл / выделение в редакторе
2. Складывает их с твоим текстом в один запрос
3. Шлёт его в Runtime API Codewhale и стримит ответ обратно в панель

Сервер (`codewhale app-server --http --insecure-no-auth`) плагин умеет поднимать
сам: сверху панели есть строка статуса и кнопка Start/Stop. При отправке первого
запроса сервер стартует автоматически, если ещё не запущен; при закрытии проекта
плагин останавливает только тот процесс, который запустил сам (внешний не трогает).

## Требования

- **PyCharm Community 2024.1+** (собиралось и тестировалось на 2024.1.4)
- **JDK 17** (нужен только для сборки плагина)
- **Codewhale** установлен и авторизован (см. ниже)

## Установка Codewhale (macOS)

```bash
# Рекомендованный путь — официальный установщик
curl -fsSL https://codewhale.net/install.sh | sh
"$HOME/.local/bin/codewhale" --version

# Или через Homebrew
brew tap Hmbown/deepseek-tui
brew install codewhale
```

Авторизация (твой ключ DeepSeek API):

```bash
codewhale auth set --provider deepseek --api-key "sk-..."
```

> **Альтернативные провайдеры.** Codewhale умеет и другие бэкенды (NVIDIA NIM,
> Fireworks AI, Alibaba Model Studio, self-hosted SGLang и др.). Если
> используешь прокси или OpenRouter — смотри `docs/CONFIGURATION.md`.
> Плагину это безразлично: он ходит только на локальный `localhost:7878`.

## Запуск сервера Runtime API

Перед работой с плагином подними сервер (в отдельном терминале или фоном):

```bash
codewhale app-server --http --insecure-no-auth
```

`--insecure-no-auth` отключает требование токена — безопасно для loopback
(сервер слушает только `127.0.0.1`). Если хочешь с токеном — используй
`--auth-token` / `CODEWHALE_RUNTIME_TOKEN`, но тогда плагину тоже нужен будет
токен (в MVP он не поддерживается).

Проверка, что сервер жив:

```bash
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
# JDK 17 + Gradle 8.x (или использовать gradlew из репозитория)
./gradlew buildPlugin
```

Результат: `build/distributions/codewhale-pycharm-<версия>.zip`

### Установка zip в PyCharm

1. PyCharm → Settings → Plugins → ⚙️ → **Install Plugin from Disk…**
2. Выбери `build/distributions/codewhale-pycharm-0.1.0.zip`
3. Перезапусти PyCharm

## Использование

1. Запусти `codewhale app-server --http --insecure-no-auth` (отдельный терминал).
2. В PyCharm открой файл проекта, с которым хочешь работать
   (или выдели нужный фрагмент).
3. Справа открой панель **Codewhale** (View → Tool Windows → Codewhale).
4. Введи запрос, оставь галочку **«Attach open file / selection»** включённой.
5. Жми **Send** (или Enter в поле ввода).

Ответ стримится в панель. Повторные запросы идут в ту же сессию (thread) —
контекст разговора сохраняется, как в чате.

## Что уже умеет (MVP)

- Панель инструментов «Codewhale» справа
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
- Поддержка auth-токена Runtime API

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
| «Codewhale server not running» | Запусти `codewhale app-server --http --insecure-no-auth`, проверь `curl http://127.0.0.1:7878/health` |
| Плагин собрался, но панели нет | PyCharm < 2024.1 или неправильный JDK при сборке |
| Ответ не стримится / обрывается | Проверь логи `app-server`, что ключ валиден и есть баланс |
| 401/403 при запросах | Сервер запущен с требованием токена — перезапусти с `--insecure-no-auth` |
| Ошибка при `gradle build` про `com.intellij.java` | Не менять `type.set("PC")`; плагин заточен под PyCharm Community |

## Лицензия

MIT. Основан на открытом Runtime API Codewhale.
Плагин не аффилирован с Codewhale/Shannon Labs и с DeepSeek Inc.
