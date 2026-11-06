"""deploy-notify.sh(배포 실패 알림)의 약속을 고정한다 (S15P31A108-265).

약속: 메시지는 올바른 JSON 이고, 웹훅 주소는 어디에도 찍히지 않으며, 알림이 실패해도 항상 exit 0 이다.
curl 은 가짜로 둔다. 가짜는 진짜 curl 처럼 -K 설정 파일에서 주소를 읽고, 실패하면 그 주소를 stderr 에 찍는다
(진짜 curl 의 오류 문구에 주소가 섞일 수 있는 경우를 흉내 낸다).
실행: python infra/jenkins/test_deploy_notify.py  (pytest 로도 돈다. CI 는 deploy-scripts 잡)
필요: bash. Windows 는 Git Bash 를 쓴다.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "deploy-notify.sh"
WEBHOOK = "https://meeting.example.invalid/hooks/S3cretHookId123"   # 가짜 설정 파일의 값
SHA = "5bf2381223f0a8afcdf49815a60ebaa2e1319bcd"

# 가짜 curl. 받은 인자는 ARGS_FILE 에, 본문(stdin)은 BODY_FILE 에 남긴다.
# -K 파일의 url 을 읽어 없으면 진짜처럼 종료 코드 2, FAKE_RC 가 있으면 주소를 stderr 에 찍고 그 코드로 끝난다
CURL = r'''#!/usr/bin/env bash
printf '%s\n' "$@" > "$ARGS_FILE"
cat > "$BODY_FILE"
conf=
while [ $# -gt 0 ]; do [ "$1" = -K ] && conf=$2; shift; done
url=$(sed -n 's/^url = "\(.*\)"$/\1/p' "$conf")
if [ -z "$url" ]; then echo "curl: no URL specified" >&2; exit 2; fi
if [ -n "$FAKE_RC" ]; then echo "curl: (6) Could not resolve host for $url" >&2; exit "$FAKE_RC"; fi
printf '%s' "${FAKE_HTTP:-200}"
'''


def find_bash():
    if os.environ.get("BEEZ_TEST_BASH"):
        return os.environ["BEEZ_TEST_BASH"]
    git_bash = Path(r"C:\Program Files\Git\bin\bash.exe")
    return str(git_bash) if os.name == "nt" and git_bash.exists() else "bash"   # System32 의 WSL bash 를 피한다


def posix(p):
    return str(p).replace("\\", "/")


def run(conf=f'url = "{WEBHOOK}"\n', **env_vars):
    """알림 스크립트를 돌려 (exit code, stdout+stderr, curl 인자 목록 또는 None, 보낸 본문 또는 None) 을 돌려준다.

    conf=None 이면 설정 파일을 만들지 않는다. env_vars 에 없는 Jenkins 값은 비워 둔다.
    """
    with tempfile.TemporaryDirectory(prefix="beez-deploy-notify-") as tmp:
        tmp = Path(tmp)
        bin_dir = tmp / "bin"
        bin_dir.mkdir()
        (bin_dir / "curl").write_bytes(CURL.encode())
        conf_path = tmp / "deploy-notify.curl"
        if conf is not None:
            conf_path.write_bytes(conf.encode())
        args_file, body_file = tmp / "args", tmp / "body"
        env = os.environ.copy()
        for name in ("GIT_SHA", "BUILD_NUMBER", "DEPLOY_ENV", "FAILED_STAGE", "BUILD_URL", "FAKE_RC", "FAKE_HTTP"):
            env.pop(name, None)
        env.update({"DEPLOY_NOTIFY_CONF": posix(conf_path), "ARGS_FILE": posix(args_file),
                    "BODY_FILE": posix(body_file)})
        env.update(env_vars)
        # 가짜 curl 이 진짜보다 먼저 잡히도록 PATH 맨 앞에 둔다. Windows 경로(C:/…)는 PATH 의 ':' 와 겹쳐 /c/… 로 바꾼다
        cmd = (f'b=$(cygpath -u "{posix(bin_dir)}" 2>/dev/null || echo "{posix(bin_dir)}"); '
               'chmod +x "$b"/*; PATH="$b:$PATH" exec bash "$@"')
        r = subprocess.run([find_bash(), "-c", cmd, "notify", posix(SCRIPT)],
                           env=env, capture_output=True, text=True, encoding="utf-8")
        args = args_file.read_text(encoding="utf-8").splitlines() if args_file.exists() else None
        body = body_file.read_text(encoding="utf-8") if body_file.exists() else None
        return r.returncode, r.stdout + r.stderr, args, body


def expect(result, text):
    """exit 0, 안내 문구, 웹훅 주소 비노출은 모든 경우에 지켜야 한다."""
    rc, out, args, _ = result
    assert rc == 0, f"exit {rc} (알림 실패가 배포 결과를 바꾸면 안 됨):\n{out}"
    assert text in out, f"출력에 '{text}' 없음:\n{out}"
    assert WEBHOOK not in out, f"웹훅 주소가 출력됨:\n{out}"
    assert args is None or WEBHOOK not in "\n".join(args), f"웹훅 주소가 curl 명령줄에 있음(프로세스 목록에 보임):\n{args}"


def message(result):
    body = result[3]
    assert body is not None, "curl 을 부르지 않음"
    return json.loads(body)["text"]   # 올바른 JSON 이 아니면 여기서 실패한다


FULL = {"GIT_SHA": SHA, "BUILD_NUMBER": "43", "DEPLOY_ENV": "dev", "FAILED_STAGE": "Deploy",
        "BUILD_URL": "https://jenkins.beez.ai.kr/job/beez-deploy/43/"}


# --- 정상 ------------------------------------------------------------------------

# 깨지면: Mattermost 가 받는 메시지 형식이 바뀌었다(필드 누락·링크 깨짐). 팀이 알림만 보고 빌드를 찾지 못한다
def test_success_message_is_valid_json_with_all_fields():
    r = run(**FULL)
    expect(r, "배포 실패 알림 보냄")
    text = message(r)
    for part in ("**beez-deploy #43 실패**", "(dev, `5bf2381`, 단계 Deploy)",
                 "[콘솔 출력](https://jenkins.beez.ai.kr/job/beez-deploy/43/console)",
                 "[복구 안내](https://github.com/S15A108/BEEZ/blob/dev/docs/infra/operations.md#6-"):
        assert part in text, f"메시지에 '{part}' 없음:\n{text}"


# 깨지면: 주소를 명령줄로 넘기게 바뀌었다. 웹훅 주소가 프로세스 목록·Jenkins 콘솔(sh 의 + 출력)에 드러난다
def test_webhook_url_is_read_from_config_file_only():
    r = run(**FULL)
    expect(r, "배포 실패 알림 보냄")
    assert "-K" in r[2], f"설정 파일(-K)로 주소를 넘기지 않음: {r[2]}"


# --- 알림이 실패해도 exit 0, 주소는 숨김 ----------------------------------------------

# 깨지면: curl 의 오류 문구(주소가 섞임)가 콘솔에 나가거나, 알림 실패가 배포 결과를 바꾼다
def test_curl_failure_hides_url_and_exits_zero():
    expect(run(FAKE_RC="6", **FULL), "배포 실패 알림을 보내지 못함(HTTP 000, curl 종료 코드 6)")


# 깨지면: Mattermost 가 거절(웹훅 삭제 등)했는데도 "보냄"이라고 찍어 알림이 끊긴 것을 모른다
def test_http_error_is_reported():
    expect(run(FAKE_HTTP="404", **FULL), "배포 실패 알림을 보내지 못함(HTTP 999, curl 종료 코드 0)")


# 깨지면: 설정 파일이 없는 서버(새 서버·파일 권한 오류)에서 알림 단계가 배포 결과를 바꾸거나 이유 없이 조용하다
def test_missing_config_skips_without_calling_curl():
    r = run(conf=None, **FULL)
    expect(r, "알림 설정 파일을 읽을 수 없어 알림을 보내지 않음")
    assert r[2] is None, "설정 파일이 없는데 curl 을 부름"


# 깨지면: 설정 파일이 비었을 때(3번 절차 중단 등) 알림 단계가 실패로 끝나거나 원인 없이 조용하다
def test_empty_config_reports_curl_error():
    expect(run(conf="", **FULL), "배포 실패 알림을 보내지 못함(HTTP 000, curl 종료 코드 2)")


# --- 값 거르기 ---------------------------------------------------------------------

# 깨지면: Verify 전 실패처럼 Jenkins 값이 비었을 때 메시지 칸이 비거나 JSON 이 깨진다
def test_empty_values_become_question_mark():
    r = run(BUILD_NUMBER="", DEPLOY_ENV="", FAILED_STAGE="")
    expect(r, "배포 실패 알림 보냄")
    text = message(r)
    assert "**beez-deploy #? 실패** (?, `(SHA 형식 오류)`, 단계 ?)" in text, text
    assert "콘솔 출력" not in text, f"BUILD_URL 이 없는데 콘솔 링크가 들어감:\n{text}"


# 깨지면: 걸러서 비게 된 값을 ? 로 채우지 않는다(? 는 clean 이 지우는 문자라 순서가 바뀌면 빈 칸이 된다)
def test_values_emptied_by_filter_become_question_mark():
    text = message(run(BUILD_NUMBER='"', DEPLOY_ENV="?", FAILED_STAGE="\\"))
    assert "#? 실패** (?, `(SHA 형식 오류)`, 단계 ?)" in text, text


# 깨지면: 사람이 넣은 GIT_SHA 같은 값의 따옴표·역슬래시·줄바꿈이 JSON 을 깨 알림이 400 으로 안 간다
def test_hostile_values_keep_json_valid():
    r = run(GIT_SHA='x"}\n\\', BUILD_NUMBER="1", DEPLOY_ENV="dev", FAILED_STAGE='Ve"ri\\fy\n*',
            BUILD_URL='https://j/x"y/')
    expect(r, "배포 실패 알림 보냄")
    text = message(r)
    assert "`(SHA 형식 오류)`" in text and "단계 Verify)" in text, text
    assert "[콘솔 출력](https://j/xy/console)" in text, text


if __name__ == "__main__":
    tests = [(k, v) for k, v in sorted(globals().items()) if k.startswith("test_")]
    for name, fn in tests:
        fn()
        print("ok", name)
    print(f"{len(tests)} passed")
