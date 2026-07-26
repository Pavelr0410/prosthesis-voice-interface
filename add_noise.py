import os
import sys
import numpy as np
import soundfile as sf
from glob import glob
from tqdm import tqdm
import random
import pyroomacoustics as pra

# ═══════════════════════════════════════════════════════════════
# КОНФИГУРАЦИЯ (макропараметры)
# ═══════════════════════════════════════════════════════════════

# Тип шума (office, street, machinery, reverb)
keyword = "reverb"

# Пути к папкам
SPEECH_FOLDER = "samples/clean/commands"                                            # Папка с чистыми речевыми файлами
NOISE_FOLDER = "musan/noise/sound-bible"                                            # Папка с шумовыми файлами (MUSAN)
OUTPUT_FOLDER = f"samples/noisy/{keyword}"                                          # Папка для сохранения результатов

SNR_DB = [5, 10, 15]                                                                # SNR в дБ (чем меньше, тем больше шума)

office_sounds = [8, 17, 19, 23, 24, 28, 38, 40, 50, 54, 68, 76]                     # номеры шумов офиса
street_sounds = [11, 12, 14, 15, 16, 27, 30, 31, 33, 47, 49, 59, 61, 62, 75, 81]    # номеры уличных шумов
machinery_sounds = [5, 7, 20, 21, 22, 45, 48, 53, 65, 67, 71, 77, 84]               # номеры шумов производства

# Параметры помещения
ROOM_DIMS = [5, 4, 3]                             # Размеры комнаты в метрах (длина, ширина, высота)
FS = 16000                                        # Частота дискретизации
MAX_ORDER = 5                                     # Максимальный порядок отражений (чем больше, тем больше эха)
ABSORPTION = 0.3                                  # Коэффициент поглощения стен (0 = полное отражение, 1 = полное поглощение)
AIR_ABSORPTION = True                             # Учитывать поглощение звука в воздухе
# Позиции источника и микрофона (в метрах)
SOURCE_POS = [2, 2, 1.5]                          # Положение источника (речь)
MIC_POS = [2, 3, 1.5]                             # Положение микрофона

