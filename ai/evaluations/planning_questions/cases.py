"""Scripted model proposals for the four synthetic documents; no model is called.

Each proposal mimics a plausible model output over `evaluations/data/*.md` and
deliberately includes unverifiable items (a fabricated quote, an absent topic,
an unknown block id, a one-block conflict) so the review shows how such output
is labelled UNVERIFIED instead of being presented as a finding. `line` anchors
are resolved to real block ids at run time; a literal `block_id` is used as is.
"""

import re
from collections.abc import Callable
from hashlib import sha256
from pathlib import Path

DATA = Path(__file__).resolve().parents[1] / "data"
PROJECT_ID = "101"

DOCUMENTS = (
    {
        "name": "plan.md",
        "kind": "PLAN",
        "document_id": "301",
        "document_version_id": "311",
        "version_no": 1,
    },
    {
        "name": "requirements.md",
        "kind": "REQUIREMENTS",
        "document_id": "202",
        "document_version_id": "212",
        "version_no": 2,
    },
    {
        "name": "api-spec.md",
        "kind": "API_SPEC",
        "document_id": "302",
        "document_version_id": "312",
        "version_no": 1,
    },
    {
        "name": "data-design.md",
        "kind": "ERD",
        "document_id": "303",
        "document_version_id": "313",
        "version_no": 1,
    },
)


def _choice(text, options, multiple=False):
    return {"type": "CHOICE", "text": text, "options": options, "multiple": multiple}


def _short(text):
    return {"type": "SHORT_ANSWER", "text": text, "options": [], "multiple": False}


def _essay(text):
    return {"type": "ESSAY", "text": text, "options": [], "multiple": False}


