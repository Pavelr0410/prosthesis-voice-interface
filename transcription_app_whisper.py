"""
transcription_app_vosk_whisper.py
==================================
Real-time распознавание голосовых команд через Whisper (faster-whisper, small) + кнопки команд.

Отличается от transcription_app_vosk_2.py движком распознавания:
  - вместо Vosk используется faster-whisper (Whisper-small, cpu/int8);
  - Whisper — батчевая модель, поэтому транскрипция выполняется в фоновом потоке.

Архитектура:
  - 2 фоновых потока: audio capture, Whisper worker
  - Основной поток Kivy — GUI (голосовой интерфейс слева, кнопки справа)
  - Внешний VAD (webrtcvad) управляет пайплайном
"""

import os

# Windows: конфликт двух копий OpenMP (MKL/numpy + ctranslate2/faster-whisper)
# без этого OMP Error #15 аварийно завершает процесс.
os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")

import re
import time
import json
import wave
import asyncio
import threading
import collections
import queue
from enum import Enum, auto
from datetime import datetime
from difflib import SequenceMatcher

import numpy as np
import pyaudio
import webrtcvad
import vosk
from faster_whisper import WhisperModel
from bleak import BleakClient, BleakScanner

from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.uix.widget import Widget
from kivy.clock import Clock
from kivy.graphics import Color, Rectangle

import MotoricaInterface as mi

# Windows: перенаправленный stdout может быть в cp1252 — кириллица роняет print().
# Принудительно переключаемся на UTF-8.
import sys
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


# ═══════════════════════════════════════════════════════════════
# КОНФИГУРАЦИЯ
# ═══════════════════════════════════════════════════════════════

KEYWORD        = "протез"
KEYWORD_MATCH_THRESHOLD = 0.6  # фаззи-детекция wake word: "процесс"/"протест" ≈ "протез"
END_WORD       = "выполнять"
END_SYNONYMS   = {END_WORD, "выполняй", "выполняйте", "выполни", "поехали", "давай", "старт"}
PACKET_TIMEOUT = 5.0
LOG_DIR        = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logs_vosk")
CHUNK_DURATION = 1.0
SAMPLE_RATE    = 16000
CHUNK_SIZE     = 1024
MODEL_PATH     = os.path.join(os.path.dirname(os.path.abspath(__file__)), "model-ru")
GRAMMAR_PATH   = os.path.join(os.path.dirname(os.path.abspath(__file__)), "grammar.json")
PROSTHESIS_PORT = "COM3"
PROSTHESIS_BLE_ADDRESS = "CC:E8:8A:47:19:A4"   # FEST-XFTHS00000

VAD_AGGRESSIVE = 3
VAD_FRAME_MS   = 30
SILENCE_SEC    = 0.8
MATCH_THRESHOLD = 0.85

# Whisper (faster-whisper)
# tiny — быстрее всего (~1с/команду на CPU); base/small — точнее, но медленнее.
WHISPER_MODEL_SIZE = "small"
WHISPER_DEVICE = "cpu"
WHISPER_COMPUTE_TYPE = "int8"
WHISPER_CPU_THREADS = 8

GESTURES = {
    "нейтральный", "нейтраль",
    "большой палец",
    "сжатие пальцев", "сжатие", "кулак",
    "разжатие пальцев", "разжатие", "открытая ладонь", "открыть",
    "щипок", "щепок", "щепать",
    "указательный", "пистолет",
    "флексия", "сгибание",
    "экстензия", "разгибание",
    "коза",
}

# Действия на протезе (внешняя библиотека MotoricaInterface):
#   ("gesture", name) — встроенный жест FestSerialInterface.prot_exec_gesture
#   ("abs", kwargs)   — абсолютные позиции пальцев FestSerialInterface.prot_abs_state
GESTURE_ACTIONS = {
    "нейтральный":     ("gesture", "neutral"),
    "нейтраль":        ("gesture", "neutral"),
    "большой палец":   ("abs", {"thumb": 100}),
    "сжатие пальцев":  ("gesture", "grab"),
    "сжатие":          ("gesture", "grab"),
    "кулак":           ("gesture", "grab"),
    "разжатие пальцев": ("gesture", "open"),
    "разжатие":        ("gesture", "open"),
    "открытая ладонь": ("gesture", "open"),
    "открыть":         ("gesture", "open"),
    "щипок":           ("gesture", "pinch"),
    "щепок":           ("gesture", "pinch"),
    "щепать":          ("gesture", "pinch"),
    "указательный":    ("gesture", "indication"),
    "пистолет":        ("gesture", "indication"),
    "флексия":         ("gesture", "grab"),
    "сгибание":        ("gesture", "grab"),
    "экстензия":       ("gesture", "open"),
    "разгибание":      ("gesture", "open"),
    "коза":            ("abs", {"thumb": 100, "index": 0, "middle": 100, "ring": 100, "pinky": 0, "thumb_rot": 0}),
}

# ── Кнопки команд (подпись кнопки → действие на протезе) ───────
BUTTON_COMMANDS = [
    ("Открыть",       ("gesture", "open")),
    ("Кулак",         ("gesture", "grab")),
    ("Нейтральный",   ("gesture", "neutral")),
    ("Указательный",  ("gesture", "indication")),
    ("Щипок",         ("gesture", "pinch")),
    ("Большой палец", ("abs", {"thumb": 100})),
    ("Коза",          ("abs", {"thumb": 100, "index": 0, "middle": 100, "ring": 100, "pinky": 0, "thumb_rot": 0})),
]

