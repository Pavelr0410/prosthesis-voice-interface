"""
whitelist_corrector.py — пословный постпроцессинг VOSK free-form вывода.
======================================================================

После free-form транскрипции каждое слово корректируется к ближайшему
слову из whitelist-словаря (edit distance ≤ 2). Слова, не нашедшие
соответствия — выбрасываются. Результат подаётся на fuzzy match.

Это компенсирует отсутствие constrained grammar на small-ru-0.22:
акустическая модель слышит фонетически близкое слово из своего словаря,
корректор приводит его к разрешённому.

Whitelist строится из grammar.json — все уникальные слова, которые
могут появиться в выводе.
"""

def build_whitelist(grammar: list[str]) -> set[str]:
    """Извлечь все уникальные слова из фраз грамматики."""
    words = set()
    for phrase in grammar:
        for w in phrase.lower().split():
            words.add(w)
    return words


def edit_distance(a: str, b: str) -> int:
    """Расстояние Левенштейна (вставка/удаление/замена)."""
    if len(a) < len(b):
        a, b = b, a
    if len(b) == 0:
        return len(a)

    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        curr = [i]
        for j, cb in enumerate(b, 1):
            if ca == cb:
                curr.append(prev[j - 1])
            else:
                curr.append(1 + min(prev[j], curr[-1], prev[j - 1]))
        prev = curr
    return prev[-1]


def correct_word(word: str, whitelist: set[str], max_dist: int = 3) -> str | None:
    """Найти ближайшее whitelist-слово. None если расстояние > max_dist."""
    best, best_dist = None, max_dist + 1
    for wl in whitelist:
        d = edit_distance(word, wl)
        if d < best_dist:
            best_dist = d
            best = wl
            if d == 0:
                break  # точное совпадение
    return best if best_dist <= max_dist else None


def correct_phrase(raw: str, whitelist: set[str], max_dist: int = 3) -> str:
    """
    Пропустить каждое слово через correct_word.
    Собрать результат. Выбросить несопоставленные слова.
    """
    words = raw.lower().split()
    corrected = []
    for w in words:
        cw = correct_word(w, whitelist, max_dist)
        if cw:
            corrected.append(cw)
    return " ".join(corrected)


def should_correct(raw: str, whitelist: set[str], min_whitelist_words: int = 2) -> bool:
    """
    Проверить: стоит ли применять корректор?
    Если raw уже содержит ≥ min_whitelist_words слов из whitelist —
    вероятно, это валидная команда с небольшими искажениями. Корректируем.
    Если нет — скорее всего шум/OOV. Не трогаем (пусть fuzzy match отсеет).
    """
    raw_words = raw.lower().split()
    count = sum(1 for w in raw_words if w in whitelist)
    return count >= min_whitelist_words


# ═══════════════════════════════════════════════════════════════
if __name__ == "__main__":
    # Smoke test
    import json
    with open("grammar.json", encoding="utf-8") as f:
        grammar = json.load(f)
    wl = build_whitelist(grammar)
    print(f"Whitelist: {len(wl)} words: {sorted(wl)}")
    
    tests = [
        ("процесс жест кулак выполнен", "протез жест кулак выполняй"),
        ("пахоты с и жест экс тендере выполняй", "протез жест экстензия выполняй"),
        ("пора тэсс жест кулак выполняйте", "протез жест кулак выполняйте"),
        ("братес статус выполняй", "протез статус выполняй"),
        ("пока жест сжатия пальцев выполняй", "протез жест сжатие пальцев выполняй"),
    ]
    for raw, expected in tests:
        out = correct_phrase(raw, wl)
        ok = "✅" if out == expected else "❌"
        print(f"{ok} '{raw}' → '{out}' (expected: '{expected}')")
