# prosthesis-voice-interface
Prothesis voice interface

## Установка модели Vosk

Скачайте модель и распакуйте в папку `model-ru/`:

```bash
# Маленькая модель (45 МБ) — быстрая
wget https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip
unzip vosk-model-small-ru-0.22.zip
mv vosk-model-small-ru-0.22 model-ru

# ИЛИ большая модель (1.8 ГБ) — более точная
wget https://alphacephei.com/vosk/models/vosk-model-ru-0.42.zip
unzip vosk-model-ru-0.42.zip
mv vosk-model-ru-0.42 model-ru