# ═══════════════════════════════════════════════════════════════
# КОНЕЧНЫЙ АВТОМАТ
# ═══════════════════════════════════════════════════════════════
class State(Enum):
    LOADING   = auto()
    LISTENING = auto()
    CAPTURING = auto()
    PROCESSING = auto()


# ═══════════════════════════════════════════════════════════════
# VAD (webrtcvad)
# ═══════════════════════════════════════════════════════════════

_vad = webrtcvad.Vad(VAD_AGGRESSIVE)
VAD_FRAME_SAMPLES = int(SAMPLE_RATE * VAD_FRAME_MS / 1000)


# ═══════════════════════════════════════════════════════════════
# BLE-интерфейс протеза (Fest MOVE_ALL_FINGERS)
# ═══════════════════════════════════════════════════════════════

# Порядок пальцев в 6-байтовой посылке характеристики 4368000a:
# pinky, ring, middle, index, thumb, thumb_rot
BLE_FINGER_ORDER = ["pinky", "ring", "middle", "index", "thumb", "thumb_rot"]


class FestBle:
    """Синхронная обёртка над bleak для управления протезом Fest по BLE."""

    MOVE_ALL_FINGERS = "4368000a-4d74-1001-726b-526f64696f6e"

    def __init__(self, address, connect_timeout=8.0):
        self.address = address
        self.connect_timeout = connect_timeout
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._run_loop, daemon=True)
        self._client: BleakClient | None = None
        self.connected = False

    def _run_loop(self):
        asyncio.set_event_loop(self._loop)
        self._loop.run_forever()

    def start(self):
        self._thread.start()
        self.connect()

    def _sync(self, coro, timeout=None):
        fut = asyncio.run_coroutine_threadsafe(coro, self._loop)
        return fut.result(timeout=timeout or self.connect_timeout)

    def connect(self):
        async def _connect():
            self._client = BleakClient(self.address)
            await self._client.connect()
            return self._client.is_connected
        try:
            self.connected = bool(self._sync(_connect()))
        except Exception as e:
            print(f"[BLE] connect error: {e}")
            self.connected = False

    def set_fingers(self, positions):
        """positions: список из 6 значений [pinky, ring, middle, index, thumb, thumb_rot]."""
        async def _write():
            await self._client.write_gatt_char(
                self.MOVE_ALL_FINGERS, bytes(int(p) & 0xFF for p in positions)
            )
        self._sync(_write(), timeout=5.0)

    def disconnect(self):
        async def _disc():
            if self._client is not None:
                await self._client.disconnect()
        try:
            self._sync(_disc(), timeout=3.0)
        except Exception:
            pass
        try:
            self._loop.call_soon_threadsafe(self._loop.stop)
        except Exception:
            pass


def has_speech(audio: np.ndarray) -> bool:
    if len(audio) < VAD_FRAME_SAMPLES:
        return False
    audio_int16 = (audio * 32768.0).astype(np.int16)
    frames = len(audio_int16) // VAD_FRAME_SAMPLES
    for i in range(frames):
        chunk = audio_int16[i * VAD_FRAME_SAMPLES:(i + 1) * VAD_FRAME_SAMPLES]
        if _vad.is_speech(chunk.tobytes(), SAMPLE_RATE):
            return True
    return False


def trailing_silence_sec(audio: np.ndarray) -> float:
    if len(audio) < VAD_FRAME_SAMPLES:
        return 0.0
    audio_int16 = (audio * 32768.0).astype(np.int16)
    frames = len(audio_int16) // VAD_FRAME_SAMPLES
    silence_frames = 0
    for i in range(frames - 1, -1, -1):
        chunk = audio_int16[i * VAD_FRAME_SAMPLES:(i + 1) * VAD_FRAME_SAMPLES]
        if _vad.is_speech(chunk.tobytes(), SAMPLE_RATE):
            break
        silence_frames += 1
    return silence_frames * VAD_FRAME_MS / 1000.0


# ═══════════════════════════════════════════════════════════════
# ОСНОВНОЙ КЛАСС
# ═══════════════════════════════════════════════════════════════

