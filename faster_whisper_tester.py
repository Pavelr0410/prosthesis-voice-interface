import json
import time
import os
from difflib import SequenceMatcher
from faster_whisper import WhisperModel

# ═══════════════════════════════════════════════════════════════
# КОНФИГУРАЦИЯ (макропараметры)
# ═══════════════════════════════════════════════════════════════

# Загрузка грамматики из JSON файла
with open("grammar.json", "r", encoding="utf-8") as f:
    grammar = json.load(f)

TEST_FOLDER = "test_audio"
THRESHOLD = 0.7

# Whisper settings
MODEL_SIZE = "small"      # "tiny", "base", "small", "medium", "large"
DEVICE = "cpu"
COMPUTE_TYPE = "int8"

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
    A = a.lower().strip()
    B = b.lower().strip()
    return SequenceMatcher(None, A, B).ratio()

def find_best_match(text, grammar_list):
    if not text:
        return None, 0.0
    best_match = None
    best_score = 0.0
    for command in grammar_list:
        score = similar(text, command)
        if score > best_score:
            best_score = score
            best_match = command
    return best_match, best_score

def analyze_word_coverage(text, grammar_list):
    if not text:
        return 0, 0, []
    
    raw_words = text.lower().split()
    
    grammar_words = set()
    for command in grammar_list:
        for word in command.lower().split():
            grammar_words.add(word)
    
    missing_words = []
    for word in raw_words:
        clean_word = ''.join(c for c in word if c.isalpha() or c == '-')
        if clean_word and clean_word not in grammar_words:
            missing_words.append(clean_word)
    
    total_words = len(raw_words)
    missing_count = len(missing_words)
    
    return total_words, missing_count, missing_words

def transcribe_file(file_path, model):
    """Transcribe a single audio file using faster-whisper"""
    start_time = time.time()
    
    try:
        # Run transcription
        segments, info = model.transcribe(
            file_path,
            beam_size=5,
            language="ru",
            vad_filter=True,
            vad_parameters=dict(
                min_silence_duration_ms=500,
                threshold=0.5
            )
        )
        
        # Collect all text from segments
        text = ""
        for segment in segments:
            text += segment.text + " "
        
        text = text.strip()
        
    except Exception as e:
        print(f"   ❌ Error: {e}")
        return "", time.time() - start_time
    
    elapsed_ms = (time.time() - start_time) * 1000
    return text, elapsed_ms

def main():
    global true_count, missing_count_total
    
    print("=" * 60)
    print("FASTER-WHISPER GRAMMAR MATCHER")
    print("=" * 60)
    
    print(f"\n📚 Loaded {len(grammar)} grammar entries from grammar.json")
    
    # Load Whisper model
    print(f"Loading Whisper model: {MODEL_SIZE} ({DEVICE}, {COMPUTE_TYPE})")
    try:
        model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE_TYPE)
        print("✅ Model loaded")
    except Exception as e:
        print(f"Error: {e}")
        return
    
    if not os.path.exists(TEST_FOLDER):
        print(f"Folder '{TEST_FOLDER}' not found!")
        return
    
    # Support multiple audio formats
    audio_files = [f for f in os.listdir(TEST_FOLDER) 
                   if f.lower().endswith(('.wav', '.mp3', '.m4a', '.flac', '.ogg'))]
    
    if not audio_files:
        print(f"No audio files found in '{TEST_FOLDER}'")
        return
    
    print(f"Found {len(audio_files)} files")
    print(f"Grammar has {len(grammar)} entries")
    
    total_time = 0
    result_list = []
    
    # Process each file
    for filename in sorted(audio_files):
        file_path = os.path.join(TEST_FOLDER, filename)
        
        print(f"\n{filename}")
        
        # Transcribe with Whisper
        recognized, elapsed_ms = transcribe_file(file_path, model)
        total_time += elapsed_ms
        
        # Find best match from grammar
        best_match, best_score = find_best_match(recognized, grammar)
        
        # Analyze word coverage
        total_words, missing_count, missing_words = analyze_word_coverage(recognized, grammar)
        
        print(f"   ⏱️  {elapsed_ms:.1f} ms")
        print(f"   🗣️  Raw: '{recognized}'")
        print(f"   📊 Words: {total_words} total, {missing_count} not in grammar")
        missing_count_total += missing_count
        if missing_words:
            print(f"      Missing words: {', '.join(missing_words)}")
        
        if best_match:
            # Check if correct
            expected = correct_list.get(filename, "")
            if expected and expected == best_match:
                true_count += 1
                result_list.append(f" ✅ {filename}: {best_match} ({best_score:.1%}) | Кол. ошибок: {missing_count} из {total_words} слов")
            else:
                result_list.append(f" ❌ {filename}: {best_match} ({best_score:.1%}) | Кол. ошибок: {missing_count} из {total_words} слов")
            
            if best_score >= THRESHOLD and expected == best_match:
                print(f"   ✅  Best match ({best_score:.1%}): '{best_match}'")
            else:
                print(f"   ⚠️  Best match ({best_score:.1%}): '{best_match}'")
                if expected:
                    print(f"      Expected: '{expected}'")
        else:
            result_list.append(f" ❌ {filename}: не опознанно | Кол. ошибок: {missing_count} из {total_words} слов")
            print(f"   ❌  No match found")
    
    # Summary
    print("\n" + "=" * 60)
    print("SUMMARY")
    print("=" * 60)
    print(f"Обработанные файлы: {len(audio_files)}")
    print(f"Общее время: {total_time:.1f} ms")
    if audio_files:
        print(f"Средняя задержка: {total_time/len(audio_files):.1f} ms")
    print(f"\nРезультаты:")
    for result in result_list:
        print(f"   {result}")
    print(f"Кол. правильных: {true_count}/{len(audio_files)}")
    print(f"Кол. неправильных слов: {missing_count_total}")
    print("\n" + "=" * 60)

if __name__ == "__main__":
    main()