from app.schemas.documents import SourceLocator


class SourceParsingError(ValueError):
    """Whole-document failure. No partial SourceDocument is returned."""

    def __init__(
        self, code: str, message: str, locator: SourceLocator | None = None
    ) -> None:
        super().__init__(message)
        self.code = code
        self.locator = locator