PROPOSALS = {
    "plan.md": [
        {
            "kind": "CLARIFICATION",
            "summary": "'활성 프로젝트 리더'의 활성 기준(계정 상태인지, 프로젝트 멤버십 상태인지)이 정의되어 있지 않습니다.",
            "evidence": [
                {
                    "line": 11,
                    "quote": "문서의 최종 발행은 활성 프로젝트 리더만 수행한다.",
                },
                {
                    "line": 13,
                    "quote": "리더도 비활성 계정이거나 프로젝트에서 탈퇴했다면 발행할 수 없다.",
                },
            ],
            "question": _choice(
                "'활성 프로젝트 리더'는 어떤 기준으로 판단하나요?",
                [
                    "계정이 활성 상태이면 된다",
                    "계정 활성과 프로젝트 멤버십 활성이 모두 필요하다",
                    "리더 역할을 부여받은 시점을 기준으로 한다",
                ],
            ),
        },
        {
            "kind": "MISSING_CANDIDATE",
            "summary": "정식 운영 담당이 '미정'이며 누가 언제 정하는지 적혀 있지 않습니다.",
            "evidence": [
                {"line": 27, "quote": "| 정식 운영 | 미정 | 시범 운영 평가 후 협의 |"}
            ],
            "question": _short("정식 운영 담당자는 누가, 언제 정하나요?"),
        },
        {
            # The quote is not in the document (it says 접근 권한이 있는 멤버만).
            "kind": "MISSING_CANDIDATE",
            "summary": "미승인 초안을 볼 수 있는 멤버 범위가 구체적이지 않습니다.",
            "evidence": [{"line": 17, "quote": "미승인 초안은 모든 멤버가 조회한다."}],
            "question": _essay("미승인 초안을 조회할 수 있는 멤버 조건을 적어 주세요."),
        },
        {
            # Absent topic: the model may raise it, but there is nothing to quote.
            "kind": "MISSING_CANDIDATE",
            "summary": "개인정보 보관·삭제 정책이 문서에 없습니다.",
            "evidence": [],
            "question": _essay(
                "개인정보 보관 기간과 삭제 절차를 어떻게 정할 계획인가요?"
            ),
        },
    ],
    "requirements.md": [
        {
            # Two quotes from the same block cannot establish a conflict.
            "kind": "CONFLICT",
            "summary": "한도 적용 단위가 개별 파일인지 요청 전체인지 서로 다르게 읽힙니다.",
            "evidence": [
                {"line": 9, "quote": "한도는 개별 파일마다 적용한다."},
                {
                    "line": 9,
                    "quote": "한 번에 여러 파일을 보낼 때 전체 요청의 합계 한도는 이 문서에서 정의하지 않는다.",
                },
            ],
            "question": _choice(
                "여러 파일을 한 번에 보낼 때 합계 한도를 둘까요?",
                ["둔다", "두지 않는다"],
            ),
        },
        {
            "kind": "CLARIFICATION",
            "summary": "'30 MB'가 10진 MB인지 2진 MiB인지 명시되지 않았습니다.",
            "evidence": [
                {
                    "line": 7,
                    "quote": "단일 파일 업로드 한도는 30 MB이며, 초과하면 HTTP 413을 반환한다.",
                }
            ],
            "question": _choice(
                "30 MB의 바이트 계산 기준은 무엇인가요?",
                ["30,000,000바이트(10진 MB)", "31,457,280바이트(2진 MiB)"],
            ),
        },
        {
            "kind": "MISSING_CANDIDATE",
            "summary": "감사 기록의 보관 기간이 정해지지 않았습니다.",
            "evidence": [
                {"line": 30, "quote": "이 문서는 로그 보관 기간을 확정하지 않는다."}
            ],
            "question": _short("감사 기록 보관 기간은 얼마로 정하나요? (예: 90일)"),
        },
        {
            # Unknown block id: never resolvable, so it stays unverified.
            "kind": "MISSING_CANDIDATE",
            "summary": "재시도 횟수 상한이 없습니다.",
            "evidence": [{"block_id": "blk_not_in_document", "quote": "재시도"}],
            "question": _short("재시도 횟수 상한을 둘까요? 둔다면 몇 회인가요?"),
        },
    ],
    "api-spec.md": [
        {
            "kind": "MISSING_CANDIDATE",
            "summary": "문서 삭제 API의 경로·메서드·응답이 합의되지 않았습니다.",
            "evidence": [
                {
                    "line": 37,
                    "quote": "문서 삭제 API의 경로와 메서드별 상세 응답은 아직 합의하지 않았다.",
                }
            ],
            "question": _choice(
                "문서 삭제 API를 이번 범위에 포함하나요?",
                ["포함한다 (경로와 응답을 기타에 적어 주세요)", "포함하지 않는다"],
            ),
        },
        {
            "kind": "CLARIFICATION",
            "summary": "413 판정이 파일 하나 기준인지 요청 전체 기준인지 명시되지 않았습니다.",
            "evidence": [
                {
                    "line": 25,
                    "quote": "인증 세션이 없으면 401, 업로드 한도 초과는 413을 반환한다.",
                }
            ],
            "question": _short("413은 어떤 크기를 기준으로 판정하나요?"),
        },
        {
            "kind": "CLARIFICATION",
            "summary": "목록 조회의 기본 정렬 기준과 커서 형식이 정의되지 않았습니다.",
            "evidence": [
                {
                    "line": 33,
                    "quote": "페이지 이동 시 정렬 기준과 커서를 동일하게 유지한다.",
                }
            ],
            "question": _essay("문서 목록의 기본 정렬 기준과 커서 형식을 적어 주세요."),
        },
        {
            # Fabricated: the table says Markdown, not PDF.
            "kind": "MISSING_CANDIDATE",
            "summary": "PDF 업로드 시 변환 책임이 적혀 있지 않습니다.",
            "evidence": [{"line": 13, "quote": "업로드할 PDF 원문"}],
            "question": _essay(
                "PDF 업로드를 지원한다면 텍스트 변환은 누가 담당하나요?"
            ),
        },
    ],
    "data-design.md": [
        {
            "kind": "CLARIFICATION",
            "summary": "owner_id와 legacy_owner_id의 관계(동기화 여부)가 정의되지 않았습니다.",
            "evidence": [
                {
                    "line": 20,
                    "quote": "| owner_id | bigint | N | FK | accounts.id 참조 |",
                },
                {
                    "line": 21,
                    "quote": "| legacy_owner_id | varchar(80) | Y | - | 외부 표시값, FK가 아님 |",
                },
            ],
            "question": _choice(
                "legacy_owner_id는 owner_id와 어떻게 관리하나요?",
                [
                    "참고 표시값일 뿐 동기화하지 않는다",
                    "owner_id가 바뀌면 legacy_owner_id도 갱신한다",
                ],
            ),
        },
        {
            "kind": "MISSING_CANDIDATE",
            "summary": "계정 복구 기간과 물리 인덱스 이름이 정의되지 않았습니다.",
            "evidence": [
                {
                    "line": 25,
                    "quote": "이 문서는 테이블별 물리 인덱스 이름과 계정 복구 기간을 정의하지 않는다.",
                }
            ],
            "question": _short("계정 복구 기간은 며칠로 정하나요?"),
        },
        {
            "kind": "CONFLICT",
            "summary": "content_hash는 원본 bytes 해시인데 본문은 정규화 텍스트 해시도 언급해 저장 기준이 둘로 읽힙니다.",
            "evidence": [
                {"line": 33, "quote": "원본 bytes SHA-256"},
                {
                    "line": 35,
                    "quote": "정규화 텍스트 해시는 원본 bytes 해시와 구분한다.",
                },
            ],
            "question": _choice(
                "document_versions에 어떤 해시를 저장하나요?",
                [
                    "원본 bytes 해시만 저장한다",
                    "정규화 텍스트 해시 컬럼을 추가한다",
                ],
                multiple=True,
            ),
        },
        {
            "kind": "MISSING_CANDIDATE",
            "summary": "document_chunk 테이블의 컬럼 정의가 없습니다.",
            "evidence": [
                {
                    "line": 39,
                    "quote": "document_chunk의 유일성 기준은 document_version_id와 chunk_no의 조합이다.",
                }
            ],
            "question": _essay(
                "document_chunk 테이블의 컬럼(타입·NULL·키)을 적어 주세요."
            ),
        },
    ],
}