def add_noise(speech_wav, noise_wav, snr_db):
    speech, sr = sf.read(speech_wav)
    noise, _ = sf.read(noise_wav)
    
    # Обрезать или зациклить шум до длины речи
    if len(noise) < len(speech):
        noise = np.tile(noise, len(speech) // len(noise) + 1)
    noise = noise[:len(speech)]
    
    # нормировка по SNR
    speech_rms = np.sqrt(np.mean(speech**2))
    noise_rms = np.sqrt(np.mean(noise**2))
    target_noise_rms = speech_rms / (10 ** (snr_db / 20))
    if noise_rms > 0:
        noise = noise * (target_noise_rms / noise_rms)
    else:
        print(f"Шум в файле {noise_wav} имеет нулевую RMS")
    
    mixed = speech + noise
    max_val = np.max(np.abs(mixed))                                                # клиппинг
    if max_val > 0:
        mixed = mixed / max_val * 0.99
    return mixed, sr


def add_noise_to_folder(speech_folder, noise_folder, output_folder, snr_db_list):
    os.makedirs(output_folder, exist_ok=True)                                      # Создаём выходную папку
    if keyword == "office":
        noise_numbers = len(office_sounds)
    elif keyword == "street":
        noise_numbers = len(street_sounds)
    elif keyword == "machinery":
        noise_numbers = len(machinery_sounds)
    else:
        noise_numbers = len(office_sounds) + len(street_sounds) + len(machinery_sounds)
    speech_files = glob(os.path.join(speech_folder, "*.wav"))                      # Получаем список всех WAV файлов с речью
    
    if not speech_files:
        print(f"❌ В папке {speech_folder} нет WAV файлов!")
        return
    noise_files = []
    for num in noise_numbers:
        noise_filename = f"noise-sound-bible-{num:04d}.wav"
        noise_path = os.path.join(noise_folder, noise_filename)
        if os.path.exists(noise_path):
            noise_files.append(noise_path)
    
    if not noise_files:
        print(f"❌ В папке {noise_folder} нет файлов для категории {keyword}!")
        print(f"   Искались файлы: noise-sound-bible-XXXX.wav")
        return
    
    print(f"SNR: {snr_db_list}")
    print(f"Сохранение в: {output_folder}")
    print("=" * 50)
    
    for speech_path in tqdm(speech_files, desc="Обработка речевых файлов"):
        noise_path = random.choice(noise_files)                                    # Выбираем 1 случайный шумовой файл для этого речевого файла
        filename = os.path.basename(speech_path)
        name_without_ext = os.path.splitext(filename)[0]
        ext = os.path.splitext(filename)[1]
        
        # Для каждого значения SNR создаём отдельный файл
        for snr_db in snr_db_list:
            new_filename = f"{name_without_ext}_{keyword}_{snr_db}dB{ext}"        # Формируем новое имя файла: original_name_category_SNRdB.wav
            output_path = os.path.join(output_folder, new_filename)
            
            try:
                mixed, sr = add_noise(speech_path, noise_path, snr_db)
                sf.write(output_path, mixed, sr)
            except Exception as e:
                print(f"❌ Ошибка при обработке {filename} (SNR={snr_db}dB): {e}")

def apply_reverberation(speech, sr, room_dims, source_pos, mic_pos, max_order, absorption, air_absorption):
    room = pra.ShoeBox(room_dims, fs=sr, max_order=max_order, materials=pra.Material(absorption), air_absorption=True)
    room.add_source(source_pos, signal=speech)
    room.add_microphone(mic_pos)
    room.simulate()
    reverb = room.mic_array.signals[0]
    return reverb

def process_folder(input_folder, output_folder, room_dims, source_pos, mic_pos, max_order, absorption, air_absorption, fs):
    os.makedirs(output_folder, exist_ok=True)
    wav_files = glob(os.path.join(input_folder, "*.wav"))
    if not wav_files:
        print(f"❌ В папке {input_folder} нет WAV файлов!")
        return
    print(f"Сохранение в: {output_folder}")
    for input_path in tqdm(wav_files, desc="Обработка"):
        try:
            speech, sr = sf.read(input_path)                          # Загружаем аудио
            # Применяем реверберацию
            reverb_signal = apply_reverberation(speech, sr, room_dims, source_pos, mic_pos, max_order, absorption, air_absorption)
            filename = os.path.basename(input_path)
            output_path = os.path.join(output_folder, filename)
            sf.write(output_path, reverb_signal, sr)
        except Exception as e:
            print('❌ Error ocurred', 'on line {}:'.format(sys.exc_info()[-1].tb_lineno), type(e).__name__, ': ', e)
    
def main():
    print("=" * 60)
    print("ADD NOISE")
    print("=" * 60)
    
    # Проверяем, существуют ли папки
    if not os.path.exists(SPEECH_FOLDER):
        print(f"❌ Папка с речью не найдена: {SPEECH_FOLDER}")
        return
    
    if not os.path.exists(NOISE_FOLDER):
        print(f"❌ Папка с шумом не найдена: {NOISE_FOLDER}")
        return
    
    # Проверяем, что SNR_DB является списком
    if not isinstance(SNR_DB, list):
        print(f"❌ SNR_DB должен быть списком! Сейчас: {type(SNR_DB)}")
        return
    
    if keyword == "reverb":
        process_folder(SPEECH_FOLDER, OUTPUT_FOLDER, ROOM_DIMS, SOURCE_POS, MIC_POS, MAX_ORDER, ABSORPTION, AIR_ABSORPTION, FS)
    else:
        add_noise_to_folder(SPEECH_FOLDER, NOISE_FOLDER, OUTPUT_FOLDER, SNR_DB)
    
    print("=" * 60)
    print("✅ Готово!")
    print(f"Результаты в папке: {OUTPUT_FOLDER}")
    print("=" * 60)


if __name__ == "__main__":
    main()