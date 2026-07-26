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
# prosthesis-voice-interface

Голосовой интерфейс для управления протезом с использованием Vosk и faster-whisper.

## 📁 Структура проекта
samples/
├── clean/ # Чистые записи (без шума)
│ ├── commands/ (90 WAV) # Команды управления жестами
│ ├── status/ (10 WAV) # Команды статуса
│ └── edge/ (20 WAV) # Краевые случаи (неоднозначные команды)
├── negative/ # Негативные примеры (не команды)
│ ├── oov/ (20 WAV) # Слова вне словаря
│ ├── malformed/ (20 WAV) # Искажённые команды
│ ├── foreign/ (10 WAV) # Команды на иностранных языках
│ └── nonspeech/ (30 WAV) # Не-речевые звуки
├── noisy/ # Записи с шумом
│ ├── office/ (270 WAV) # Офисный шум (SNR: 0-20 дБ)
│ ├── street/ (270 WAV) # Уличный шум (SNR: 0-20 дБ)
│ ├── machinery/ (180 WAV) # Производственный шум (SNR: 0-20 дБ)
│ └── reverb/ (90 WAV) # Реверберация (помещения)
├── manifest.json # Метаданные всех семплов
├── add_noise.py # Скрипт для наложения шума
└── README.md # Этот файл

text

## 🎯 Назначение папок

| Папка | Описание | Количество |
|-------|----------|------------|
| `clean/commands/` | Чистые команды управления жестами | 90 |
| `clean/status/` | Чистые команды статуса | 10 |
| `clean/edge/` | Краевые случаи (неоднозначные команды) | 20 |
| `negative/oov/` | Слова вне словаря (out-of-vocabulary) | 20 |
| `negative/malformed/` | Искажённые или неполные команды | 20 |
| `negative/foreign/` | Команды на иностранных языках | 10 |
| `negative/nonspeech/` | Не-речевые звуки | 30 |
| `noisy/office/` | Команды с офисным шумом | 270 |
| `noisy/street/` | Команды с уличным шумом | 270 |
| `noisy/machinery/` | Команды с производственным шумом | 180 |
| `noisy/reverb/` | Команды с реверберацией | 90 |

## 🔧 Установка

### 1. Клонировать репозиторий

```bash
git clone https://github.com/Pavelr0410/prosthesis-voice-interface.git
cd prosthesis-voice-interface
2. Установить зависимости
bash
pip install -r requirements.txt
Или вручную:

bash
pip install vosk wave numpy soundfile tqdm pyroomacoustics

bash
python add_noise.py
Настройки шума находятся в начале файла add_noise.py:

python
SNR_DB = [5, 10, 15]  # Уровни SNR
NOISE_FOLDER = "musan/noise/sound-bible"  # Папка с шумами
Тестирование Vosk
bash
python vosk_tester.py
📊 Формат manifest.json
json
{
  "samples": [
    {
      "path": "clean/commands/kulak.wav",
      "text": "протез жест кулак выполняй",
      "expected_command": "жест",
      "expected_payload": "кулак",
      "category": "clean",
      "subcategory": "commands",
      "snr_db": null,
      "speaker": "pavel",
      "duration_ms": 1200,
      "valid": true,
      "test_ids": ["V-СИ-01", "V-ОК-01"]
    }
  ]
}
Поля manifest.json
Поле	Описание
path	Путь относительно samples/
text	Эталонный текст команды
expected_command	Ожидаемая команда (жест, статус, null)
expected_payload	Ожидаемый жест (кулак, нейтральный, null)
category	Категория (clean, negative, noisy)
subcategory	Подкатегория (commands, office, street, ...)
snr_db	Уровень SNR (для noisy), иначе null
speaker	Имя диктора
duration_ms	Длительность в миллисекундах
valid	true — валидная команда, false — негативный пример
test_ids	Список тестовых идентификаторов
🧪 Тестовые идентификаторы
ID	Описание
V-СИ-01	Чистая команда (системный тест)
V-ОК-01	Валидная команда (общий тест)
V-НЕ-01	Негативный пример
W-AC-01	Шумная команда (акустический тест)
W-PR-01	Шумная или реверберирующая команда (сложный тест)