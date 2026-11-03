"""OpenAPI helpers for routes that read their request body by hand.

A route that streams its body (size cap, strict JSON) declares the request
schema through `openapi_extra`. Pydantic emits shared models under `$defs`
with `#/$defs/...` references, which an OpenAPI document resolves against its
own root, where no such section exists, so Swagger and client generators fail
to read the schema. Inlining every reference gives a self-contained schema.
"""

from typing import Any

from pydantic import BaseModel


def _maps_to_defs(discriminator: Any) -> bool:
    mapping = discriminator.get("mapping") if isinstance(discriminator, dict) else None
    return isinstance(mapping, dict) and any(
        isinstance(target, str) and target.startswith("#/$defs/")
        for target in mapping.values()
    )


def inline_request_schema(model: type[BaseModel]) -> dict[str, Any]:
    """The model's JSON schema with every `$defs` reference expanded in place."""
    schema = model.model_json_schema()
    definitions = schema.pop("$defs", {})

    def inline(value: Any, path: tuple[str, ...]) -> Any:
        if isinstance(value, dict):
            ref = value.get("$ref")
            if isinstance(ref, str) and ref.startswith("#/$defs/"):
                name = ref.rsplit("/", 1)[-1]
                if name in path:
                    raise ValueError(f"recursive schema {name!r} cannot be inlined")
                expanded = inline(definitions[name], (*path, name))
                siblings = {k: inline(v, path) for k, v in value.items() if k != "$ref"}
                return {**expanded, **siblings}
            # A discriminator maps tag values to `$defs` references; once the
            # `oneOf` branches are inlined there is nothing left to map to,
            # and each branch still carries its `const` tag.
            if _maps_to_defs(value.get("discriminator")):
                value = {k: v for k, v in value.items() if k != "discriminator"}
            return {key: inline(item, path) for key, item in value.items()}
        if isinstance(value, list):
            return [inline(item, path) for item in value]
        return value

    return inline(schema, ())