class VoskVoiceApp(App):

    def build(self):
        self.state = State.LOADING
        self.vosk_model = None
        self.whisper_model = None
        try:
            self.grammar_str = json.dumps(json.load(open(GRAMMAR_PATH, encoding="utf-8")))
        except Exception as e:
            print(f"Grammar load error: {e}")
            self.grammar_str = "[]"

        self.audio = pyaudio.PyAudio()
        self.audio_buffer = collections.deque()
        self.buffer_lock = threading.Lock()
        self.result_queue = queue.Queue()

        self.capture_start = 0.0
        self.captured_frames = []

        self.log_lines = []
        self.running = True
        self._samples_busy = False

        os.makedirs(LOG_DIR, exist_ok=True)
        self._log_path = os.path.join(LOG_DIR, datetime.now().strftime("%Y-%m-%d_%H-%M-%S") + ".log")
        self._log_file = open(self._log_path, "a", encoding="utf-8")

        # ── Протез: при старте пробуем USB UART (COM3); BLE — через кнопку-переключатель ──
        self.ble = None
        self.serial = None
        try:
            self.serial = mi.FestSerialInterface(PROSTHESIS_PORT, debug=False, logging_enabled=False)
            self.serial.start()
            self._log(f"Протез подключен по serial: {PROSTHESIS_PORT}")
            self._force_prosthesis_open()
        except Exception as e:
            self.serial = None
            self._log(f"Serial недоступен ({PROSTHESIS_PORT}): {e}. Нажмите BLE Connect для подключения по BLE")

        threading.Thread(target=self._load_model, daemon=True).start()
        threading.Thread(target=self._audio_capture, daemon=True).start()
        threading.Thread(target=self._whisper_worker, daemon=True).start()

        # ── Корневой layout (горизонтальный: голосовой интерфейс + кнопки) ──
        self.root_box = BoxLayout(orientation='horizontal', spacing=6, padding=6)

        # ── Левая часть: голосовой интерфейс ──
        self.main = BoxLayout(orientation='vertical', spacing=4, padding=10)

        # ── Верхняя панель (фиксированная) ──
        top_panel = BoxLayout(orientation='vertical', spacing=2, size_hint=(1, None))
        top_panel.height = 80

        self.state_label = Label(
            text='[ЗАГРУЗКА WHISPER...]',
            font_size='20sp', halign='center', valign='middle',
            color=(0.8, 0.8, 1, 1), size_hint=(1, None), height=30)
        self.state_label.bind(size=self.state_label.setter('text_size'))
        top_panel.add_widget(self.state_label)

        self.partial_label = Label(
            text='', font_size='14sp', halign='center', valign='middle',
            color=(0.5, 0.7, 0.9, 1), size_hint=(1, None), height=24)
        self.partial_label.bind(size=self.partial_label.setter('text_size'))
        top_panel.add_widget(self.partial_label)

        self.response_label = Label(
            text='', font_size='22sp', halign='center', valign='middle',
            color=(0.3, 1, 0.3, 1), size_hint=(1, None), height=28)
        self.response_label.bind(size=self.response_label.setter('text_size'))
        top_panel.add_widget(self.response_label)

        self.main.add_widget(top_panel)

        # ── Нижняя панель: draggable консоль ──
        self._log_scroll = ScrollView(
            size_hint=(1, 0.75), bar_width=8,
            scroll_type=['bars', 'content'])
        with self._log_scroll.canvas.before:
            Color(0.08, 0.08, 0.1, 1)
            self._log_bg = Rectangle(size=self._log_scroll.size, pos=self._log_scroll.pos)
        self._log_scroll.bind(size=self._update_log_bg, pos=self._update_log_bg)

        self.log_label = Label(
            text='', font_size='12sp', halign='left', valign='top',
            color=(0.6, 0.6, 0.6, 1), size_hint_y=None,
            padding=(8, 4))
        self.log_label.bind(width=lambda *x: setattr(
            self.log_label, 'text_size', (self.log_label.width, None)))
        self.log_label.bind(texture_size=self.log_label.setter('size'))
        self._log_scroll.add_widget(self.log_label)

        self.main.add_widget(self._log_scroll)
        self.root_box.add_widget(self.main)

        # ── Правая часть: панель кнопок ──
        self.buttons_panel = BoxLayout(orientation='vertical', size_hint=(None, 1),
                                       width=220, spacing=6, padding=8)

        # ── Группа «меню»: BLE Connect + Manual control (сверху) ──
        self.menu_box = BoxLayout(orientation='vertical', spacing=6, size_hint_y=1)
        self.ble_connect_button = Button(
            text='BLE Connect', font_size='16sp', size_hint_y=None, height=52,
            background_color=(0.35, 0.35, 0.4, 1))
        self.ble_connect_button.bind(on_press=self._on_ble_connect)
        self.menu_box.add_widget(self.ble_connect_button)

        manual_button = Button(
            text='Manual control', font_size='16sp', size_hint_y=None, height=52,
            background_color=(0.25, 0.45, 0.7, 1))
        manual_button.bind(on_press=self._on_manual_control)
        self.menu_box.add_widget(manual_button)

        self.samples_button = Button(
            text='Run samples', font_size='16sp', size_hint_y=None, height=52,
            background_color=(0.55, 0.35, 0.65, 1))
        self.samples_button.bind(on_press=self._on_run_samples)
        self.menu_box.add_widget(self.samples_button)
        self.menu_box.add_widget(Widget())  # растягивается вниз

        # ── Группа «команды»: жесты сверху, «Назад» внизу (скрыта по умолчанию) ──
        self.cmd_box = BoxLayout(orientation='vertical', spacing=6, size_hint_y=1)
        for label, action in BUTTON_COMMANDS:
            btn = Button(text=label, font_size='16sp', size_hint_y=None, height=52)
            btn.bind(on_press=self._make_button_cb(label, action))
            self.cmd_box.add_widget(btn)

        self.cmd_box.add_widget(Widget())  # растягивается, прижимает «Назад» вниз

        back_button = Button(
            text='Назад', font_size='16sp', size_hint_y=None, height=52,
            background_color=(0.7, 0.25, 0.25, 1))
        back_button.bind(on_press=self._on_back)
        self.cmd_box.add_widget(back_button)

        self.buttons_panel.add_widget(self.menu_box)
        self.root_box.add_widget(self.buttons_panel)

        self._log(f"Грамматика: {len(json.loads(self.grammar_str))} фраз | Модель: Whisper {WHISPER_MODEL_SIZE}")

        Clock.schedule_interval(self._update_gui, 0.10)
        return self.root_box

    def _update_log_bg(self, *args):
        self._log_bg.size = self._log_scroll.size
        self._log_bg.pos = self._log_scroll.pos

    # ── Кнопки команд ───────────────────────────────────────────
    def _on_manual_control(self, instance):
        self.buttons_panel.remove_widget(self.menu_box)
        self.buttons_panel.add_widget(self.cmd_box)

    def _on_back(self, instance):
        self.buttons_panel.remove_widget(self.cmd_box)
        self.buttons_panel.add_widget(self.menu_box)

    # ── Run samples: прогон аудио по всем папкам samples (Whisper) ──
    SAMPLE_FOLDERS = [
        "clean/commands", "clean/edge", "clean/status",
        "negative/malformed", "noisy/machinery", "noisy/office",
        "noisy/reverb", "noisy/street",
    ]

    def _on_run_samples(self, instance):
        if self._samples_busy:
            self._log("[SAMPLES] Обработка уже идёт...")
            return
        self._samples_busy = True
        self._set_samples_button_state(True)
        self._log("[SAMPLES] Микрофон отключён, запуск обработки сэмплов...")
        threading.Thread(target=self._run_samples, daemon=True).start()

    def _set_samples_button_state(self, busy):
        def _apply(dt):
            self.samples_button.text = "Обработка..." if busy else "Run samples"
            self.samples_button.background_color = (0.4, 0.4, 0.4, 1) if busy else (0.55, 0.35, 0.65, 1)
            self.samples_button.disabled = busy
        Clock.schedule_once(_apply)

    def _run_samples(self):
        try:
            base = os.path.dirname(os.path.abspath(__file__))
            samples_dir = os.path.join(base, "samples")
            manifest_path = os.path.join(base, "manifest.json")
            out_path = os.path.join(samples_dir, "Results_Whisper_base.txt")

            expected = {}
            try:
                with open(manifest_path, encoding="utf-8") as f:
                    data = json.load(f)
                items = data.get("samples", data) if isinstance(data, dict) else data
                for s in items:
                    p = s.get("path", "").replace("\\", "/")
                    if p:
                        expected[p] = (s.get("text", ""), s.get("expected_command", ""))
            except Exception as e:
                self._log(f"[SAMPLES] Ошибка чтения manifest.json: {e}")
                return

            all_lines = []
            folder_summary = []
            grand_total_ok = 0
            grand_total_count = 0
            grand_total_ms = 0.0

            for folder in self.SAMPLE_FOLDERS:
                folder_abs = os.path.join(samples_dir, folder)
                if not os.path.isdir(folder_abs):
                    self._log(f"[SAMPLES] Папка не найдена: {folder}, пропуск")
                    continue

                is_malformed = (folder == "negative/malformed")
                rows = []
                total_ok = 0
                total_ms = 0.0
                count = 0
                files = sorted(f for f in os.listdir(folder_abs) if f.lower().endswith(".wav"))

                for fname in files:
                    rel = folder + "/" + fname
                    info = expected.get(rel)
                    if info is None:
                        self._log(f"[SAMPLES] Нет записи в manifest для {rel}, пропуск")
                        continue
                    correct, expected_cmd = info

                    audio = self._read_wav(os.path.join(folder_abs, fname))
                    if audio is None or len(audio) == 0:
                        continue

                    t0 = time.time()
                    recognized, match_score, _ = self._transcribe_audio_batch(audio)
                    elapsed_ms = (time.time() - t0) * 1000

                    status = self._sample_status(recognized, correct, expected_cmd, is_malformed, match_score)
                    if status == "выполнен":
                        total_ok += 1
                    payload = self._executed_payload(recognized)
                    rows.append((correct, recognized, payload, match_score * 100, elapsed_ms, status))
                    total_ms += elapsed_ms
                    count += 1

                if count == 0:
                    self._log(f"[SAMPLES] {folder}: файлов не обработано")
                    continue

                pct = total_ok / count * 100
                avg_ms = total_ms / count
                folder_summary.append((folder, total_ok, count, pct, avg_ms))
                grand_total_ok += total_ok
                grand_total_count += count
                grand_total_ms += total_ms

                all_lines.append(f"## samples/{folder}")
                all_lines.append("| Правильный Результат | Результат Whisper | Команда (payload) | Соответствие в % | Задержка (мс) | Статус |")
                all_lines.append("|---|---|---|---|---|---|")
                for correct, recognized, payload, similarity, elapsed_ms, status in rows:
                    all_lines.append(f"| {correct} | {recognized} | {payload} | {similarity:.1f} | {elapsed_ms:.0f} | {status} |")
                all_lines.append("")

                self._log(f"[SAMPLES] {folder}: {total_ok}/{count} ({pct:.1f}%), средняя задержка {avg_ms:.1f} мс")

            all_lines.append("## Итог по папкам")
            all_lines.append("| Папка | Правильно | Процент | Средняя задержка (мс) |")
            all_lines.append("|---|---|---|---|")
            for folder, ok, cnt, pct, avg_ms in folder_summary:
                all_lines.append(f"| samples/{folder} | {ok}/{cnt} | {pct:.1f}% | {avg_ms:.1f} |")

            if grand_total_count > 0:
                all_lines.append("")
                all_lines.append("## Общий итог")
                all_lines.append(f"Всего обработано файлов: {grand_total_count}")
                all_lines.append(f"Средняя правильная выполнимость: {grand_total_ok / grand_total_count * 100:.1f}% ({grand_total_ok}/{grand_total_count})")
                all_lines.append(f"Средняя задержка обработки: {grand_total_ms / grand_total_count:.1f} мс")

            try:
                with open(out_path, "w", encoding="utf-8") as f:
                    f.write("\n".join(all_lines) + "\n")
                self._log(f"[SAMPLES] Результаты записаны в {out_path}")
            except Exception as e:
                self._log(f"[SAMPLES] Ошибка записи результата: {e}")
        finally:
            self._samples_busy = False
            self._set_samples_button_state(False)
            self._log("[SAMPLES] Обработка завершена, микрофон включён")

    def _executed_payload(self, recognized):
        """Команда (payload), которую выполнила бы программа по распознанному тексту."""
        parsed = self._parse_packet(recognized)
        if parsed is None:
            return self._find_gesture_in_text(recognized) or ""
        cmd = parsed.get("command", "")
        payload = parsed.get("payload", "")
        if cmd in ("статус", "status"):
            return "статус"
        if not payload:
            payload = self._find_gesture_in_text(recognized) or ""
        return payload

    def _sample_status(self, recognized, expected_text, expected_command, is_malformed=False, match_score=0.0):
        if is_malformed:
            # В samples/negative/malformed ни одна команда выполняться не должна:
            # если fuzzy match ниже порога (команда не распознана) — задача выполнена.
            return "выполнен" if match_score < MATCH_THRESHOLD else "не выполнен"
        if not recognized:
            return "не выполнен"
        if expected_command in ("статус", "status"):
            return "выполнен" if ("статус" in recognized or "status" in recognized) else "не выполнен"
        exp_gesture = self._find_gesture_in_text(expected_text)
        rec_gesture = self._find_gesture_in_text(recognized)
        return "выполнен" if (exp_gesture is not None and rec_gesture == exp_gesture) else "не выполнен"

    def _read_wav(self, path):
        try:
            w = wave.open(path, "rb")
            n = w.getnframes()
            raw = w.readframes(n)
            w.close()
            audio_int16 = np.frombuffer(raw, dtype=np.int16)
            return audio_int16.astype(np.float32) / 32768.0
        except Exception as e:
            self._log(f"[SAMPLES] Ошибка чтения {path}: {e}")
            return None

    def _transcribe_audio_batch(self, audio):
        """Как при команде: транскрипция Whisper + fuzzy match с grammar.json."""
        raw = self._whisper_transcribe(audio)
        if not raw:
            return "", 0.0, ""
        best_match, best_score = None, 0.0
        raw_lower = raw.lower().strip()
        for phrase in json.loads(self.grammar_str):
            score = SequenceMatcher(None, raw_lower, phrase.lower()).ratio()
            if score > best_score:
                best_score = score
                best_match = phrase
        if best_match and best_score >= MATCH_THRESHOLD:
            return best_match, best_score, raw
        return raw, best_score, raw

    def _find_gesture_in_text(self, text):
        if not text:
            return None
        for g in GESTURES:
            if g in text:
                return g
        return None

    def _on_ble_connect(self, instance):
        if self.ble is not None and self.ble.connected:
            self._log("[BLE] Отключаюсь...")
            threading.Thread(target=self._disconnect_ble_bg, daemon=True).start()
        else:
            self._log("[BLE] Попытка подключения...")
            threading.Thread(target=self._connect_ble_bg, daemon=True).start()

    def _set_ble_button_state(self, connected):
        if connected:
            text, color = "BLE Disconnect", (0.2, 0.7, 0.2, 1)
        else:
            text, color = "BLE Connect", (0.35, 0.35, 0.4, 1)

        def _apply(dt):
            self.ble_connect_button.text = text
            self.ble_connect_button.background_color = color
        Clock.schedule_once(_apply)

    def _connect_ble_bg(self):
        try:
            if self.ble is not None and self.ble.connected:
                return

            new_ble = FestBle(PROSTHESIS_BLE_ADDRESS)
            new_ble.start()
            if new_ble.connected:
                if self.serial is not None:
                    try:
                        self.serial.stop()
                        self.serial.serial_close()
                    except Exception:
                        pass
                    self.serial = None
                self.ble = new_ble
                self._log(f"[BLE] Подключено: {PROSTHESIS_BLE_ADDRESS}")
                self._set_ble_button_state(True)
                self._force_prosthesis_open()
            else:
                new_ble.disconnect()
                self._log("[BLE] Не удалось подключиться")
                self._set_ble_button_state(False)
        except Exception as e:
            self._log(f"[BLE] Ошибка: {e}")
            self._set_ble_button_state(False)

    def _disconnect_ble_bg(self):
        try:
            ble = self.ble
            self.ble = None
            if ble is not None:
                ble.disconnect()
            self._log("[BLE] Отключено")
        except Exception as e:
            self._log(f"[BLE] Ошибка отключения: {e}")
        self._set_ble_button_state(False)

    def _make_button_cb(self, label, action):
        def on_press(instance):
            self._log(f"[КНОПКА] {label}")
            self._exec_prosthesis_gesture(action)
            self.response_label.text = f"Команда: {label}"
            self.response_label.color = (0.3, 1, 0.3, 1)
        return on_press

    # ── Загрузка модели ─────────────────────────────────────────
    def _load_model(self):
        # Vosk — только для wake word "протез" (стриминговый, надёжный)
        self._log(f"Загрузка VOSK (wake word) из {MODEL_PATH}...")
        t0 = time.time()
        self.vosk_model = vosk.Model(MODEL_PATH)
        self._log(f"VOSK загружен за {time.time() - t0:.1f}s")

        # Whisper — транскрипция команд (законченная фраза)
        self._log(f"Загрузка Whisper {WHISPER_MODEL_SIZE} ({WHISPER_DEVICE}/{WHISPER_COMPUTE_TYPE})...")
        t0 = time.time()
        self.whisper_model = WhisperModel(
            WHISPER_MODEL_SIZE, device=WHISPER_DEVICE, compute_type=WHISPER_COMPUTE_TYPE,
            cpu_threads=WHISPER_CPU_THREADS
        )
        self._log(f"Whisper загружен за {time.time() - t0:.1f}s")
        self.state = State.LISTENING

    # ── Захват аудио ────────────────────────────────────────────
    def _audio_capture(self):
        stream = self.audio.open(
            format=pyaudio.paInt16, channels=1, rate=SAMPLE_RATE,
            input=True, frames_per_buffer=CHUNK_SIZE)
        while self.running:
            try:
                data = stream.read(CHUNK_SIZE, exception_on_overflow=False)
                if self._samples_busy:
                    continue
                with self.buffer_lock:
                    self.audio_buffer.append(data)
                    max_chunks = int(SAMPLE_RATE / CHUNK_SIZE * CHUNK_DURATION * 4)
                    while len(self.audio_buffer) > max_chunks:
                        self.audio_buffer.popleft()
                    if self.state == State.CAPTURING:
                        self.captured_frames.append(data)
            except Exception as e:
                self._log(f"Audio error: {e}")
                time.sleep(0.1)
        stream.stop_stream()
        stream.close()

    # ── Whisper worker ───────────────────────────────────────────
    def _whisper_worker(self):
        while (self.vosk_model is None or self.whisper_model is None) and self.running:
            time.sleep(0.1)

        while self.running:
            if self._samples_busy:
                time.sleep(0.1)
                continue

            if self.state in (State.LOADING, State.PROCESSING):
                time.sleep(0.1)
                continue

            time.sleep(CHUNK_DURATION)

            if self.state == State.LISTENING:
                self._whisper_listening_cycle()
            elif self.state == State.CAPTURING:
                self._vosk_capturing_check()

    def _whisper_listening_cycle(self):
        with self.buffer_lock:
            if len(self.audio_buffer) < int(SAMPLE_RATE / CHUNK_SIZE * 0.5):
                return
            frames = list(self.audio_buffer)

        audio = self._frames_to_audio(frames)
        if not has_speech(audio):
            return

        # Wake word детектит стриминговый Vosk (надёжно в потоке)
        text = self._vosk_transcribe_freeform(audio)
        if not text:
            return

        self._log(f"[СЛУШАЮ] {text}")

        if self._keyword_match(text):
            self._log(f"*** КЛЮЧЕВОЕ СЛОВО: '{KEYWORD}' ***")
            self.state = State.CAPTURING
            self.capture_start = time.time()
            self.captured_frames = list(frames)
            with self.buffer_lock:
                self.audio_buffer.clear()

    def _vosk_transcribe_freeform(self, audio: np.ndarray) -> str:
        """Стриминговая транскрипция Vosk для детекции ключевого слова."""
        if len(audio) < SAMPLE_RATE * 0.15:
            return ""
        try:
            rec = vosk.KaldiRecognizer(self.vosk_model, SAMPLE_RATE)
            rec.SetWords(True)
            audio_int16 = (audio * 32768.0).astype(np.int16)
            rec.AcceptWaveform(audio_int16.tobytes())
            result = json.loads(rec.FinalResult())
            return result.get("text", "").strip()
        except Exception as e:
            self._log(f"VOSK error: {e}")
            return ""

    def _vosk_capturing_check(self):
        elapsed = time.time() - self.capture_start
        chunk_audio = self._frames_to_audio(self.captured_frames)
        silence = trailing_silence_sec(chunk_audio)

        if (elapsed >= 0.5 and silence >= SILENCE_SEC) or elapsed >= PACKET_TIMEOUT:
            self.state = State.PROCESSING
            self.result_queue.put(("process", chunk_audio))

    # ── Whisper: транскрипция ────────────────────────────────────
    def _whisper_transcribe(self, audio: np.ndarray) -> str:
        """Транскрипция аудио через faster-whisper (с VAD и без галлюцинаций)."""
        if len(audio) < SAMPLE_RATE * 0.15:
            return ""
        try:
            segments, _ = self.whisper_model.transcribe(
                audio,
                language="ru",
                beam_size=1,
                temperature=0,
                vad_filter=True,
                vad_parameters=dict(
                    min_silence_duration_ms=400,
                    speech_pad_ms=200,
                ),
                condition_on_previous_text=False,  # иначе Whisper галлюцинирует на шуме
            )
            return " ".join(s.text for s in segments).strip()
        except Exception as e:
            self._log(f"Whisper error: {e}")
            return ""

    # ── Whisper: свободное + fuzzy match против grammar.json ─────
    def _whisper_transcribe_packet(self, audio: np.ndarray) -> str:
        """Транскрипция + поиск лучшего совпадения в grammar.json."""
        raw = self._whisper_transcribe(audio)
        if not raw:
            return ""

        self._log(f"[RAW] {raw}")

        grammar_list = json.loads(self.grammar_str)
        if not grammar_list:
            return raw

        best_match, best_score = None, 0.0
        raw_lower = raw.lower().strip()
        for phrase in grammar_list:
            score = SequenceMatcher(None, raw_lower, phrase.lower()).ratio()
            if score > best_score:
                best_score = score
                best_match = phrase

        if best_match and best_score >= MATCH_THRESHOLD:
            self._log(f"[MATCH] {best_score:.1%} → '{best_match}'")
            # Отправляем каждый шаг матчинга как partial для GUI
            words = best_match.split()
            for i in range(1, len(words) + 1):
                partial_text = " ".join(words[:i])
                self.result_queue.put(("partial", partial_text))
                time.sleep(0.02)
            return best_match
        else:
            self._log(f"[MATCH] {best_score:.1%} — below threshold, raw='{raw}'")
            return raw

    # ── Конвертер ────────────────────────────────────────────────
    @staticmethod
    def _frames_to_audio(frames: list) -> np.ndarray:
        if not frames:
            return np.array([], dtype=np.float32)
        raw = b''.join(frames)
        audio_int16 = np.frombuffer(raw, dtype=np.int16)
        return audio_int16.astype(np.float32) / 32768.0

    # ── Парсинг ──────────────────────────────────────────────────
    def _keyword_word_index(self, words) -> int | None:
        """Индекс слова, похожего на ключевое (устойчиво к ошибкам Vosk)."""
        for i, w in enumerate(words):
            if len(w) >= 4 and SequenceMatcher(None, w, KEYWORD).ratio() >= KEYWORD_MATCH_THRESHOLD:
                return i
        return None

    def _keyword_match(self, text: str) -> bool:
        words = [re.sub(r'[^\w]', '', w) for w in text.lower().split()]
        words = [w for w in words if w]
        return self._keyword_word_index(words) is not None

    def _parse_packet(self, text: str) -> dict | None:
        words = [re.sub(r'[^\w]', '', w) for w in text.lower().split()]
        words = [w for w in words if w]

        idx = self._keyword_word_index(words)
        if idx is None:
            return None

        body = words[idx + 1:]

        end_idx = None
        for i, w in enumerate(body):
            if w in END_SYNONYMS:
                end_idx = i
                break

        if end_idx is None:
            command = body[0] if body else ""
            payload = " ".join(body[1:]) if len(body) > 1 else ""
        elif end_idx == 0:
            command = ""
            payload = ""
        else:
            command = body[0] if body else ""
            payload = " ".join(body[1:end_idx]) if end_idx > 1 else ""

        return {"command": command, "payload": payload}

    # ── Исполнение ───────────────────────────────────────────────
    def _execute(self, parsed: dict) -> str:
        cmd = parsed.get("command", "")
        payload = parsed.get("payload", "")

        if cmd in ("жест", "gesture"):
            for gesture in GESTURES:
                if gesture in payload:
                    action = GESTURE_ACTIONS.get(gesture)
                    self._exec_prosthesis_gesture(action)
                    return f"Жест: {gesture}"
            return "Не распознан"

        elif cmd in ("статус", "status"):
            return "Батарея 66%"

        else:
            return f"Неизвестная команда: '{cmd}'"

    # ── Исполнение жеста на протезе ─────────────────────────────
    def _action_to_positions(self, action):
        """Преобразует действие в 6 позиций пальцев BLE-порядка
        [pinky, ring, middle, index, thumb, thumb_rot]."""
        kind, value = action
        if kind == "gesture":
            table = {
                "neutral":     [25, 25, 25, 25, 25, 25],
                "grab":        [100, 100, 100, 100, 100, 100],
                "open":        [0, 0, 0, 0, 0, 0],
                "indication":  [100, 100, 100, 0, 0, 0],
                "pinch":       [0, 0, 0, 50, 50, 100],
            }
            return table.get(value)
        elif kind == "abs":
            pos = {k: int(np.clip(v, 0, 100)) for k, v in value.items()}
            return [pos.get(f, 0) for f in BLE_FINGER_ORDER]
        return None

    def _exec_prosthesis_gesture(self, action):
        if action is None:
            return
        positions = self._action_to_positions(action)
        if positions is None:
            return

        if self.ble is not None:
            try:
                self.ble.set_fingers(positions)
                self._log(f"[ПРОТЕЗ] BLE жест отправлен: {action} -> {positions}")
            except Exception as e:
                self._log(f"[ПРОТЕЗ] BLE ошибка: {e}")
            return

        if self.serial is not None:
            try:
                self._serial_send_positions(positions)
                self._log(f"[ПРОТЕЗ] Serial жест отправлен: {action} -> {positions}")
            except Exception as e:
                self._log(f"[ПРОТЕЗ] Serial ошибка: {e}")

    def _serial_send_positions(self, positions):
        """Отправка позиций пальцев по serial в аппаратном порядке
        [pinky, ring, middle, index, thumb, thumb_rot] (подтверждено тестом)."""
        pack = np.array(
            [0x01, 0xBB, 6] + [int(np.clip(int(p), 0, 100)) for p in positions] + [0],
            dtype='uint8')
        pack[-1] = mi.get_crc(pack, len(pack) - 1)
        for _ in range(3):
            self.serial.serial.write(bytearray(pack))
            time.sleep(0.01)
        print(pack)

    # ── Принудительное раскрытие кисти при инициализации ────────
    def _force_prosthesis_open(self):
        if self.ble is not None:
            try:
                self.ble.set_fingers([0, 0, 0, 0, 0, 0])
                self._log("[ПРОТЕЗ] Кисть принудительно открыта (BLE)")
            except Exception as e:
                self._log(f"[ПРОТЕЗ] BLE ошибка открытия кисти: {e}")
            return
        try:
            self.serial.prot_open()
            self._serial_send_positions([0, 0, 0, 0, 0, 0])
            self._log("[ПРОТЕЗ] Кисть принудительно открыта")
        except Exception as e:
            self._log(f"[ПРОТЕЗ] Ошибка открытия кисти: {e}")

    # ── GUI ──────────────────────────────────────────────────────
    def _update_gui(self, dt):
        try:
            while True:
                msg_type, data = self.result_queue.get_nowait()
                if msg_type == "process":
                    self.partial_label.text = ""
                    threading.Thread(target=self._handle_process, args=(data,), daemon=True).start()
                elif msg_type == "partial":
                    self.partial_label.text = data
                elif msg_type == "response":
                    text, color = data
                    self.response_label.text = text
                    self.response_label.color = color
                elif msg_type == "log":
                    self._append_log(data)
        except queue.Empty:
            pass

        if self.state == State.LOADING:
            self.state_label.text = "[ЗАГРУЗКА WHISPER...]"
        elif self.state == State.LISTENING:
            self.state_label.text = "[СЛУШАЮ] Ожидание ключевого слова..."
            self.state_label.color = (0.6, 0.8, 1, 1)
        elif self.state == State.CAPTURING:
            elapsed = time.time() - self.capture_start
            self.state_label.text = f"[ЗАПИСЬ КОМАНДЫ] {elapsed:.1f}s / {PACKET_TIMEOUT:.0f}s"
            self.state_label.color = (1, 0.8, 0.3, 1)
        elif self.state == State.PROCESSING:
            self.state_label.text = "[ОБРАБОТКА...]"
            self.state_label.color = (0.8, 0.6, 1, 1)

    def _handle_process(self, audio: np.ndarray):
        t_start = time.time()
        text = self._whisper_transcribe_packet(audio)
        self._log(f"[ПАКЕТ] {text}")

        parsed = self._parse_packet(text)

        if parsed is None:
            self._log(f"[!] KEYWORD '{KEYWORD}' not found: '{text}'")
            self.result_queue.put(("response", ("Ошибка: пакет не распознан", (1, 0.3, 0.3, 1))))
        else:
            result = self._execute(parsed)
            elapsed_ms = (time.time() - t_start) * 1000
            self._log(f"[ОТВЕТ] {result}")
            self._log(f"[ТАЙМЕР] Обработка команды: {elapsed_ms:.0f} мс")
            if "Не распознан" in result or "Неизвестная" in result:
                self.result_queue.put(("response", (result, (1, 0.5, 0.2, 1))))
            else:
                self.result_queue.put(("response", (result, (0.3, 1, 0.3, 1))))

        self.state = State.LISTENING
        self.captured_frames = []

    # ── Логирование ──────────────────────────────────────────────
    def _log(self, msg: str):
        try:
            print(msg, flush=True)
        except Exception:
            pass
        try:
            self._log_file.write(msg + "\n")
            self._log_file.flush()
        except Exception:
            pass
        self.result_queue.put(("log", msg))

    def _append_log(self, msg: str):
        timestamp = time.strftime("%H:%M:%S")
        self.log_lines.append(f"[{timestamp}] {msg}")
        if len(self.log_lines) > 200:
            self.log_lines = self.log_lines[-200:]
        self.log_label.text = "\n".join(self.log_lines)
        self._log_scroll.scroll_y = 0

    # ── Завершение ───────────────────────────────────────────────
    def on_stop(self):
        self.running = False
        if self.audio:
            self.audio.terminate()
        if self.ble is not None:
            try:
                self.ble.disconnect()
            except Exception:
                pass
        if self.serial is not None:
            try:
                self.serial.stop()
                self.serial.serial_close()
            except Exception:
                pass
        try:
            self._log_file.close()
        except Exception:
            pass


if __name__ == '__main__':
    VoskVoiceApp().run()