_SPAN = re.compile(r"L(\d+)-L(\d+)$")


def document_entry(name: str) -> dict:
    for entry in DOCUMENTS:
        if entry["name"] == name:
            return entry
    raise KeyError(name)


def request_body(name: str, *, mode: str = "MODEL", max_questions: int = 20) -> dict:
    """The #133 request for one synthetic document, as the BE would send it."""
    entry = document_entry(name)
    raw = (DATA / name).read_bytes()
    content = raw.decode("utf-8")
    normalized = content.removeprefix("﻿").replace("\r\n", "\n")
    return {
        "project_id": PROJECT_ID,
        "request_key": f"planning-questions-{entry['document_version_id']}",
        "document": {
            "document_id": entry["document_id"],
            "document_version_id": entry["document_version_id"],
            "version_no": entry["version_no"],
        },
        "kind": entry["kind"],
        "media_type": "text/markdown",
        "content": content,
        "content_hash": sha256(raw).hexdigest(),
        "text_hash": sha256(normalized.encode("utf-8")).hexdigest(),
        "normalization_version": "utf8-lf-v1",
        "parser_profile": "markdown-v1",
        "mode": mode,
        "max_questions": max_questions,
    }


def scripted_proposal(name: str, block_for_line: Callable[[int], str]) -> dict:
    """The raw JSON a model would return, with line anchors turned into block ids."""
    candidates = []
    for proposal in PROPOSALS[name]:
        evidence = [
            {
                "block_id": item["block_id"]
                if "block_id" in item
                else block_for_line(item["line"]),
                "quote": item["quote"],
            }
            for item in proposal["evidence"]
        ]
        candidates.append({**proposal, "evidence": evidence})
    return {"candidates": candidates}


def block_ids_from_payload(payload: dict) -> Callable[[int], str]:
    """Resolve a line to a block id using the locations in the model payload."""
    spans = []
    for block in payload["blocks"]:
        match = _SPAN.search(block["location"])
        spans.append((int(match.group(1)), int(match.group(2)), block["block_id"]))

    def block_for_line(line: int) -> str:
        for start, end, block_id in spans:
            if start <= line <= end:
                return block_id
        raise KeyError(line)

    return block_for_line
