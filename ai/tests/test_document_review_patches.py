"""Cross-check the bounded sequence verifier against actual small edit orders."""

from itertools import permutations
from random import Random

import pytest

from app.services import document_review_patches as verifier
from app.services.document_review_patches import validate_patch_sequence


def test_sequence_verifier_matches_exhaustive_orders_for_small_patch_sets():
    random = Random(222)
    content = "A B C anchor"
    originals = ["A", "B", "C"]
    choices = ["", "A", "B", "C", "x", "AB", "xC", "yx"]
    for _ in range(250):
        replacements = [random.choice(choices) for _ in originals]
        pairs = list(zip(originals, replacements, strict=True))
        patches = [
            (content.index(a), content.index(a) + len(a), a, b) for a, b in pairs
        ]
        safe = True
        for length in range(1, len(pairs) + 1):
            for order in permutations(pairs, length):
                current = content
                for original, replacement in order:
                    start = current.find(original)
                    if start < 0 or start != current.rfind(original):
                        safe = False
                        break
                    current = (
                        current[:start] + replacement + current[start + len(original) :]
                    )
                if not safe:
                    break
            if not safe:
                break
        if safe:
            validate_patch_sequence(content, patches)
        else:
            with pytest.raises(ValueError):
                validate_patch_sequence(content, patches)


def test_raw_utf8_byte_limit_is_inclusive(monkeypatch):
    monkeypatch.setattr(verifier, "MAX_DOCUMENT_BYTES", 5)
    validate_patch_sequence("A", [(0, 1, "A", "😀x")])
    with pytest.raises(ValueError, match="invalid document"):
        validate_patch_sequence("A", [(0, 1, "A", "😀xx")])


def test_excessive_sequence_verification_work_fails_closed(monkeypatch):
    monkeypatch.setattr(verifier, "MAX_MATCH_WORK", 1)
    with pytest.raises(ValueError, match="too complex"):
        validate_patch_sequence("A B", [(0, 1, "A", "x")])


def test_accepted_multichar_patches_survive_backend_simulation_in_any_order():
    random = Random(84)
    content = "ab|cd|ef|xyz| anchor"
    originals = ["ab", "cd", "ef", "xyz"]
    choices = ["", "a", "b", "x", "y", "z", "ab", "xyz", "\r", "\n", "\ufeff"]
    for _ in range(300):
        pairs = [(original, random.choice(choices)) for original in originals]
        patches = [
            (content.index(a), content.index(a) + len(a), a, b) for a, b in pairs
        ]
        try:
            validate_patch_sequence(content, patches)
        except ValueError:
            continue  # The verifier may conservatively reject a complicated set.
        for length in range(1, len(pairs) + 1):
            for order in permutations(pairs, length):
                current = content
                for original, replacement in order:
                    start = current.find(original)
                    assert start >= 0 and start == current.rfind(original)
                    current = (
                        current[:start] + replacement + current[start + len(original) :]
                    )
                    current = (
                        current.removeprefix("\ufeff")
                        .replace("\r\n", "\n")
                        .replace("\r", "\n")
                    )
                    assert current.strip()
