"""Isolated same-database CDC boundary test; never starts a production worker."""
import os
import pathlib
import subprocess
import sys
import time
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
BACKEND = HERE.parents[2] / "backend"
COMPOSE = ["docker", "compose", "-f", str(HERE / "compose.yaml")]

def main():
    existing = subprocess.run(COMPOSE + ["ps", "--all", "--quiet"], capture_output=True, text=True, check=True)
    if existing.stdout.strip():
        raise RuntimeError("Boundary test project already exists; refusing to alter it")
    started = False
    try:
        started = True
        subprocess.run(COMPOSE + ["up", "-d"], check=True, timeout=180)
        deadline = time.monotonic() + 120
        while True:
            try:
                urllib.request.urlopen("http://127.0.0.1:18085/", timeout=2).close()
                break
            except OSError:
                if time.monotonic() > deadline:
                    raise RuntimeError("Connect did not start")
                time.sleep(1)
        env = os.environ.copy()
        env["BOUNDARY_TEST_SOURCE"] = str(HERE / "java")
        return subprocess.run(["cmd.exe", "/c", "gradlew.bat", "integrationTest", "--tests", "*PublicationBoundaryLiveIntegrationTest", "-I", str(HERE / "e2e-init.gradle")], cwd=BACKEND, env=env, timeout=300).returncode
    finally:
        if started:
            # This specific empty test project only; no -v and no other projects.
            subprocess.run(COMPOSE + ["down"], timeout=90)

if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print("Boundary E2E failed:", error, file=sys.stderr)
        sys.exit(1)
