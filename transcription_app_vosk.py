"""
transcription_app_vosk.py
==========================
Real-time распознавание голосовых команд через VOSK.

Двухрежимная архитектура:
  - Wake word detection: свободное распознавание — детектит "протез"
  - Command capture: свободное распознавание + fuzzy match против grammar.json
    (constrained grammar через HCLG.fst невозможен на small-ru-0.22 —
     доменные слова отсутствуют в словаре модели)

Архитектура:
  - 2 фоновых потока: audio capture, VOSK worker
  - Основной поток Kivy — GUI
  - Внешний VAD (webrtcvad) управляет пайплайном
"""

import os
import re
import time
import json
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

from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.uix.widget import Widget
from kivy.clock import Clock
from kivy.graphics import Color, Rectangle


# ═══════════════════════════════════════════════════════════════
# КОНФИГУРАЦИЯ
# ═══════════════════════════════════════════════════════════════

KEYWORD        = "протез"
END_WORD       = "выполнять"
END_SYNONYMS   = {END_WORD, "выполняй", "выполняйте", "выполни", "поехали", "давай", "старт"}
PACKET_TIMEOUT = 5.0
LOG_DIR        = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logs_vosk")
CHUNK_DURATION = 1.0
SAMPLE_RATE    = 16000
CHUNK_SIZE     = 1024
MODEL_PATH     = os.path.join(os.path.dirname(os.path.abspath(__file__)), "model-ru")
GRAMMAR_PATH   = os.path.join(os.path.dirname(os.path.abspath(__file__)), "grammar.json")

VAD_AGGRESSIVE = 3
VAD_FRAME_MS   = 30
SILENCE_SEC    = 0.8
MATCH_THRESHOLD = 0.85

