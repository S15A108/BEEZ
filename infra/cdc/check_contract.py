"""Check review-only connector/publication column consistency; not a security audit."""

import json
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCHEMA = HERE.parents[1] / "backend/src/main/resources/db/migration/V2__init_schema.sql"


def columns(text: str, pattern: str) -> set[str]:
    match = re.search(pattern, text, flags=re.IGNORECASE | re.DOTALL)
    if match is None:
        raise ValueError(f"could not find column list: {pattern}")
    return {name.strip().lower() for name in match.group(1).split(",")}


def check() -> None:
    config = json.loads((HERE / "connector.review.json").read_text(encoding="utf-8"))[
        "config"
    ]
    publication = (HERE / "publication.review.sql").read_text(encoding="utf-8")
    schema = SCHEMA.read_text(encoding="utf-8")
    excluded = {
        "title",
        "domain",
        "created_by",
        "approved_by",
        "content_text",
        "content_hash",
        "storage_key",
        "parse_error",
    }
    patterns = config["column.include.list"].split(",")
    for table in ("document", "document_version"):
        capture = columns(publication, rf"public\.{table}\s*\(([^)]*)\)")
        snapshot = columns(
            config[f"snapshot.select.statement.overrides.public.{table}"],
            r"SELECT\s+(.+?)\s+FROM",
        )
        match = re.search(rf"CREATE TABLE {table}\s*\((.*?)\n\);", schema, re.DOTALL)
        if match is None:
            raise ValueError(f"missing {table} in project schema")
        actual_columns = {
            m.group(1)
            for line in match.group(1).splitlines()
            if (
                m := re.match(
                    r"\s*([a-z][a-z_0-9]*)\s+(?:BIGINT|INT|TEXT|TIMESTAMPTZ)\b", line
                )
            )
        }
        selected = {
            name
            for name in actual_columns
            if any(re.fullmatch(p, f"public.{table}.{name}") for p in patterns)
        }
        if capture != snapshot or capture != selected or not capture <= actual_columns:
            raise ValueError(f"publication/snapshot/connector mismatch for {table}")
        if "id" not in capture or capture & excluded:
            raise ValueError(f"missing primary key or disallowed column in {table}")
    if (
        config.get("snapshot.mode") != "initial"
        or config.get("publication.autocreate.mode") != "disabled"
    ):
        raise ValueError("snapshot/publication mode is not the reviewed configuration")
    print(
        "Review template column lists and project schema agree; privileges and runtime are not verified."
    )


if __name__ == "__main__":
    check()
