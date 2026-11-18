"""Production intake-schema CDC/replay acceptance on disposable local resources."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
COMPOSE = ["docker", "compose", "-p", "beez-cdc-recovery-229-test", "-f", str(HERE / "compose.yaml")]


def provenance():
    jar = ROOT / "backend/build/libs/beez-backend-0.0.1-SNAPSHOT.jar"
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT,
                              capture_output=True, text=True, check=True).stdout.strip()
    dirty = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT,
                           capture_output=True, text=True, check=True).stdout.strip()
    return {"base_revision": revision, "worktree_has_changes": bool(dirty),
            "application_jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest()}


def gradle(arguments, env=None, timeout=480):
    # A timed-out cmd.exe must not leave its Gradle/JVM/application children behind.
    # --no-daemon keeps this invocation's JVM tree separate from shared Gradle daemons.
    process = subprocess.Popen(["cmd.exe", "/c", "gradlew.bat", "--no-daemon", *arguments],
                               cwd=ROOT / "backend", env=env,
                               creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
    try:
        return process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        if process.poll() is None:
            subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False, timeout=30)
            process.wait(timeout=30)
        raise


def record_result(returncode, started_at):
    report = ROOT / "backend/build/test-results/integrationTest/TEST-com.beez.backend.impactanalysis.PublishedAnalysisCapture229IntegrationTest.xml"
    evidence = {"finished_at": datetime.now(timezone.utc).isoformat(), "returncode": returncode,
                "provenance": provenance(),
                "passed": False, "fresh_report": False, "checks": []}
    if report.is_file() and report.stat().st_mtime >= started_at:
        suite = ET.parse(report).getroot()
        evidence["fresh_report"] = True
        evidence["tests"] = {key: int(suite.attrib.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
        evidence["checks"] = [line for line in (suite.findtext("system-out") or "").splitlines() if line.startswith("CDC229 ")]
        evidence["passed"] = (returncode == 0 and evidence["tests"]["tests"] > 0
                              and all(evidence["tests"][key] == 0 for key in ("failures", "errors", "skipped")))
    (HERE / "evidence").mkdir(exist_ok=True)
    (HERE / "evidence/results.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    return evidence["passed"]


def run():
    existing = subprocess.run(COMPOSE + ["ps", "--all", "--quiet"], capture_output=True, text=True, check=True)
    if existing.stdout.strip():
        raise RuntimeError("The dedicated #229 project already exists; refusing to change it")
    # The crash/restart case must run the application built from this checkout.
    if gradle(["bootJar", "--console=plain"], timeout=240):
        raise RuntimeError("Could not build this checkout's application JAR")
    started = False
    try:
        started = True
        subprocess.run(COMPOSE + ["up", "-d"], check=True, timeout=240)
        deadline = time.monotonic() + 180
        while True:
            try:
                with urllib.request.urlopen("http://127.0.0.1:18329/", timeout=2):
                    break
            except OSError:
                if time.monotonic() > deadline:
                    raise RuntimeError("Isolated Connect did not start")
                time.sleep(1)
        env = os.environ.copy()
        env["BEEZ_229_TEST_SOURCE"] = str(HERE / "java")
        test_started = time.time()
        result = gradle(
            ["integrationTest", "--tests",
             "*PublishedAnalysisCapture229IntegrationTest", "-I", str(HERE / "e2e-init.gradle"),
             "--console=plain", "--rerun-tasks"], env=env, timeout=480)
        passed = record_result(result, test_started)
        if not passed:
            subprocess.run(COMPOSE + ["logs", "--tail", "60", "connect"], check=False)
        return 0 if passed else 1
    finally:
        if started:
            subprocess.run(COMPOSE + ["down"], check=True, timeout=90)


def main():
    evidence_path = HERE / "evidence/results.json"
    evidence_path.parent.mkdir(exist_ok=True)
    started_at = datetime.now(timezone.utc).isoformat()
    evidence_path.write_text(json.dumps({"started_at": started_at, "passed": False,
                                        "status": "running", "fresh_report": False}, indent=2) + "\n",
                             encoding="utf-8")
    try:
        return run()
    except Exception as failure:
        # Startup, deadline and cleanup failures must invalidate an earlier passing run.
        # Store the category only: exception text can contain command/environment details.
        evidence_path.write_text(json.dumps({"started_at": started_at,
                                            "finished_at": datetime.now(timezone.utc).isoformat(),
                                            "passed": False, "status": "failed",
                                            "error_type": type(failure).__name__,
                                            "fresh_report": False}, indent=2) + "\n", encoding="utf-8")
        raise


if __name__ == "__main__":
    sys.exit(main())
