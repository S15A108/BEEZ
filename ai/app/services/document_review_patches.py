"""Check that independently selectable review patches remain safe in any order."""

from unicodedata import category

MAX_DOCUMENT_BYTES = 1_048_576
MAX_DOCUMENT_CODEPOINTS = 100_000
# Bound work even when model-generated alternatives create many match states.
MAX_MATCH_WORK = 12_000_000


def _normalized(text: str) -> str:
    return text.replace("\r\n", "\n").replace("\r", "\n")


def _blank(text: str) -> bool:
    # Java String.isBlank uses Character.isWhitespace, which excludes these
    # three non-breaking spaces even though Python's str.isspace includes them.
    return all(
        char in "\t\n"
        or (category(char) in {"Zs", "Zl", "Zp"} and char not in "\u00a0\u2007\u202f")
        for char in text
    )


def _has_control(text: str) -> bool:
    return any(
        (ord(char) < 32 or 127 <= ord(char) <= 159) and char not in "\n\t"
        for char in text
    )


def _prefix_table(pattern: str) -> list[int]:
    table = [0] * len(pattern)
    prefix = 0
    for index in range(1, len(pattern)):
        while prefix and pattern[index] != pattern[prefix]:
            prefix = table[prefix - 1]
        if pattern[index] == pattern[prefix]:
            prefix += 1
        table[index] = prefix
    return table


def validate_patch_sequence(
    content: str, patches: list[tuple[int, int, str, str]]
) -> None:
    """Reject any set that can fail when PATCHes are applied or skipped in any order.

    Ranges are non-overlapping offsets in the original, BE-normalized snapshot.
    BE normalizes line endings after each application. For each still-pending
    original, a bounded KMP state search considers every subset of other edits
    without enumerating permutations or constructing all resulting documents.
    """
    if not patches:
        return
    if (
        not content
        or content.startswith("\ufeff")
        or _normalized(content) != content
        or _blank(content)
        or _has_control(content)
        or len(content) > MAX_DOCUMENT_CODEPOINTS
        or len(content.encode("utf-8")) > MAX_DOCUMENT_BYTES
    ):
        raise ValueError("invalid patch input document")

    ordered = sorted(patches)
    pieces: list[tuple[str, ...]] = []
    raw_deltas = []
    normalized_deltas = []
    codepoint_deltas = []
    cursor = 0
    for start, end, original, replacement in ordered:
        normalized = _normalized(replacement)
        if start < cursor or content[start:end] != original or _has_control(normalized):
            raise ValueError("invalid patch sequence")
        pieces.append((content[cursor:start],))
        pieces.append(tuple(dict.fromkeys((original, normalized))))
        cursor = end
        original_bytes = len(original.encode("utf-8"))
        raw_deltas.append(len(replacement.encode("utf-8")) - original_bytes)
        normalized_deltas.append(len(normalized.encode("utf-8")) - original_bytes)
        codepoint_deltas.append(len(normalized) - len(original))
    pieces.append((content[cursor:],))

    # A trailing CR can merge with an LF outside the replacement when BE
    # normalizes the whole document, changing another piece's contents.
    for index, (_, _, _, replacement) in enumerate(ordered):
        if not replacement.endswith("\r"):
            continue
        for piece in pieces[index * 2 + 2 :]:
            if any(option.startswith("\n") for option in piece):
                raise ValueError("patch normalization crosses a boundary")
            if all(option for option in piece):
                break

    # The user can apply any subset, so check the maximum expansion across all
    # selections, including raw CRLF bytes before BE normalizes the last edit.
    positive_bytes = sum(max(0, delta) for delta in normalized_deltas)
    max_bytes = (
        len(content.encode("utf-8"))
        + positive_bytes
        + max(
            raw - max(0, normalized)
            for raw, normalized in zip(raw_deltas, normalized_deltas, strict=True)
        )
    )
    if (
        max_bytes > MAX_DOCUMENT_BYTES
        or len(content) + sum(max(0, delta) for delta in codepoint_deltas)
        > MAX_DOCUMENT_CODEPOINTS
        or all(any(_blank(option) for option in piece) for piece in pieces)
    ):
        raise ValueError("patch produces an invalid document")

    # BE strips a leading BOM when saving. Reject alternatives that expose one:
    # otherwise a later patch could be checked against text BE silently removed.
    may_be_empty = True
    for piece in pieces:
        if may_be_empty and any(option.startswith("\ufeff") for option in piece):
            raise ValueError("patch exposes a leading BOM")
        may_be_empty = may_be_empty and any(not option for option in piece)

    work = 0
    for target_index, (_, _, target, _) in enumerate(ordered):
        table = _prefix_table(target)
        # (length of matching prefix, number of complete occurrences so far).
        states = {(0, 0)}
        for piece_index, piece in enumerate(pieces):
            # Keep this target unapplied while considering every other subset.
            options = (target,) if piece_index == target_index * 2 + 1 else piece
            next_states = set()
            for matched, count in states:
                for option in options:
                    work += len(option)
                    if work > MAX_MATCH_WORK:
                        raise ValueError("patch sequence is too complex to verify")
                    prefix, occurrences = matched, count
                    for char in option:
                        while prefix and char != target[prefix]:
                            work += 1
                            if work > MAX_MATCH_WORK:
                                raise ValueError(
                                    "patch sequence is too complex to verify"
                                )
                            prefix = table[prefix - 1]
                        if char == target[prefix]:
                            prefix += 1
                        if prefix == len(target):
                            occurrences += 1
                            if occurrences > 1:
                                raise ValueError("patch original becomes ambiguous")
                            prefix = table[prefix - 1]
                    next_states.add((prefix, occurrences))
            states = next_states
        if any(count != 1 for _, count in states):
            raise ValueError("patch original is no longer unique")
