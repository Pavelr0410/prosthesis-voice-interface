import json
import time
import wave
import vosk
import os
from difflib import SequenceMatcher

# ═══════════════════════════════════════════════════════════════
# КОНФИГУРАЦИЯ (макропараметры)
# ═══════════════════════════════════════════════════════════════

# Загрузка грамматики из JSON файла
with open("grammar.json", "r", encoding="utf-8") as f:
    grammar = json.load(f)

MODEL_PATH = "model-ru"
TEST_FOLDER = "test_audio"
THRESHOLD = 0.7
SAMPLE_RATE = 16000

result_list = []
true_count = 0
missing_count_total = 0

correct_list = {                                                                  # Список правильных фраз, для проверки в конце
    "bolshoi_palez.wav": "протез жест большой палец выполняй",
    "bolshoi_palez2.wav": "протез жест большой палец выполняйте",
    "extensia.wav": "протез жест экстензия выполняй",
    "flexija.wav": "протез жест флексия выполняй",
    "kulak.wav": "протез жест кулак выполняйте",
    "kulak2.wav": "протез жест кулак выполняйте",
    "kulak3.wav": "протез жест кулак выполняй",
    "neitralnyi.wav": "протез жест нейтральный выполняй",
    "otkryt.wav": "протез жест открыть выполняй",
    "shipok.wav": "протез жест щипок выполняй",
    "status1.wav": "протез статус выполняй",
    "status2.wav": "протез статус выполняй",
    "status3.wav": "протез статус выполняй",
    "szhatye_palzev.wav": "протез жест сжатие пальцев выполняй",
    "ukazatelniy.wav": "протез жест указательный выполняй"
}

def similar(a, b):
    A = a.lower().strip()                                                          # распознанный текст VOSK (без лишних пробелов)
    B = b.lower().strip()                                                          # команда из grammar (без лишних пробелов)
    return SequenceMatcher(None, A, B).ratio()                                     # сходство (от 0% до 100%)

def find_best_match(text, grammar_list):
    if not text:
        return None, 0.0                                  # если нету опознанной речи
    best_match = None
    best_score = 0.0
    for command in grammar_list:
        score = similar(text, command)                    # сходство (от 0% до 100%) для данного command
        if score > best_score:
            best_score = score
            best_match = command
    return best_match, best_score                         # пишет команду с лучшим совпадением

def analyze_word_coverage(text, grammar_list):            # Анализирует, какие слова из транскрипции вне грамматики
    if not text:
        return 0, 0, []                                   # если нету опознанной речи
    
    raw_words = text.lower().split()                      # list со всеми опознанными словами
    
    grammar_words = set()                                 # list со всеми правильно опознанными словами
    for command in grammar_list:
        for word in command.lower().split():
            grammar_words.add(word)
    
    missing_words = []
    for word in raw_words:
        clean_word = ''.join(c for c in word if c.isalpha() or c == '-')       # очищает слово от всех символов, кроме букв и дефиса
        if clean_word and clean_word not in grammar_words:
            missing_words.append(clean_word)
    
    total_words = len(raw_words)
    missing_count = len(missing_words)
    
    return total_words, missing_count, missing_words

def transcribe_file(file_path, recognizer):
    start_time = time.time()
    try:
        wf = wave.open(file_path, "rb")
        while True:
            data = wf.readframes(8000)                    # читает 8000 фреймов (пол секунды для 16кГц) аудиозаписи
            if len(data) == 0:
                break
            recognizer.AcceptWaveform(data)               # отправляет кусочек аудио "data" в распознаватель Vosk для обработки. Тот пишет True, если считает, что была произношена законченная фраза, и False, если ожидает ещё аудио
        result = json.loads(recognizer.FinalResult())     # Финальная транскрипция Vosk в формате JSON переводится в словарь Python (путем json.loads)
        text = result.get("text", "")                     # Извлекает из словаря распознанный текст. Если текст не был распознан, возвращает пустую строку ""
        wf.close()
    except Exception as e:
        return "", time.time() - start_time
    elapsed_ms = (time.time() - start_time) * 1000
    return text, elapsed_ms

def main():
    global true_count, missing_count_total
    print(f"Loading model: {MODEL_PATH}")
    try:
        model = vosk.Model(MODEL_PATH)
        print("✅ Model loaded")
    except Exception as e:
        print(f"Error: {e}")
        return
    
    print(f"Loaded {len(grammar)} grammar entries from grammar.json")
    
    if not os.path.exists(TEST_FOLDER):
        print(f"Folder '{TEST_FOLDER}' not found!")
        return
    
    wav_files = [f for f in os.listdir(TEST_FOLDER) if f.lower().endswith('.wav')]
    if not wav_files:
        print(f"No WAV files found in '{TEST_FOLDER}'")
        return
    
    print(f"Found {len(wav_files)} files")
    print(f"Grammar has {len(grammar)} entries")
    
    total_time = 0
    result_list = []
    # Process each file
    for filename in sorted(wav_files):
        file_path = os.path.join(TEST_FOLDER, filename)
        
        print(f"\n{filename}")
        
        rec = vosk.KaldiRecognizer(model, SAMPLE_RATE)
        
        # Транскрипция
        recognized, elapsed_ms = transcribe_file(file_path, rec)
        total_time += elapsed_ms
        
        # Поиск лучшега совпадения
        best_match, best_score = find_best_match(recognized, grammar)
        
        # Поиск ошибок
        total_words, missing_count, missing_words = analyze_word_coverage(recognized, grammar)
        
        print(f"{elapsed_ms:.1f} ms")
        print(f"Raw: '{recognized}'")
        print(f"Words: {total_words} total, {missing_count} not in grammar")
        missing_count_total += missing_count
        if missing_words:
            print(f"   Missing words: {', '.join(missing_words)}")
        
        if best_match:
            if correct_list[filename] == best_match:
                true_count += 1
                result_list.append(f" ✅ {filename}: {best_match} ({best_score:.1%}) | Кол. ошибок: {missing_count} из {total_words} слов")
            else:
                result_list.append(f" ❌ {filename}: {best_match} ({best_score:.1%}) | Кол. ошибок: {missing_count} из {total_words} слов")
            if best_score >= THRESHOLD and correct_list[filename] == best_match:
                print(f" ✅  Best match ({best_score:.1%}): '{best_match}'")
            else:
                print(f" ⚠️  Best match ({best_score:.1%}): '{best_match}'")
        else:
            result_list.append(f" ❌ {filename}: не опознанно | Кол. ошибок: {missing_count} из {total_words} слов")
            print(f" ❌  No match found")
    
    # Сводка
    print("\n" + "=" * 60)
    print(f"SUMMARY")
    print("=" * 60)
    print(f"Обработанные файлы: {len(wav_files)}")
    print(f"Общее время: {total_time:.1f} ms")
    print(f"Средняя задержка: {total_time/len(wav_files):.1f} ms")
    print(f"\nРезультаты:")
    for result in result_list:
        print(result)
    print(f"Кол. правильных: {true_count}/{len(wav_files)}")
    print(f"Кол. неправильных слов: {missing_count_total}")
    print("\n" + "=" * 60)

if __name__ == "__main__":
    main()