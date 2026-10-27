"""Read the pinned public vocabulary locally, without network repair or writes."""

import base64
import hashlib
import os
import tempfile
from pathlib import Path

VOCABULARY_URL = (
    "https://openaipublic.blob.core.windows.net/encodings/cl100k_base.tiktoken"
)
VOCABULARY_HASH = "223921b76ee99bde995b7ff738513eef100fb51d18c93597a113bcffe865b2a7"


def load_vocabulary() -> dict[bytes, int]:
    # Match tiktoken 0.14.0's cache layout so the explicit preparation command
    # can use the vendor downloader and its hash verification.
    directory = os.environ.get(
        "TIKTOKEN_CACHE_DIR",
        os.environ.get(
            "DATA_GYM_CACHE_DIR", str(Path(tempfile.gettempdir()) / "data-gym-cache")
        ),
    )
    if not directory:
        raise ValueError("Tokenizer vocabulary cache must be enabled.")
    path = Path(directory) / hashlib.sha1(VOCABULARY_URL.encode()).hexdigest()
    try:
        contents = path.read_bytes()
    except OSError as exc:
        raise ValueError(
            "Tokenizer vocabulary is unavailable; run python -m scripts.prepare_tokenizer."
        ) from exc
    if hashlib.sha256(contents).hexdigest() != VOCABULARY_HASH:
        raise ValueError(
            "Tokenizer vocabulary is corrupt; run python -m scripts.prepare_tokenizer."
        )
    return {
        base64.b64decode(token): int(rank)
        for token, rank in (line.split() for line in contents.splitlines() if line)
    }
