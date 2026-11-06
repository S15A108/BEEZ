"""Check endpoint interpolation through Compose without starting services."""

import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest

from app.core.config import Settings

COMPOSE = Path(__file__).resolve().parents[2] / "infra" / "compose.yaml"
OVERRIDE = "https://gms.ssafy.io/custom/v1/chat/completions"


@pytest.mark.parametrize("configured", [None, "", OVERRIDE])
def test_compose_passes_configured_endpoint_or_the_application_default(
    tmp_path, configured
):
    docker = shutil.which("docker")
    if not docker:
        pytest.skip("Docker Compose CLI is not installed")
    if subprocess.run(
        [docker, "compose", "version"], capture_output=True, check=False
    ).returncode:
        pytest.skip("Docker Compose plugin is not installed")
    env = os.environ.copy()
    env.update(
        DB_USERNAME="test",
        DB_PASSWORD="test",
        DOCUMENT_S3_ACCESS_KEY="test",
        DOCUMENT_S3_SECRET_KEY="test",
        SEAWEEDFS_BOOTSTRAP_ACCESS_KEY="test",
        SEAWEEDFS_BOOTSTRAP_SECRET_KEY="test",
        SEAWEEDFS_BACKUP_ACCESS_KEY="test",
        SEAWEEDFS_BACKUP_SECRET_KEY="test",
        AI_INTERNAL_TOKEN="test",
        GMS_KEY="test",
        PUBLIC_ORIGIN="https://example.test",
        # No default in compose.yaml since S15P31A108-27: config fails without it.
        BACKEND_HOST_PORT="8081",
    )
    env.pop("GMS_CHAT_ENDPOINT", None)
    if configured is not None:
        env["GMS_CHAT_ENDPOINT"] = configured
    # Do not depend on a developer's deployment env file or contact a daemon.
    empty_env = tmp_path / "empty.env"
    empty_env.write_text("", encoding="utf-8")
    completed = subprocess.run(
        [
            docker,
            "compose",
            "--env-file",
            str(empty_env),
            "-f",
            str(COMPOSE),
            "config",
            "--format",
            "json",
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        env=env,
        check=True,
    )
    endpoint = json.loads(completed.stdout)["services"]["ai"]["environment"][
        "GMS_CHAT_ENDPOINT"
    ]
    assert endpoint == (
        configured or Settings.model_fields["gms_chat_endpoint"].default
    )
