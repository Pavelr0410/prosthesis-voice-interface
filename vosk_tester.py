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
CONFIDENCE_THRESHOLD = 0.6                                                        # Порог уверенности
SAMPLE_RATE = 16000

result_list = []
true_count = 0
missing_count_total = 0

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

def get_confidence_from_result(result_json):                                   # Извлекает среднюю уверенность из результата Vosk
    try:
        if "result" in result_json and result_json["result"]:
            confidences = []
            for word_info in result_json["result"]:
                if "conf" in word_info:
                    confidences.append(word_info["conf"])                      # Составляет список с значениями conf каждого распознанного слова в result_json
            if confidences:
                return sum(confidences) / len(confidences)                     # Вычисляет среднее значение conf для всего предложения
    except:
        pass
    return 0.0

def transcribe_file(file_path, recognizer):
    start_time = time.time()
    confidence = 0.0
    try:
        wf = wave.open(file_path, "rb")
        while True:
            data = wf.readframes(8000)                    # читает 8000 фреймов (пол секунды для 16кГц) аудиозаписи
            if len(data) == 0:
                break
            recognizer.AcceptWaveform(data)               # отправляет кусочек аудио "data" в распознаватель Vosk для обработки
        result = json.loads(recognizer.FinalResult())     # Финальная транскрипция Vosk в формате JSON переводится в словарь Python
        text = result.get("text", "")                     # Извлекает из словаря распознанный текст
        confidence = get_confidence_from_result(result)   # Получаем уверенность распознавания
        wf.close()
    except Exception as e:
        return "", time.time() - start_time, 0.0
    elapsed_ms = (time.time() - start_time) * 1000
    return text, elapsed_ms, confidence

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
    print(f"Confidence threshold: {CONFIDENCE_THRESHOLD}")
    
    total_time = 0
    result_list = []
    # Process each file
    for filename in sorted(wav_files):
        file_path = os.path.join(TEST_FOLDER, filename)
        
        print(f"\n{filename}")
        
        rec = vosk.KaldiRecognizer(model, SAMPLE_RATE)
        rec.SetWords(True)                                   # Включаем вывод информации о словах (включая уверенность)
        
        # Транскрипция
        recognized, elapsed_ms, confidence = transcribe_file(file_path, rec)
        total_time += elapsed_ms
        
        # Проверяем порог уверенности
        if confidence < CONFIDENCE_THRESHOLD:
            print(f"⚠️  Low confidence: {confidence:.2%} < {CONFIDENCE_THRESHOLD:.2%}")
            result_list.append(f" ⚠️ {filename}: низкая уверенность ({confidence:.1%}) | результат: '{recognized}'")
            print(f"   Raw: '{recognized}'")
            continue
        
        # Поиск лучшего совпадения
        best_match, best_score = find_best_match(recognized, grammar)
        
        # Поиск ошибок
        total_words, missing_count, missing_words = analyze_word_coverage(recognized, grammar)
        
        print(f"Confidence: {confidence:.2%}")
        print(f"{elapsed_ms:.1f} ms")
        print(f"Raw: '{recognized}'")
        print(f"Words: {total_words} total, {missing_count} not in grammar")
        missing_count_total += missing_count
        if missing_words:
            print(f"   Missing words: {', '.join(missing_words)}")
        
        if best_match:
            is_correct = (confidence >= CONFIDENCE_THRESHOLD)                       # Проверяем, что уверенность достаточна
            if is_correct and best_score>=THRESHOLD:
                true_count += 1
                print(f" ✅  Best match ({best_score:.1%}): '{best_match}'")
                result_list.append(f" ✅ {filename}: {best_match} ({best_score:.1%}) | Уверенность: {confidence:.1%} | Ошибок: {missing_count}/{total_words}")
            else:
                print(f" ❌  Best match ({best_score:.1%}): '{best_match}'")
                result_list.append(f" ❌ {filename}: {best_match} ({best_score:.1%}) | Уверенность: {confidence:.1%} | Ошибок: {missing_count}/{total_words}")
        else:
            result_list.append(f" ❌ {filename}: не распознано | Уверенность: {confidence:.1%} | Ошибок: {missing_count}/{total_words}")
            print(f" ❌  No match found")
    
    # Сводка
    print("\n" + "=" * 60)
    print(f"SUMMARY")
    print("=" * 60)
    print(f"Обработанные файлы: {len(wav_files)}")
    print(f"Общее время: {total_time:.1f} ms")
    print(f"Средняя задержка: {total_time/len(wav_files):.1f} ms")
    print(f"Порог уверенности: {CONFIDENCE_THRESHOLD:.1%}")
    print(f"\nРезультаты:")
    for result in result_list:
        print(result)
    print(f"Кол. правильных: {true_count}/{len(wav_files)}")
    print(f"Кол. неправильных слов: {missing_count_total}")
    print("\n" + "=" * 60)

if __name__ == "__main__":
    main()