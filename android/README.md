# Android-стенд «ГолосЖест»

Портирование VOSK real-time UI на Android (Kotlin).

## Состав

```
android/
├── build.gradle.kts              # корневой gradle
├── app/
│   ├── build.gradle.kts          # зависимости: vosk-android 0.3.47
│   └── src/main/
│       ├── AndroidManifest.xml   # RECORD_AUDIO permission
│       ├── assets/
│       │   └── grammar.json      # 31 фраза constrained grammar
│       ├── java/com/ccmnp/voicecontrol/
│       │   └── MainActivity.kt   # вся логика в одном файле
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/strings.xml
│           └── values/themes.xml
```

## Сборка

1. Открыть `android/` в Android Studio (Arctic Fox+)
2. Дождаться Gradle sync
3. **Скачать модель** и положить рядом с `app/build.gradle.kts`:

```bash
curl -L https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip \
     -o app/vosk-model-small-ru-0.22.zip
unzip app/vosk-model-small-ru-0.22.zip -d app/
mv app/vosk-model-small-ru-0.22 app/model-ru
```

4. Запустить на устройстве (minSdk 26, ARM64)

> **Примечание:** VOSK Android SDK (`StorageService.unpack`) берёт модель из
> `app/src/main/assets/` или из zip рядом с apk. Путь `app/model-ru/`
> должен совпадать с `StorageService.unpack(this, "model-ru", ...)`.

## Архитектура (как в Python-прототипе)

| Компонент | Android-аналог |
|---|---|
| `pyaudio` + поток | `AudioRecord` + VOSK `SpeechService` |
| `webrtcvad` | встроенный VAD в VOSK `Recognizer` |
| `Kivy GUI` | `activity_main.xml` + `TextView` |
| `SequenceMatcher` | `similarity()` — LCS вручную |
| Fuzzy match 0.85 | `MATCH_THRESHOLD = 0.85` |
| Wake word «протез» | `onPartialResult` → поиск подстроки |

## Состояния

```
LOADING → LISTENING → CAPTURING → PROCESSING → LISTENING
         ↑  wake word  ↑ timeout  ↑ command
                         или silence  executed
```

## Команды

```
протез жест <payload> выполняй    — смена жеста
протез статус выполняй            — запрос состояния
```

## Задачи стажёра

1. **Запустить стенд** на физическом Android-устройстве
2. **Замерить latency** end-to-end: wake word → ответ в UI
3. **Сравнить** с Python-прототипом (320ms offline, ~500ms real-time)
4. **Добавить foreground service** для фоновой работы (Android 8+)
5. **Modbus-пакет**: после `execute()` — отправить команду через Bluetooth/BLE

## Известные ограничения

- `vosk-model-small-ru-0.22` — constrained grammar невозможен (доменные слова вне словаря). Используется свободное распознавание + fuzzy match.
- Для constrained grammar нужна кастомная small-модель, собранная через Kaldi-рецепт с кастомным словарём.
- Android VOSK API использует `SpeechService` — stream-based, не batch. Это ближе к real-time, но `onPartialResult` дёргается каждые 0.2–0.5s.