GESTURES = {
    "нейтральный", "нейтраль",
    "большой палец",
    "сжатие пальцев", "сжатие", "кулак",
    "разжатие пальцев", "разжатие", "открытая ладонь", "открыть",
    "щипок", "щепок", "щепать",
    "указательный", "пистолет",
    "флексия", "сгибание",
    "экстензия", "разгибание",
}

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
        self.model = None
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

        os.makedirs(LOG_DIR, exist_ok=True)
        self._log_path = os.path.join(LOG_DIR, datetime.now().strftime("%Y-%m-%d_%H-%M-%S") + ".log")
        self._log_file = open(self._log_path, "a", encoding="utf-8")

        threading.Thread(target=self._load_model, daemon=True).start()
        threading.Thread(target=self._audio_capture, daemon=True).start()
        threading.Thread(target=self._vosk_worker, daemon=True).start()

        # ── Корневой layout ──
        self.main = BoxLayout(orientation='vertical', spacing=4, padding=10)

        # ── Верхняя панель (фиксированная) ──
        top_panel = BoxLayout(orientation='vertical', spacing=2, size_hint=(1, None))
        top_panel.height = 80

        self.state_label = Label(
            text='[ЗАГРУЗКА VOSK...]',
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

        self._log(f"Грамматика: {len(json.loads(self.grammar_str))} фраз | Модель: {MODEL_PATH}")

        Clock.schedule_interval(self._update_gui, 0.10)
        return self.main

    def _update_log_bg(self, *args):
        self._log_bg.size = self._log_scroll.size
        self._log_bg.pos = self._log_scroll.pos

    # ── Загрузка модели ─────────────────────────────────────────
    def _load_model(self):
        self._log(f"Загрузка VOSK из {MODEL_PATH}...")
        t0 = time.time()
        self.model = vosk.Model(MODEL_PATH)
        self._log(f"VOSK загружен за {time.time() - t0:.1f}s")
        self.state = State.LISTENING

    # ── Захват аудио ────────────────────────────────────────────
    def _audio_capture(self):
        stream = self.audio.open(
            format=pyaudio.paInt16, channels=1, rate=SAMPLE_RATE,
            input=True, frames_per_buffer=CHUNK_SIZE)
        while self.running:
            try:
                data = stream.read(CHUNK_SIZE, exception_on_overflow=False)
                with self.buffer_lock:
                    self.audio_buffer.append(data)
                    max_chunks = int(SAMPLE_RATE / CHUNK_SIZE * CHUNK_DURATION * 2)
                    while len(self.audio_buffer) > max_chunks:
                        self.audio_buffer.popleft()
                    if self.state == State.CAPTURING:
                        self.captured_frames.append(data)
            except Exception as e:
                self._log(f"Audio error: {e}")
                time.sleep(0.1)
        stream.stop_stream()
        stream.close()

    # ── VOSK worker ──────────────────────────────────────────────
    def _vosk_worker(self):
        while self.model is None and self.running:
            time.sleep(0.1)

        while self.running:
            if self.state in (State.LOADING, State.PROCESSING):
                time.sleep(0.1)
                continue

            time.sleep(CHUNK_DURATION)

            if self.state == State.LISTENING:
                self._vosk_listening_cycle()
            elif self.state == State.CAPTURING:
                self._vosk_capturing_check()

    def _vosk_listening_cycle(self):
        with self.buffer_lock:
            if len(self.audio_buffer) < int(SAMPLE_RATE / CHUNK_SIZE * 0.5):
                return
            frames = list(self.audio_buffer)

        audio = self._frames_to_audio(frames)
        if not has_speech(audio):
            return

        text = self._vosk_transcribe_freeform(audio)
        if not text:
            return

        self._log(f"[СЛУШАЮ] {text}")

        if KEYWORD in text.lower():
            self._log(f"*** КЛЮЧЕВОЕ СЛОВО: '{KEYWORD}' ***")
            self.state = State.CAPTURING
            self.capture_start = time.time()
            self.captured_frames = list(frames)
            with self.buffer_lock:
                self.audio_buffer.clear()

    def _vosk_capturing_check(self):
        elapsed = time.time() - self.capture_start
        chunk_audio = self._frames_to_audio(self.captured_frames)
        silence = trailing_silence_sec(chunk_audio)

        if (elapsed >= 0.5 and silence >= SILENCE_SEC) or elapsed >= PACKET_TIMEOUT:
            self.state = State.PROCESSING
            self.result_queue.put(("process", chunk_audio))

    # ── VOSK: свободное распознавание (wake word) ────────────────
    def _vosk_transcribe_freeform(self, audio: np.ndarray) -> str:
        """Без грамматики — для детекции ключевого слова."""
        if len(audio) < SAMPLE_RATE * 0.15:
            return ""
        try:
            rec = vosk.KaldiRecognizer(self.model, SAMPLE_RATE)
            rec.SetWords(True)

            audio_int16 = (audio * 32768.0).astype(np.int16)
            rec.AcceptWaveform(audio_int16.tobytes())

            result = json.loads(rec.FinalResult())
            return result.get("text", "").strip()
        except Exception as e:
            self._log(f"VOSK error: {e}")
            return ""

    # ── VOSK: свободное + fuzzy match против grammar.json ────────
    def _vosk_transcribe_packet(self, audio: np.ndarray) -> str:
        """Свободная транскрипция + поиск лучшего совпадения в grammar.json."""
        raw = self._vosk_transcribe_freeform(audio)
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
    def _parse_packet(self, text: str) -> dict | None:
        words = [re.sub(r'[^\w]', '', w) for w in text.lower().split()]
        words = [w for w in words if w]

        if KEYWORD not in words:
            return None

        idx = words.index(KEYWORD)
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
                    return f"Жест: {gesture}"
            return "Не распознан"

        elif cmd in ("статус", "status"):
            return "Батарея 66%"

        else:
            return f"Неизвестная команда: '{cmd}'"

    # ── GUI ──────────────────────────────────────────────────────
    def _update_gui(self, dt):
        try:
            while True:
                msg_type, data = self.result_queue.get_nowait()
                if msg_type == "process":
                    self._handle_process(data)
                elif msg_type == "partial":
                    self.partial_label.text = data
                elif msg_type == "log":
                    self._append_log(data)
        except queue.Empty:
            pass

        if self.state == State.LOADING:
            self.state_label.text = "[ЗАГРУЗКА VOSK...]"
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
        text = self._vosk_transcribe_packet(audio)
        self._log(f"[ПАКЕТ] {text}")
        self.partial_label.text = ""

        parsed = self._parse_packet(text)

        if parsed is None:
            self._log(f"[!] KEYWORD '{KEYWORD}' not found: '{text}'")
            self.response_label.text = "Ошибка: пакет не распознан"
            self.response_label.color = (1, 0.3, 0.3, 1)
        else:
            result = self._execute(parsed)
            self._log(f"[ОТВЕТ] {result}")
            self.response_label.text = result
            if "Не распознан" in result or "Неизвестная" in result:
                self.response_label.color = (1, 0.5, 0.2, 1)
            else:
                self.response_label.color = (0.3, 1, 0.3, 1)

        self.state = State.LISTENING
        self.captured_frames = []

    # ── Логирование ──────────────────────────────────────────────
    def _log(self, msg: str):
        print(msg, flush=True)
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
        try:
            self._log_file.close()
        except Exception:
            pass


if __name__ == '__main__':
    VoskVoiceApp().run()
