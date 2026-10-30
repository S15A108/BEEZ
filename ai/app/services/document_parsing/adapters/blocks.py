"""Adapter output shared by every supported source format."""

from dataclasses import dataclass

from app.schemas.documents import SourceContainer, SourceLocator


@dataclass(frozen=True)
class ParsedBlock:
    block_type: str
    locator: SourceLocator
    # Indices into preceding ParsedBlocks; a heading includes its own index.
    heading_path: tuple[int, ...]
    container_path: tuple[SourceContainer, ...]
    heading_level: int | None = None
    heading_text: str | None = None
    cells: tuple[str, ...] = ()
    table_header_index: int | None = None
    info: str | None = None
