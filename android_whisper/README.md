# Android-стенд «ГолосЖест» (Whisper-tiny)

Портирование Android-приложения голосового управления с **Vosk на Whisper-tiny**
(whisper.cpp через JitPack). Всё остальное — интерфейс, BLE-управление протезом,
грамматика — то же, что в `android/`.

## Состав

```
android_whisper/
├── build.gradle.kts              # корневой gradle
├── app/
│   ├── build.gradle.kts          # зависимости: whisper.cpp (whisper-android) + JNA
│   └── src/main/
│       ├── AndroidManifest.xml   # RECORD_AUDIO + Bluetooth permissions
│       ├── assets/
│       │   ├── grammar.json      # фразы команд
│       │   └── ggml-tiny.bin     # модель Whisper-tiny (скачивается, в git не входит)
│       ├── java/com/ccmnp/voskbench/
│       │   ├── MainActivity.kt
│       │   ├── BluetoothModbusManager.kt
│       │   ├── WhitelistCorrector.kt
│       │   └── services/VoiceRecognitionService.kt
│       └── res/
```

## Сборка

1. Открыть `android_whisper/` в Android Studio.
2. Убедиться, что установлены **NDK** и **CMake** (SDK Manager → SDK Tools).
3. **Склонировать исходники whisper.cpp** (нужны для нативной сборки, в git не входят):
   ```bash
   git clone --depth 1 --branch v1.7.4 https://github.com/ggerganov/whisper.cpp.git \
       app/src/main/jni/whisper.cpp
   ```
4. **Скачать модель Whisper-tiny (квантизированная, быстрая)** в `app/src/main/assets/`:
   ```bash
   curl -L https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin \
        -o app/src/main/assets/ggml-tiny-q5_1.bin
   ```
5. Gradle sync (CMake соберёт `libwhisper.so` через JNI) и запуск на устройстве (minSdk 26).

> Модель `ggml-tiny.bin` (~77 МБ) и исходники `whisper.cpp/` исключены из git
> (см. `.gitignore`). Загрузка модели — прямо из assets через JNI
> (`initContextFromAsset`), копия в хранилище не нужна.

## Архитектура

| Python-прототип | Android-аналог |
|---|---|
| `pyaudio` + поток | `AudioRecord` (16 кГц, моно) + фоновый поток |
| VAD (энергия/webrtcvad) | RMS-порог `SPEECH_THRESHOLD` + тишина `SILENCE_MS` |
| faster-whisper `transcribe` | `WhisperCpp.fullTranscribeWithTime` (whisper.cpp) |
| `SequenceMatcher` | `similarity()` — LCS вручную |
| Fuzzy match 0.85 | `MATCH_THRESHOLD = 0.85` |
| Wake word «протез» | проверка в транскрипции фразы |

## Состояния

```
LOADING → LISTENING → CAPTURING → PROCESSING → LISTENING
         ↑  речь (VAD)  ↑ тишина/  ↑ транскрипция
                          таймаут   Whisper
```

Whisper — батчевая модель: при обнаружении речи захватывается фраза до тишины,
затем транскрибируется целиком. Команда выполняется, если в тексте есть
«протез» + жест.

## Команды

```
протез жест <payload> выполняй    — смена жеста
протез статус выполняй            — запрос состояния
```

## BLE-управление протезом

- Подключение: кнопка «Подключить Bluetooth» (toggle) — прямое GATT-подключение
  к сохранённому адресу или автоскан FEST.
- Команды пишутся в характеристику `4368000a` (MOVE_ALL_FINGERS):
  6 байт позиций `[pinky, ring, middle, index, thumb, thumb_rot]`.

## Известные особенности

- Whisper-tiny на устройстве медленнее стримингового Vosk: транскрипция фразы
  занимает заметное время (батч), но точнее распознаёт команды.
- Если фраза рвётся на паузах — уменьшите `SILENCE_MS`/увеличьте `SPEECH_THRESHOLD`
  в `MainActivity.kt`.
