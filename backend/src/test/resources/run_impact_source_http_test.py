"""Run the BE -> real FastAPI source round trip without Docker or external LLM calls."""

import os
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

backend = Path(__file__).resolve().parents[3]
ai = backend.parent / "ai"
fixture = backend / "src/test/resources/impact_e2e_fastapi.py"
token = secrets.token_urlsafe(32)
nonce = secrets.token_urlsafe(32)
with socket.socket() as probe:
    probe.bind(("127.0.0.1", 0))
    probe.listen(1)
    port = probe.getsockname()[1]

env = {
    key: value
    for key, value in os.environ.items()
    if not key.upper().startswith(("GMS_", "OPENAI_", "IMPACT_", "BEEZ_IMPACT_"))
    and key.upper()
    not in {"AI_INTERNAL_TOKEN", "BEE_DEFAULT_MODEL", "BEE_COMPLEX_MODEL"}
}
env.update(
    PYTHONPATH=str(ai),
    BEEZ_IMPACT_E2E_INTERNAL_TOKEN=token,
    BEEZ_IMPACT_E2E_READINESS_NONCE=nonce,
    BEEZ_IMPACT_E2E_REAL_GMS="false",
)
server = subprocess.Popen(
    [sys.executable, "-u", str(fixture), str(port)],
    cwd=ai,
    env=env,
    stdout=subprocess.DEVNULL,
    stderr=subprocess.DEVNULL,
)
try:
    ready = False
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if server.poll() is not None:
            break
        try:
            with opener.open(
                f"http://127.0.0.1:{port}/__e2e_ready", timeout=0.5
            ) as response:
                ready = (
                    response.read().decode("utf-8") == nonce and server.poll() is None
                )
            if ready:
                break
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(0.1)
    if not ready:
        raise RuntimeError("Isolated AI test server did not start")
    env.update(
        BEEZ_RUN_IMPACT_HTTP_TEST="true",
        BEEZ_IMPACT_HTTP_URL=f"http://127.0.0.1:{port}",
        BEEZ_IMPACT_HTTP_TOKEN=token,
    )
    if os.name == "nt":
        command = ["cmd.exe", "/d", "/c", "gradlew.bat"]
    else:
        command = ["./gradlew"]
    command.extend(
        [
            "--no-daemon",
            "test",
            "--tests",
            "*ImpactSourceFastApiRoundTripTest",
            "--rerun-tasks",
        ]
    )
    raise SystemExit(
        subprocess.run(command, cwd=backend, env=env, check=False).returncode
    )
finally:
    server.terminate()
    try:
        server.wait(timeout=5)
    except subprocess.TimeoutExpired:
        server.kill()
        server.wait(timeout=5)
