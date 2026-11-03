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


def test_existing_leading_bom_is_stripped_on_each_backend_apply():
    content = "\ufeffA B"
    validate_patch_sequence(content, [(1, 2, "A", "a"), (3, 4, "B", "b")])


def test_single_patch_can_replace_a_bom_in_its_original():
    validate_patch_sequence("\ufeffA", [(0, 2, "\ufeffA", "replacement")])


def test_pending_bom_original_lost_after_another_patch_is_rejected():
    content = "\ufeffA B"
    with pytest.raises(ValueError, match="no longer unique"):
        validate_patch_sequence(content, [(0, 2, "\ufeffA", "X"), (3, 4, "B", "Y")])


def test_two_boms_can_leave_a_pending_bom_original_after_one_apply():
    content = "\ufeff\ufeffA B"
    validate_patch_sequence(content, [(1, 3, "\ufeffA", "X"), (4, 5, "B", "Y")])


def test_leading_bom_cannot_hide_an_empty_document_after_application():
    with pytest.raises(ValueError):
        validate_patch_sequence("\ufeffA", [(1, 2, "A", "")])


def test_blank_suffix_can_be_replaced_before_bom_is_removed():
    validate_patch_sequence("\ufeff  ", [(1, 3, "  ", "content")])


def test_many_independent_patches_do_not_enumerate_all_bom_orders():
    originals = [f"<item-{index:02d}>" for index in range(25)]
    content = "\ufeff\ufeff" + " ".join(originals) + " anchor"
    patches = [
        (
            content.index(original),
            content.index(original) + len(original),
            original,
            f"fixed-{index:02d}",
        )
        for index, original in enumerate(originals)
    ]
    validate_patch_sequence(content, patches)


def test_complex_bom_sequences_fail_closed_at_the_state_budget(monkeypatch):
    monkeypatch.setattr(verifier, "MAX_BOM_STATES", 2)
    content = "\ufeffA\ufeff B C"
    patches = [(1, 2, "A", "a"), (4, 5, "B", "b"), (6, 7, "C", "c")]
    with pytest.raises(ValueError, match="too complex"):
        validate_patch_sequence(content, patches)


@pytest.mark.parametrize(
    ("content", "originals"),
    [
        ("\ufeffA B C anchor", ["A", "B", "C"]),
        ("\ufeff\ufeffA B C anchor", ["\ufeffA", "B", "C"]),
        ("\ufeffA\ufeff B C anchor", ["A", "B", "C"]),
    ],
)
def test_accepted_bom_sequences_match_all_backend_orders(content, originals):
    random = Random(22284)
    choices = ["", "A", "B", "C", "x", "AB", "\r", "\n", "\ufeff"]
    for _ in range(200):
        pairs = [(original, random.choice(choices)) for original in originals]
        patches = [
            (
                content.index(original),
                content.index(original) + len(original),
                original,
                replacement,
            )
            for original, replacement in pairs
        ]
        try:
            validate_patch_sequence(content, patches)
        except ValueError:
            continue  # A bounded check may conservatively decline a complex set.
        for length in range(1, len(pairs) + 1):
            for order in permutations(pairs, length):
                current = content
                for original, replacement in order:
                    start = current.find(original)
                    assert start >= 0 and start == current.rfind(original)
                    raw = (
                        current[:start] + replacement + current[start + len(original) :]
                    )
                    assert len(raw.encode("utf-8")) <= verifier.MAX_DOCUMENT_BYTES
                    current = (
                        raw.removeprefix("\ufeff")
                        .replace("\r\n", "\n")
                        .replace("\r", "\n")
                    )
                    assert current.strip()
                    assert len(current) <= verifier.MAX_DOCUMENT_CODEPOINTS


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
