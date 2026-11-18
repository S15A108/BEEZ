"""The streamed review request must still have a usable OpenAPI schema."""

from app.main import app


def test_document_review_request_schema_has_resolvable_references():
    document = app.openapi()
    operation = document["paths"]["/v1/document-reviews"]["post"]
    assert operation["requestBody"]["required"] is True
    schema = operation["requestBody"]["content"]["application/json"]["schema"]

    assert {"content", "content_hash", "reference_hash", "references"} <= set(
        schema["required"]
    )
    reference = schema["properties"]["references"]["items"]
    assert reference["properties"]["status"]["const"] == "APPROVED"
    assert {"reference_id", "content", "content_hash"} <= set(reference["required"])

    def check_references(value):
        if isinstance(value, list):
            for item in value:
                check_references(item)
        elif isinstance(value, dict):
            if "$ref" in value:
                ref = value["$ref"]
                assert ref.startswith("#/")
                target = document
                for part in ref[2:].split("/"):
                    target = target[part.replace("~1", "/").replace("~0", "~")]
                assert isinstance(target, dict)
            for item in value.values():
                check_references(item)

    check_references(schema)
