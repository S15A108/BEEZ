"""백업·복원 스크립트의 실측 함정을 고정한다 (S15P31A108-211).

- env 파일을 배포(compose)와 같은 규칙으로 읽는다: 따옴표·인라인 주석이 키 값에 섞이면 배포는 되는데 백업·대조·복원만 인증에 실패했다
- 문서 0건(빈 bucket)의 백업도 복원할 수 있다: rclone 은 빈 bucket 을 복사할 때 대상 폴더를 만들지 않아, 백업은 성공했는데 복원이 "원본 폴더 없음"으로 멈췄다
- 덤프가 가리키는 원본이 백업에 없으면 실패하고 LAST_OK 를 바꾸지 않는다

실제 docker compose 로 env 파일을 해석하고(데몬 불필요), 나머지 docker 명령(ps·exec·run)과 flock 은 가짜로 둔다.
컨테이너를 띄우는 복원 시험(restore-check.sh)은 여기서 돌리지 않는다 — docs/infra/seaweedfs.md 검증 기록.
실행: python infra/backup/test_backup_scripts.py  (pytest 로도 돈다)
필요: bash, docker compose v2(config --environment 지원). Windows 는 Git Bash 를 쓴다.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
BASE_ENV = (HERE.parent / "local.env").read_text(encoding="utf-8")   # compose 필수값이 다 든 더미값
SECRET = "S3cretXYZ"
BUCKET = "beez-documents"

# 가짜 docker. compose 만 진짜로 넘긴다.
#   exec: pg_dump 는 "DUMP", pg_restore --data-only 는 FAKE_COPY(덤프의 document_version COPY 블록), psql 은 빈 결과
#   run : rclone·curl 컨테이너 자리. 받은 키를 KEY_LOG 에 적고, backup.sh 의 rclone copy 면 FAKE_OBJECTS 의 key 를
#         -v 로 넘긴 폴더의 <bucket>/ 아래에 만든다(FAKE_OBJECTS 가 비면 진짜 rclone 처럼 폴더를 만들지 않는다)
DOCKER = r'''
case "$1" in
  compose) exec "$REAL_DOCKER" "$@" ;;
  ps) echo fakecontainer ;;
  stop|start) echo "$1" >> "$CALL_LOG" ;;
  inspect) echo healthy ;;
  exec)
    shift; [ "$1" = -i ] && { shift; cat > /dev/null; }; shift
    case "$*" in
      *pg_dump*) printf DUMP ;;
      *--data-only*) printf '%s' "$FAKE_COPY" ;;
    esac ;;
  run)
    echo run >> "$CALL_LOG"
    printf '%s|%s|%s\n' "${RCLONE_CONFIG_SRC_ACCESS_KEY_ID:-}" "${RCLONE_CONFIG_DST_ACCESS_KEY_ID:-}" "${AK:-}" >> "$KEY_LOG"
    host=; prev=
    for a in "$@"; do [ "$prev" = -v ] && host=${a%%:/backup*}; prev=$a; done
    case "$*" in
      *"copy src:"*)
        if [ -n "$FAKE_OBJECTS" ]; then
          mkdir -p "$host/$BUCKET_NAME"
          for k in $FAKE_OBJECTS; do echo x > "$host/$BUCKET_NAME/$k"; done
        fi ;;
      *curlimages*) cat > /dev/null; echo "  일치 0개, 문제 0개" ;;
      *" lsf "*) printf '%s' "$FAKE_BUCKET_LS" ;;   # restore.sh 사전 검증의 bucket 목록
    esac ;;
esac
'''
STUBS = {"docker": DOCKER, "flock": "exit 0\n"}


def copy_block(*keys):
    """pg_restore --data-only 가 내는 document_version COPY 블록. storage_key 를 셋째 열에 둔다(열 순서를 찾는지 본다)."""
    rows = "".join(f"{i}\t10{i}\t{k}\tsha256:00\n" for i, k in enumerate(keys, 1))
    return ("COPY public.document_version (id, document_id, storage_key, content_hash) FROM stdin;\n"
            + rows + "\\.\n")


def find_bash():
    if os.environ.get("BEEZ_TEST_BASH"):
        return os.environ["BEEZ_TEST_BASH"]
    git_bash = Path(r"C:\Program Files\Git\bin\bash.exe")
    return str(git_bash) if os.name == "nt" and git_bash.exists() else "bash"   # System32 의 WSL bash 를 피한다


def posix(p):
    return str(p).replace("\\", "/")


def run(script, *args, env_extra="", backup_root=None, **fake):
    """env_extra 를 local.env 뒤에 붙인 env 파일로 스크립트를 돌리고 (exit code, 출력, 받은 키 목록, 호출 목록, tmp) 를 돌려준다."""
    tmp = Path(tempfile.mkdtemp(prefix="beez-bk-test-"))
    bin_dir = tmp / "bin"
    bin_dir.mkdir()
    for name, body in STUBS.items():
        (bin_dir / name).write_bytes(("#!/usr/bin/env bash\n" + body).encode())
    envf = tmp / "env"
    envf.write_bytes((BASE_ENV + env_extra).encode())
    root = backup_root or tmp / "backup"
    lock = tmp / "deploy.lock"
    lock.write_bytes(b"")
    key_log, call_log = tmp / "keys", tmp / "calls"
    env = os.environ.copy()
    for k in list(env):
        if k.startswith(("SEAWEEDFS_", "DOCUMENT_S3_")):
            env.pop(k)   # 셸 환경변수는 compose 에서 env 파일보다 앞선다
    env.update({"REAL_DOCKER": posix(shutil.which("docker")), "ENV_FILE": posix(envf), "BACKUP_ROOT": posix(root),
                "DEPLOY_LOCK": posix(lock), "KEY_LOG": posix(key_log), "CALL_LOG": posix(call_log),
                "BUCKET_NAME": BUCKET, "FAKE_COPY": copy_block(), "FAKE_OBJECTS": "", "FAKE_BUCKET_LS": ""})
    env.update(fake)
    cmd = (f'b=$(cygpath -u "{posix(bin_dir)}" 2>/dev/null || echo "{posix(bin_dir)}"); '
           'chmod +x "$b"/*; PATH="$b:$PATH" exec bash "$@"')
    r = subprocess.run([find_bash(), "-c", cmd, "t", posix(HERE / script), *args],
                       env=env, capture_output=True, text=True, encoding="utf-8")
    keys = key_log.read_text(encoding="utf-8").splitlines() if key_log.exists() else []
    calls = call_log.read_text(encoding="utf-8").split() if call_log.exists() else []
    return r.returncode, r.stdout + r.stderr, keys, calls, tmp


def check(result, code, text):
    rc, out, *_ , tmp = result
    try:
        assert rc == code and text in out, f"exit {rc} (기대 {code}), 출력에 '{text}' 없음:\n{out}"
        assert SECRET not in out, f"비밀값이 출력됨:\n{out}"
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# --- env 파일 해석: compose 와 같게 -------------------------------------------

QUOTED = 'SEAWEEDFS_BACKUP_ACCESS_KEY="quotedkey1" # 백업 키\r\nSEAWEEDFS_BOOTSTRAP_ACCESS_KEY=\'quotedkey2\'\n'


def test_backup_reads_env_like_compose():
    r = run("backup.sh", "dev", env_extra=QUOTED)
    assert r[2] == ["quotedkey1||"], r[2]   # 따옴표·주석·CRLF 가 키에 섞이지 않는다
    check(r, 0, "완료")


def test_check_live_reads_env_like_compose():
    r = run("check-live.sh", "dev", env_extra=QUOTED)
    assert r[2] == ["||quotedkey1"], r[2]
    check(r, 0, "통과")


def test_restore_reads_env_like_compose():
    with tempfile.TemporaryDirectory() as root:
        (Path(root) / "objects" / BUCKET).mkdir(parents=True)
        (Path(root) / "objects" / BUCKET / "k1.txt").write_text("x")
        r = run("restore.sh", "dev", "objects", "--yes", env_extra=QUOTED, backup_root=Path(root))
        assert r[2] == ["|quotedkey2|"], r[2]
        check(r, 0, "완료")


def test_env_syntax_error_is_hidden():
    # compose 는 따옴표가 안 닫힌 값을 오류 문구에 그대로 찍는다
    for script, args in (("backup.sh", ["dev"]), ("check-live.sh", ["dev"]), ("restore.sh", ["dev", "objects"])):
        check(run(script, *args, env_extra=f'SEAWEEDFS_BACKUP_SECRET_KEY="{SECRET}\n'), 2 if script != "restore.sh" else 1,
              "compose 로 해석하지 못함")


# --- 문서 0건(빈 bucket) -------------------------------------------------------

def test_empty_bucket_backup_keeps_bucket_folder():
    r = run("backup.sh", "dev")
    root = r[4] / "backup"
    assert (root / "objects" / BUCKET).is_dir(), "빈 bucket 이어도 objects/<bucket> 이 있어야 복원 스크립트가 쓴다"
    assert (root / "LAST_OK").read_text().startswith("dump=beez-"), "성공이면 LAST_OK 를 쓴다"
    check(r, 0, "원본 0개")


def test_restore_objects_without_bucket_folder_is_zero_objects():
    # PC 사본·옛 백업에는 빈 폴더가 없다. 실패하지 않고 0개로 본다(올리기 컨테이너도 띄우지 않는다)
    with tempfile.TemporaryDirectory() as root:
        (Path(root) / "objects").mkdir()
        r = run("restore.sh", "dev", "objects", "--yes", backup_root=Path(root))
        assert "run" not in r[3], r[3]
        check(r, 0, "올릴 원본 0개")


def test_restore_requires_backup_folder():
    with tempfile.TemporaryDirectory() as root:
        check(run("restore.sh", "dev", "objects", "--yes", backup_root=Path(root) / "nope"), 1, "백업 폴더가 아님")


# --- restore.sh all 사전 검증: 원본 폴더가 없다는 것만으로 "문서 0건"이라 보지 않는다 ---------------------

def restore_root(tmp, objects=()):
    """LAST_OK 와 덤프가 있는 백업 폴더. objects 가 비면 objects/<bucket> 을 만들지 않는다(PC 사본·누락)."""
    root = Path(tmp)
    (root / "db").mkdir(parents=True)
    (root / "db" / "beez-20261105T000000Z.dump").write_text("DUMP")
    (root / "LAST_OK").write_text("dump=beez-20261105T000000Z.dump\n")
    (root / "objects").mkdir()
    if objects:
        (root / "objects" / BUCKET).mkdir()
        for k in objects:
            (root / "objects" / BUCKET / k).write_text("x")
    return root


def test_restore_all_stops_before_changes_when_originals_missing():
    # 문서 1건 덤프인데 원본 폴더가 없고 bucket 에도 없다 → backend 를 멈추기 전에 실패
    with tempfile.TemporaryDirectory() as tmp:
        r = run("restore.sh", "dev", "all", "--yes", backup_root=restore_root(tmp), FAKE_COPY=copy_block("k1.txt"))
        assert "stop" not in r[3] and "start" not in r[3], f"아무것도 바꾸지 않아야 한다: {r[3]}"
        check(r, 1, "원본 1개가 백업 폴더와 bucket 어디에도 없음")


def test_restore_all_zero_documents_without_folder_restores():
    with tempfile.TemporaryDirectory() as tmp:
        r = run("restore.sh", "dev", "all", "--yes", backup_root=restore_root(tmp))
        assert r[3][:1] == ["stop"] and "start" in r[3], r[3]
        check(r, 0, "완료: backend healthy")


def test_restore_all_originals_already_in_bucket_restores():
    # DB 만 되돌리는 경우: 백업 폴더에 없어도 지금 bucket 에 있으면 된다
    with tempfile.TemporaryDirectory() as tmp:
        r = run("restore.sh", "dev", "all", "--yes", backup_root=restore_root(tmp),
                FAKE_COPY=copy_block("k1.txt", "k2.txt"), FAKE_BUCKET_LS="k1.txt\nk2.txt\nother.txt\n")
        check(r, 0, "bucket 에만 있는 것 2개")


def test_restore_all_originals_in_folder_restores():
    with tempfile.TemporaryDirectory() as tmp:
        r = run("restore.sh", "dev", "all", "--yes", backup_root=restore_root(tmp, ["k1.txt"]),
                FAKE_COPY=copy_block("k1.txt"))
        check(r, 0, "완료: backend healthy")


# --- 덤프 기준 대조 ------------------------------------------------------------

def test_dump_key_missing_fails_and_keeps_last_ok():
    r = run("backup.sh", "dev", FAKE_COPY=copy_block("k1.txt", "k2.txt"), FAKE_OBJECTS="k1.txt")
    assert not (r[4] / "backup" / "LAST_OK").exists(), "실패한 실행은 LAST_OK 를 쓰지 않는다"
    check(r, 1, "백업에 없는 원본: k2.txt")


def test_dump_keys_present_passes():
    check(run("backup.sh", "dev", FAKE_COPY=copy_block("k1.txt", "k2.txt"), FAKE_OBJECTS="k1.txt k2.txt"),
          0, "덤프의 문서 버전 2개 확인")


if __name__ == "__main__":
    tests = [(k, v) for k, v in sorted(globals().items()) if k.startswith("test_")]
    for name, fn in tests:
        fn()
        print("ok", name)
    print(f"{len(tests)} passed")
