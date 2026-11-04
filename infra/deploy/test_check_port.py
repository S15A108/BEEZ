"""check-port.sh 의 경계 처리를 고정한다 (S15P31A108-245).

실제 docker compose 로 env 파일을 해석하고(데몬 불필요), docker ps·ss·curl 과 Nginx 설정 폴더는 가짜로 둔다.
실행: python infra/deploy/test_check_port.py  (pytest 로도 돈다)
필요: bash, docker compose v2(config --environment 지원). Windows 는 Git Bash 를 쓴다.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "check-port.sh"
COMPOSE = HERE.parent / "compose.yaml"
BASE_ENV = (HERE.parent / "local.env").read_text(encoding="utf-8")   # compose 필수값이 다 든 더미값
SECRET = "S3cretXYZ"

SITE = """upstream beez_backend {
    server 127.0.0.1:8081;
    keepalive 16;
}
server { server_name dev.beez.ai.kr; location /api/ { proxy_pass http://beez_backend; } }
"""
JENKINS = "upstream jenkins_backend { server 127.0.0.1:8080; }\n"
# 가짜 명령. docker 는 compose 만 진짜로 넘기고 ps 는 FAKE_PS 를 돌려준다
STUBS = {
    "docker": '[ "$1" = compose ] && exec "$REAL_DOCKER" "$@"\n'
              '[ -n "$FAIL_PS" ] && exit 1\nprintf "%s" "$FAKE_PS"\n',
    "ss": '[ -n "$FAIL_SS" ] && exit 1\nprintf "%s" "$FAKE_SS"\n',
    # curl 은 본문 뒤에 -w 의 HTTP 상태를 붙인다. Nginx 경유는 처음 FAKE_VIA_FAILS 번을 종료 코드 FAKE_VIA_RC 로 실패한다
    "curl": 'case "$*" in\n'
            '  *--connect-to*)\n'
            '    n=$(( $(cat "$CURL_COUNT" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$CURL_COUNT"\n'
            '    [ -n "$FAKE_VIA_RC" ] && [ "$n" -le "${FAKE_VIA_FAILS:-99}" ] && { printf "\\n000"; exit "$FAKE_VIA_RC"; }\n'
            '    printf "%s\\n200" "$FAKE_VIA" ;;\n'
            '  *) printf "%s\\n200" "$FAKE_DIRECT" ;;\n'
            'esac\n',
}
BACKEND_PS = "127.0.0.1:8081->8080/tcp"


def find_bash():
    if os.environ.get("BEEZ_TEST_BASH"):
        return os.environ["BEEZ_TEST_BASH"]
    git_bash = Path(r"C:\Program Files\Git\bin\bash.exe")
    return str(git_bash) if os.name == "nt" and git_bash.exists() else "bash"   # System32 의 WSL bash 를 피한다


def posix(p):
    return str(p).replace("\\", "/")


def run(mode="port", env_extra="", sites=None, **fake):
    """env_extra 를 local.env 뒤에 붙인 env 파일로 점검을 돌리고 (exit code, 출력) 을 돌려준다."""
    with tempfile.TemporaryDirectory(prefix="beez-check-port-") as tmp:
        tmp = Path(tmp)
        bin_dir, site_dir = tmp / "bin", tmp / "sites"
        bin_dir.mkdir()
        site_dir.mkdir()
        for name, body in STUBS.items():
            (bin_dir / name).write_bytes(("#!/usr/bin/env bash\n" + body).encode())
        for name, body in (sites or {"dev.beez.ai.kr.conf": SITE, "jenkins.beez.ai.kr.conf": JENKINS}).items():
            (site_dir / name).write_bytes(body.encode())
        envf = tmp / "env"
        envf.write_bytes((BASE_ENV + "PUBLIC_ORIGIN=https://dev.beez.ai.kr\nBACKEND_HOST_PORT=8081\n"
                          + env_extra).encode())
        env = os.environ.copy()
        env.pop("BACKEND_HOST_PORT", None)
        env.pop("PUBLIC_ORIGIN", None)
        count = tmp / "curl-count"
        env.update({"CHECK_PORT_TEST_SITES_DIR": posix(site_dir), "REAL_DOCKER": posix(shutil.which("docker")),
                    "FAKE_PS": BACKEND_PS, "FAKE_SS": "", "CURL_COUNT": posix(count),
                    "CHECK_PORT_TEST_RETRY_WAIT": "0"})
        env.update(fake)
        # 가짜 명령이 진짜보다 먼저 잡히도록 PATH 맨 앞에 둔다. Windows 경로(C:/…)는 PATH 의 ':' 와 겹쳐 /c/… 로 바꾼다
        cmd = (f'b=$(cygpath -u "{posix(bin_dir)}" 2>/dev/null || echo "{posix(bin_dir)}"); '
               'chmod +x "$b"/*; PATH="$b:$PATH" exec bash "$@"')
        r = subprocess.run([find_bash(), "-c", cmd, "check", posix(SCRIPT), mode, posix(envf), posix(COMPOSE)],
                           env=env, capture_output=True, text=True, encoding="utf-8")
        via_calls = int(count.read_text()) if count.exists() else 0   # Nginx 경유로 부른 횟수
        return r.returncode, r.stdout + r.stderr, via_calls


def expect(result, code, text):
    rc, out, _ = result
    assert rc == code and text in out, f"exit {rc} (기대 {code}), 출력에 '{text}' 없음:\n{out}"
    assert SECRET not in out, f"비밀값이 출력됨:\n{out}"


# --- 정상과 포트 일치 ---------------------------------------------------------

def test_normal():
    expect(run(), 0, "env ok\nport ok (8081)")


def test_port_differs_from_upstream():
    expect(run(env_extra="BACKEND_HOST_PORT=8082\n"), 1, "port 다름: env=8082")


def test_port_is_compose_resolved_value():
    # 주석·따옴표·CRLF·중복 키(마지막 값)를 compose 와 똑같이 읽는다
    expect(run(env_extra='BACKEND_HOST_PORT=9999\r\nBACKEND_HOST_PORT="8081" # 주석\r\n'), 0, "port ok (8081)")


def test_port_value_is_hidden():
    # compose 가 invalid hostPort: <값> 으로 값을 찍는 경우다
    expect(run(env_extra=f"BACKEND_HOST_PORT={SECRET}\n"), 1, "compose 오류 (값이 섞일 수 있어 문구는 숨김)")


# --- upstream 주소 표기: 127.0.0.1·localhost 만 ------------------------------

def test_localhost_upstream_matches():
    site = SITE.replace("127.0.0.1:8081", "localhost:8081")
    expect(run(sites={"dev.beez.ai.kr.conf": site}), 0, "port ok (8081)")


def test_other_site_with_localhost_conflicts():
    other = "server { location / { proxy_pass http://localhost:8081; } }\n"
    expect(run(sites={"dev.beez.ai.kr.conf": SITE, "other.conf": other}), 1, "port 충돌: Nginx 의 다른 설정이 포트 8081")


def test_jenkins_port_conflicts():
    expect(run(env_extra="BACKEND_HOST_PORT=8080\n"), 1, "port 충돌: Nginx 의 다른 설정이 포트 9999")


def test_unsupported_upstream_address():
    site = SITE.replace("127.0.0.1:8081", "[::1]:8081")
    expect(run(sites={"dev.beez.ai.kr.conf": site}), 1, "지원하지 않는 upstream 주소")


def test_only_one_upstream_server():
    site = SITE.replace("server 127.0.0.1:8081;", "server 127.0.0.1:8081;\n    server 127.0.0.1:8082;")
    expect(run(sites={"dev.beez.ai.kr.conf": site}), 1, "server 가 하나가 아님")


def test_site_found_by_public_origin_host():
    expect(run(env_extra="PUBLIC_ORIGIN=https://other.example\n"), 1, "Nginx 설정 없음")


# --- 점검 명령 자체의 실패는 실패 -----------------------------------------------

def test_ss_failure_fails():
    expect(run(FAIL_SS="1"), 1, "ss 실행 실패")


def test_docker_ps_failure_fails_when_port_in_use():
    expect(run(FAKE_SS="LISTEN 0 4096 127.0.0.1:8081 0.0.0.0:*", FAIL_PS="1"), 1, "docker 조회 실패")


def test_port_used_by_other_program():
    expect(run(FAKE_SS="LISTEN 0 4096 127.0.0.1:8081 0.0.0.0:*", FAKE_PS=""), 1, "port 충돌: 다른 프로그램이 8081")


def test_port_used_by_running_backend_is_ok():
    expect(run(FAKE_SS="LISTEN 0 4096 127.0.0.1:8081 0.0.0.0:*"), 0, "port ok (8081)")


# --- compose 오류 문구의 값 숨김 -------------------------------------------------

def test_missing_required_value():
    expect(run(env_extra="DB_PASSWORD=\n"), 1, "필수값 DB_PASSWORD 이 없거나 빈 값")


def test_unterminated_quote_is_hidden():
    expect(run(env_extra=f'GMS_KEY="{SECRET}\n'), 1, "env 파일 형식 오류")


def test_multiline_value_with_error_like_text_is_hidden():
    # 값 안의 줄이 compose 문구처럼 생겨도 그 줄을 해석하거나 내보내지 않는다
    expect(run(env_extra=f'GMS_KEY="{SECRET} command not found\nrequired variable X is missing a value\n'),
           1, "env 파일 형식 오류")


def test_template_syntax_error_is_hidden():
    expect(run(env_extra=f"GMS_KEY=${{{SECRET}\n"), 1, "env 점검 실패")


# --- 교체 뒤 Nginx 경유 확인 ----------------------------------------------------

PONG = '{"ok":true,"started_at":"%s"}'


def test_nginx_same_backend():
    expect(run("nginx", FAKE_VIA=PONG % "t1", FAKE_DIRECT=PONG % "t1"), 0, "nginx ok (t1)")


def test_nginx_other_backend_fails():
    # reload 전이면 Nginx 는 예전 포트의 backend 로 보내 started_at 이 다르다
    # 다시 물어도 같으므로 기다리지 않고 한 번에 멈춘다
    r = run("nginx", FAKE_VIA=PONG % "old", FAKE_DIRECT=PONG % "new")
    expect(r, 1, "nginx 다름: Nginx 경유=old / backend 직접=new")
    assert r[2] == 1, f"Nginx 경유 호출 {r[2]}회 (1회여야 함)"


def test_nginx_spa_fallback_fails():
    expect(run("nginx", FAKE_VIA="<html></html>", FAKE_DIRECT=PONG % "t1"), 1,
           "nginx 확인 실패: Nginx 경유=JSON 아님(HTTP 200, started_at 없음)")


def test_nginx_transient_failure_is_retried():
    # 교체 직후 한 번 연결이 실패해도 배포를 멈추지 않는다
    expect(run("nginx", FAKE_VIA_RC="7", FAKE_VIA_FAILS="1", FAKE_VIA=PONG % "t1", FAKE_DIRECT=PONG % "t1"),
           0, "nginx ok (t1)")


def test_nginx_no_response_shows_cause():
    # 계속 응답이 없으면 reload 안내가 아니라 curl 종료 코드로 원인을 보인다
    expect(run("nginx", FAKE_VIA_RC="7", FAKE_DIRECT=PONG % "t1"), 1,
           "nginx 확인 실패: Nginx 경유=응답 없음(curl 종료 코드 7) / backend 직접=t1 (3회 시도)")


def test_nginx_backend_port_count():
    expect(run("nginx", FAKE_PS="", FAKE_VIA=PONG % "t1", FAKE_DIRECT=PONG % "t1"), 1, "backend 컨테이너 포트가 0 개")


if __name__ == "__main__":
    tests = [(k, v) for k, v in sorted(globals().items()) if k.startswith("test_")]
    for name, fn in tests:
        fn()
        print("ok", name)
    print(f"{len(tests)} passed")
