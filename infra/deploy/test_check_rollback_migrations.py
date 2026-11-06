"""check-rollback-migrations.sh(롤백 전 마이그레이션 대조)의 판정을 고정한다 (S15P31A108-328).

임시 git 저장소에 마이그레이션 파일을 커밋하고 스크립트를 복사해 돌린다(origin 은 자기 자신이라 fetch 도 진짜로 돈다).
ssh 는 가짜로 두고, DB Flyway 이력(psql -AtF '|' 출력)을 FAKE_DB 로 넣는다.
실행: python infra/deploy/test_check_rollback_migrations.py  (pytest 로도 돈다. CI 에는 넣지 않음)
필요: bash, git, python. Windows 는 Git Bash 를 쓴다.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zlib

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "check-rollback-migrations.sh"
REAL_MIGRATIONS = HERE.parents[1] / "backend/src/main/resources/db/migration"
DIR = "backend/src/main/resources/db/migration"
JDIR = "backend/src/main/java/db/migration"

# 되돌릴 SHA(OLD)의 마이그레이션: SQL 셋과 Java 하나
OLD_FILES = {
    f"{DIR}/V1__init_schema.sql": "create table a (id int);\n",
    f"{DIR}/V2__add_column.sql": "\ufeffalter table a add column b int;\r\n",   # BOM·CRLF 도 checksum 에서 빠진다
    f"{DIR}/V3__more_tables.sql": "create table c (id int);\n",
    f"{JDIR}/V5__backfill_usage.java": "class V5__backfill_usage {}\n",
}
# 가짜 ssh. 받은 인자는 SSH_ARGS 에 남기고, FAKE_SSH_RC 가 있으면 그 코드로 실패한다
SSH = '#!/usr/bin/env bash\nprintf "%s" "$*" > "$SSH_ARGS"\n[ -n "$FAKE_SSH_RC" ] && exit "$FAKE_SSH_RC"\nprintf "%s" "$FAKE_DB"\n'


def find_bash():
    if os.environ.get("BEEZ_TEST_BASH"):
        return os.environ["BEEZ_TEST_BASH"]
    git_bash = Path(r"C:\Program Files\Git\bin\bash.exe")
    return str(git_bash) if os.name == "nt" and git_bash.exists() else "bash"   # System32 의 WSL bash 를 피한다


def posix(p):
    return str(p).replace("\\", "/")


def flyway_checksum(text):
    """Flyway 의 checksum 규칙(BOM·줄바꿈 제거, UTF-8 CRC32, 부호 있는 32비트). 가짜 DB 행을 만들 때만 쓴다."""
    c = zlib.crc32(text.lstrip("\ufeff").replace("\r", "").replace("\n", "").encode())
    return c - (1 << 32) if c >= 1 << 31 else c


def row(v, checksum=None, success="t", typ="SQL", desc=None):
    """psql -AtF '|' 한 줄: version|checksum|success|type|description. 기본값은 OLD 의 그 번호 파일과 같은 기록이다."""
    if checksum is None or desc is None:
        f = next((k for k in OLD_FILES if k.split("/")[-1].startswith(f"V{v}__")), None)
        if checksum is None:
            checksum = "" if f is None or f.endswith(".java") else flyway_checksum(OLD_FILES[f])
        if desc is None:
            desc = f.split("__", 1)[1].rsplit(".", 1)[0].replace("_", " ") if f else f"v{v}"
    return f"{v}|{checksum}|{success}|{typ}|{desc}"


MATCH = [row(1), row(2), row(3), row(5, typ="JDBC")]
PIPE_SQL = "create table p (id int);\n"
KOREAN_FILE, KOREAN_SQL = "V6__한글_설명.sql", "create table k (id int);\n"
# 가짜 git: FAIL_BLOB 이 있으면 파일 내용 읽기(cat-file blob)만 실패하고 나머지는 진짜 git 으로 넘긴다
FAIL_BLOB_GIT = ('[ -n "$FAIL_BLOB" ] && case "$*" in *"cat-file blob"*) exit 128 ;; esac\n'
                 'exec "$REAL_GIT" "$@"\n')


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, text=True,
                          encoding="utf-8").stdout.strip()


def commit(repo, files, msg):
    for path, body in files.items():
        p = repo / path
        if body is None:
            p.unlink()
            continue
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(body.encode())
    git(repo, "add", "-A")
    git(repo, "commit", "-qm", msg)
    return git(repo, "rev-parse", "HEAD")


def make_repo(tmp):
    """main 에 OLD, dev 에 같은 번호 V3 파일 둘, work 브랜치에 V3 의 다른 판. (저장소 경로, OLD SHA) 를 돌려준다."""
    repo = tmp / "repo"
    repo.mkdir()
    git(repo, "init", "-q", "-b", "main")
    git(repo, "config", "user.email", "test@example.invalid")
    git(repo, "config", "user.name", "test")
    git(repo, "config", "core.autocrlf", "false")
    old = commit(repo, OLD_FILES, "old")
    git(repo, "checkout", "-qb", "work")
    commit(repo, {f"{DIR}/V3__more_tables.sql": "create table c (id bigint);\n"}, "work 판")
    git(repo, "checkout", "-qb", "dev", old)
    commit(repo, {f"{DIR}/V3__duplicate_number.sql": "create table d (id int);\n"}, "같은 번호 둘")
    git(repo, "checkout", "-qb", "korean", old)   # 한글 파일 이름(ls-tree 가 따옴표·8진수로 찍는 경우)
    commit(repo, {f"{DIR}/{KOREAN_FILE}": KOREAN_SQL}, "한글 이름")
    # pipe 브랜치: 설명에 "|" 가 든 파일. Windows 는 이런 파일 이름을 못 만들어 git 에 직접 넣는다
    blob = subprocess.run(["git", "-C", str(repo), "hash-object", "-w", "--stdin"], input=PIPE_SQL.encode(),
                          check=True, capture_output=True).stdout.decode().strip()
    index_env = {**os.environ, "GIT_INDEX_FILE": str(tmp / "pipe-index")}
    for args in (["read-tree", old], ["update-index", "--add", "--cacheinfo", f"100644,{blob},{DIR}/V4__a|b.sql"]):
        subprocess.run(["git", "-c", "core.protectNTFS=false", "-C", str(repo), *args], env=index_env, check=True,
                       capture_output=True)
    tree = subprocess.run(["git", "-C", str(repo), "write-tree"], env=index_env, check=True,
                          capture_output=True).stdout.decode().strip()
    git(repo, "branch", "pipe", git(repo, "commit-tree", tree, "-p", old, "-m", "pipe"))
    git(repo, "remote", "add", "origin", posix(repo))   # fetch 가 진짜로 돌도록 자기 자신을 origin 으로
    deploy = repo / "infra/deploy"
    deploy.mkdir(parents=True)
    shutil.copy(SCRIPT, deploy / SCRIPT.name)   # 스크립트는 자기가 든 저장소를 읽는다(커밋하지 않아도 됨)
    return repo, old


def run(*args, db=None, old=True, ref=None, stubs=None, **fake):
    """스크립트를 돌려 (exit code, 출력, ssh 인자) 를 돌려준다.

    db 는 이력 행 목록, old=True 면 첫 인자로 OLD SHA, ref 를 주면 그 브랜치의 SHA 를 대신 넣는다.
    stubs 는 ssh 말고 더 둘 가짜 명령 {이름: 본문}.
    """
    with tempfile.TemporaryDirectory(prefix="beez-rollback-check-") as tmp:
        tmp = Path(tmp)
        repo, sha = make_repo(tmp)
        bin_dir = tmp / "bin"
        bin_dir.mkdir()
        (bin_dir / "ssh").write_bytes(SSH.encode())
        for name, body in (stubs or {}).items():
            (bin_dir / name).write_bytes(("#!/usr/bin/env bash\n" + body).encode())
        ssh_args = tmp / "ssh-args"
        env = os.environ.copy()
        env["REAL_GIT"] = posix(shutil.which("git"))
        env.update({"FAKE_DB": "".join(r + "\n" for r in (MATCH if db is None else db)), "SSH_ARGS": posix(ssh_args)})
        env.pop("FAKE_SSH_RC", None)
        env.update(fake)
        if ref:
            sha = git(repo, "rev-parse", ref)
        argv = ([sha] if old else []) + list(args)
        cmd = (f'b=$(cygpath -u "{posix(bin_dir)}" 2>/dev/null || echo "{posix(bin_dir)}"); '
               'chmod +x "$b"/*; PATH="$b:$PATH" exec bash "$@"')
        r = subprocess.run([find_bash(), "-c", cmd, "check", posix(repo / "infra/deploy" / SCRIPT.name), *argv],
                           env=env, capture_output=True, text=True, encoding="utf-8")
        sent = ssh_args.read_text(encoding="utf-8") if ssh_args.exists() else None
        return r.returncode, r.stdout + r.stderr, sent


def expect(result, code, *texts):
    rc, out, _ = result
    assert rc == code, f"exit {rc} (기대 {code}):\n{out}"
    for t in texts:
        assert t in out, f"출력에 '{t}' 없음:\n{out}"


def lines(result):
    return [x for x in result[1].splitlines() if x.startswith("V")]


# --- 일치·기록용 행 ------------------------------------------------------------

def test_match_prints_only_summary():
    # Java(V5)는 DB 에도 checksum 이 비어 있어 번호·설명·유형만 맞으면 일치다
    r = run()
    expect(r, 0)
    assert r[1].strip() == "대조 끝 (DB 4개, 되돌릴 SHA 4개)", r[1]


def test_ssh_query_is_read_only_and_ordered():
    sent = run()[2]
    assert "select version, checksum, success, type, description from flyway_schema_history" in sent, sent
    assert "order by installed_rank" in sent, sent
    for word in ("update ", "delete ", "insert ", "drop ", "restart", "down"):
        assert word not in sent.lower(), f"조회가 아닌 말 '{word}':\n{sent}"


def test_schema_and_baseline_rows_are_ignored():
    db = ["0||t|SCHEMA|<< Flyway Schema Creation >>", "1||t|BASELINE|<< Flyway Baseline >>"] + MATCH
    r = run(db=db)
    expect(r, 0, "대조 끝 (DB 4개, 되돌릴 SHA 4개)")
    assert not lines(r), r[1]


def test_numbers_up_to_baseline_are_not_required():
    # V2 에서 baseline 한 DB 는 V1·V2 의 적용 행이 없다. Flyway 는 baseline 이하를 적용된 것으로 본다
    r = run(db=["2||t|BASELINE|<< Flyway Baseline >>", row(3), row(5, typ="JDBC")])
    expect(r, 0, "대조 끝 (DB 2개, 되돌릴 SHA 4개)")
    assert not lines(r), r[1]


def test_description_with_pipe():
    db = MATCH[:3] + [row(4, checksum=flyway_checksum(PIPE_SQL), desc="a|b"), row(5, typ="JDBC")]
    r = run(db=db, ref="pipe")
    expect(r, 0, "대조 끝 (DB 5개, 되돌릴 SHA 5개)")
    assert not lines(r), r[1]
    r = run(db=MATCH[:3] + [row(4, checksum=flyway_checksum(PIPE_SQL), desc="a|c"), row(5, typ="JDBC")], ref="pipe")
    expect(r, 1, 'V4: 기록 다름 — 설명(DB "a|c", 되돌릴 SHA "a|b")')


# --- 기록 다름: checksum·설명·유형 각각 -----------------------------------------

def test_checksum_differs():
    r = run(db=[row(1), row(2, checksum=123), row(3), row(5, typ="JDBC")])
    expect(r, 1, f"V2: 기록 다름 — checksum(DB 123, 되돌릴 SHA {flyway_checksum(OLD_FILES[DIR + '/V2__add_column.sql'])})"
                 " — 되돌리면 예전 backend 기동 실패")
    assert len(lines(r)) == 1, r[1]


def test_description_differs():
    r = run(db=[row(1), row(2), row(3, desc="more table"), row(5, typ="JDBC")])
    expect(r, 1, 'V3: 기록 다름 — 설명(DB "more table", 되돌릴 SHA "more tables") — 되돌리면 예전 backend 기동 실패')


def test_type_differs():
    r = run(db=[row(1), row(2), row(3), row(5, checksum=7, typ="SQL")])
    expect(r, 1, "V5: 기록 다름 — checksum(DB 7, 되돌릴 SHA ) 유형(DB SQL, 되돌릴 SHA JDBC)")


# --- 번호가 한쪽에만 있음 ------------------------------------------------------

def test_failed_row():
    r = run(db=[row(1), row(2), row(3, success="f"), row(5, typ="JDBC")])
    expect(r, 1, "V3: 실패한 마이그레이션 — 어느 backend도 기동 못 함")


def test_higher_number_only_in_db_is_judgement_target():
    r = run(db=MATCH + [row(6, checksum=-42, desc="new thing")])
    expect(r, 1, "V6: 되돌릴 SHA가 모르는 상위 번호 — 판단 대상 (DB checksum -42)", "대조 끝 (DB 5개, 되돌릴 SHA 4개)")
    assert len(lines(r)) == 1, r[1]


def test_lower_number_only_in_db_blocks():
    r = run(db=[row(1), row(2), row(3), row(4, checksum=9, desc="hotfix"), row(5, typ="JDBC")])
    expect(r, 1, "V4: DB에만 있는데 되돌릴 SHA의 최신 번호보다 낮음 — 되돌리면 예전 backend 기동 실패")


def test_higher_number_only_in_sha_runs_on_rollback():
    r = run(db=[row(1), row(2), row(3)])
    expect(r, 1, "V5: 되돌릴 SHA에만 있고 DB에 없음 — 되돌리는 순간 이 마이그레이션이 실행됨")


def test_lower_number_only_in_sha_blocks():
    r = run(db=[row(1), row(3), row(5, typ="JDBC")])
    expect(r, 1, "V2: 되돌릴 SHA에만 있고 DB에 없음 — 되돌리면 예전 backend 기동 실패")


def test_lines_are_sorted_by_number():
    r = run(db=[row(1), row(3, success="f"), row(10, checksum=1, desc="x"), row(5, typ="JDBC")])
    assert [x.split(":")[0] for x in lines(r)] == ["V2", "V3", "V10"], r[1]


# --- flyway repair 의 DELETE 행 (265 리뷰 낮음 1) ---------------------------------

def test_repair_delete_row_removes_number():
    # V4 를 손 배포했다가 repair 로 지움 처리했다. "DB에만 있는데 낮음" 으로 찍으면 되돌리기를 잘못 막는다
    db = [row(1), row(2), row(3), row(4, checksum=9, desc="hotfix"), row(5, typ="JDBC"), row(4, typ="DELETE", desc="hotfix")]
    r = run(db=db)
    expect(r, 0, "대조 끝 (DB 4개, 되돌릴 SHA 4개)")
    assert not lines(r), r[1]


def test_reapplied_after_delete_counts_again():
    db = [row(1), row(2), row(2, typ="DELETE"), row(3), row(5, typ="JDBC"), row(2, checksum=55)]
    expect(run(db=db), 1, "V2: 기록 다름 — checksum(DB 55,")


# --- 대조하지 못하면 중단 -----------------------------------------------------------

def test_empty_response_stops():
    expect(run(db=[]), 2, "DB 이력을 받지 못함 (빈 응답) — 중단")


def test_ssh_failure_stops():
    expect(run(FAKE_SSH_RC="255"), 2, "DB 이력을 받지 못함 — 중단")


def test_unknown_sha_stops():
    r = run("0" * 40, old=False)
    expect(r, 2, "없는 SHA: " + "0" * 40 + " — 중단")
    assert r[2] is None, "SHA 가 없으면 서버에 묻지 않는다"


def test_awk_failure_stops():
    # 대조 계산이 깨지면 줄 없는 "대조 끝"(= 되돌려도 됨)이 아니라 중단이어야 한다
    r = run(stubs={"awk": "exit 2\n"})
    expect(r, 2, "대조 계산(awk) 실패 — 중단")
    assert "대조 끝" not in r[1], r[1]


def test_unreadable_file_stops():
    # 파일을 못 읽으면 빈 입력의 checksum(0)으로 대조하지 않는다
    r = run(stubs={"git": FAIL_BLOB_GIT}, FAIL_BLOB="1")
    expect(r, 2, "되돌릴 SHA 의 파일을 읽지 못함: " + DIR + "/V1__init_schema.sql — 중단")
    assert "대조 끝" not in r[1], r[1]


def test_korean_file_name():
    db = MATCH + [row(6, checksum=flyway_checksum(KOREAN_SQL), desc="한글 설명")]
    r = run(db=db, ref="korean")
    expect(r, 0, "대조 끝 (DB 5개, 되돌릴 SHA 5개)")
    assert not lines(r), r[1]


def test_usage():
    expect(run(old=False), 2, "사용: check-rollback-migrations.sh")


# --- 실제 적용된 판 찾기 (265 리뷰 낮음 2) -------------------------------------------

def test_find_prints_each_dev_file_on_its_own_line():
    r = run("find", "3", old=False)
    expect(r, 0)
    dev = [x for x in r[1].splitlines() if x.startswith("dev 판: ")]
    assert dev == [f"dev 판: {flyway_checksum('create table d (id int);' )} V3__duplicate_number.sql",
                   f"dev 판: {flyway_checksum('create table c (id int);')} V3__more_tables.sql"], r[1]


def test_find_accepts_v_prefix():
    # 대조 결과 줄은 "V3: …" 이라 V 를 붙여 치기 쉽다
    r = run("find", "V3", old=False)
    expect(r, 0, "dev 판: ", "V3__duplicate_number.sql")


def test_find_missing_number_says_so():
    expect(run("find", "9", old=False), 1, "V9 파일 없음")


def test_find_unreadable_file_is_marked():
    r = run("find", "3", old=False, stubs={"git": FAIL_BLOB_GIT}, FAIL_BLOB="1")
    expect(r, 0, "dev 판: 읽지못함 V3__duplicate_number.sql")
    assert "dev 판: 0 " not in r[1], r[1]


def test_find_lists_versions_from_other_branches():
    out = run("find", "3", old=False)[1]
    assert f"{flyway_checksum('create table c (id bigint);')} " in out, out   # work 브랜치의 판


# --- checksum: 서버 값과 고정 대조 ---------------------------------------------------

def run_checksum(*paths):
    return subprocess.run([find_bash(), posix(SCRIPT), "checksum", *map(posix, paths)],
                          capture_output=True, text=True, encoding="utf-8")


def checksum_of(*paths):
    r = run_checksum(*paths)
    assert r.returncode == 0, r.stdout + r.stderr
    return [int(x.split()[0]) for x in r.stdout.splitlines()]


def test_checksum_matches_server_values():
    # 2026-11-06 dev 서버 flyway_schema_history 의 값
    expected = {"V10": 1544006874, "V13": -938035569, "V37": 1873346674}
    files = [next(REAL_MIGRATIONS.glob(f"{v}__*.sql")) for v in expected]
    assert checksum_of(*files) == list(expected.values())


def test_checksum_ignores_bom_and_line_endings():
    with tempfile.TemporaryDirectory() as tmp:
        a, b = Path(tmp) / "a.sql", Path(tmp) / "b.sql"
        a.write_bytes("select 1;\nselect '한글';\n".encode())
        b.write_bytes("\ufeffselect 1;\r\nselect '한글';\r\n".encode())
        x, y = checksum_of(a, b)
        assert x == y == flyway_checksum("select 1;\nselect '한글';\n"), (x, y)


def test_checksum_of_directory_fails():
    # 폴더를 넘기면 빈 값으로 성공하지 않고 읽지 못함으로 멈춘다
    with tempfile.TemporaryDirectory() as tmp:
        r = run_checksum(Path(tmp))
        assert r.returncode == 2 and "읽지 못함" in r.stdout, r.stdout + r.stderr


if __name__ == "__main__":
    tests = [(k, v) for k, v in sorted(globals().items()) if k.startswith("test_")]
    for name, fn in tests:
        fn()
        print("ok", name)
    print(f"{len(tests)} passed")
