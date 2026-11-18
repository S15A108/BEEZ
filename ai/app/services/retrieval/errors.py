class RetrievalError(ValueError):
    """A complete artifact could not be prepared; no partial batch is returned."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
