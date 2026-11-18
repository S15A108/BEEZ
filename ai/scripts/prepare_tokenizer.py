"""Explicit build/setup step; downloads public vocabulary, never calls a model."""

import tiktoken

from app.services.retrieval.tokenizer_vocabulary import load_vocabulary


def main() -> None:
    tiktoken.get_encoding("cl100k_base")
    load_vocabulary()
    print("Verified cl100k_base vocabulary is ready for offline runtime.")


if __name__ == "__main__":
    main()
