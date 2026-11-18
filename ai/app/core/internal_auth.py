import secrets
from typing import Annotated

from fastapi import Depends, Header, HTTPException

from app.core.config import Settings, get_settings


def require_internal_token(
    settings: Annotated[Settings, Depends(get_settings)],
    authorization: Annotated[str | None, Header()] = None,
) -> None:
    expected = settings.ai_internal_token
    scheme, _, supplied = (authorization or "").partition(" ")
    if (
        expected is None
        or not expected.get_secret_value().strip()
        or scheme.lower() != "bearer"
        or not secrets.compare_digest(
            supplied.encode("utf-8"), expected.get_secret_value().encode("utf-8")
        )
    ):
        raise HTTPException(401, detail="invalid_internal_token")
