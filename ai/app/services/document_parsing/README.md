# 원문 파서: Markdown·일반 텍스트 (#127, #223)

`parse_source(SourceInput) -> SourceDocument`는 기획안(PLAN), 요구사항(REQUIREMENTS), API 명세(API_SPEC), 데이터 설계(ERD)의 UTF-8 Markdown을 원문 블록으로 만든다. `.txt` 업로드는 `media_type: text/plain` + `plain-v1` 프로필로 받아 빈 줄로 나눈 문단을 그대로 블록으로 만든다(#223). HTTP 라우터·DB·LLM·임베딩 호출은 없다. #129의 청킹 입력으로 사용할 수 있는 내부 타입이며, 공통 API 계약은 BE 합의 후 별도로 연결한다.

media_type마다 파서 프로필이 하나다(`pipeline.PARSER_PROFILES`). 요청의 `parser_profile`은 그 프로필과 같아야 하며 다르면 `pipeline_version_mismatch`다. 정규화·해시 검증·block id·행 범위 검사는 두 프로필이 공유하고 어댑터(`adapters/markdown.py`, `adapters/plain_text.py`)만 다르다.

## 실행 예시

`ai/`에서 의존성 설치 후 실행한다. 아래는 로컬 fixture 예시다. 서비스에서는 BE가 원본 파일·버전·해시를 관리하고 strict UTF-8 디코딩한 텍스트를 전달한다.

```python
from hashlib import sha256
from pathlib import Path

from app.schemas.documents import SourceInput
from app.services.document_parsing.normalization import normalize_text, text_digest
from app.services.document_parsing.pipeline import parse_source, resolve_source

raw = Path("tests/fixtures/parsing/api-spec.md").read_bytes()
content = raw.decode("utf-8", errors="strict")
source = SourceInput(
    project_id="demo",
    document_version_id="document-v1",
    kind="API_SPEC",
    media_type="text/markdown",
    content=content,
    content_hash=sha256(raw).hexdigest(),
    text_hash=text_digest(normalize_text(content)),
    normalization_version="utf8-lf-v1",
    parser_profile="markdown-v1",
)
document = parse_source(source)
print(document.model_dump_json(indent=2))
print(resolve_source(document, document.blocks[0].locator))
```

## 원문과 구조 계약

- `content_hash`는 **BE가 보유한 원본 bytes 해시**다. 파서는 이를 보존하며 원본 bytes를 다시 검증했다고 주장하지 않는다. 정규화 텍스트의 `text_hash`는 반드시 재계산해 비교한다.
- `utf8-lf-v1`: 맨 앞 BOM 한 개 제거, CRLF/CR → LF. trim·공백 병합·Unicode 정규화는 하지 않는다. 깨진 Unicode와 NUL은 거부한다. 파일의 잘못된 UTF-8 bytes는 BE 디코딩 단계에서 거부해야 한다.
- locator는 정규화 텍스트의 **1-based, 마지막 행 포함** 범위다. 해당 행의 LF도 포함한다. 마지막 LF 뒤 가상의 빈 행은 세지 않으며 U+2028 등은 새 행으로 취급하지 않는다.
- 블록에는 원문을 중복 저장하지 않는다. `resolve_source`가 정확한 원문을 돌려준다. `text_hash`는 이 범위의 해시이며, `block_id`에는 프로젝트·문서 버전·문서 종류·텍스트 해시·정규화/파서 버전·블록 종류·위치가 반영된다.
- `heading_path`는 제목 블록 ID의 경로다. 제목 블록 자체에는 자신까지 포함한다. 같은 제목이나 문장이 반복되어도 위치가 다르면 별개 블록이다. API 엔드포인트와 데이터 테이블의 제목 경계를 이 경로로 유지한다. method/path/FK 등 의미 필드를 추정하지 않는다.
- `container_path`는 목록 항목·인용문 범위다. 컨테이너는 문맥 메타데이터이며 별도의 중복 근거 블록으로 세지 않는다. 컨테이너 내부 제목은 바깥 문서의 제목 경로를 바꾸지 않는다.
- pipe table은 `table_header`와 `table_row`로 나눈다. 헤더 블록은 구분선 행도 포함한다. 각 행은 `table_header_id`와 `cells`를 가진다. 셀은 파서가 정리한 inline Markdown이며 **원문 인용/셀 offset이 아니다**. 정확한 근거는 행 locator로 복원한다.
- 표 셀 안의 파이프는 코드 예시 안에서도 `\|`로 이스케이프한다. 본문 행의 셀 수가 헤더와 다르면 오류다. 자동 생략·빈 셀 보충을 허용하지 않는다.
- fenced JSON은 코드 블록 원문으로 보존한다. JSON 유효성 검사·OpenAPI 해석·설계 항목 추출은 하지 않는다. reference link 정의도 블록으로 남기며, 중복 정의는 각각 보존하고 진단한다.
- 모든 원문 행은 블록 또는 `excluded_ranges`에 정확히 한 번 포함된다. 제외는 공백·수평 구분선·빈 컨테이너 표시뿐이다. 의미 있는 일반 설명도 검색 대상에 남긴다. 이 검사는 원문 범위 보존을 검증하며 의미적 추출 재현율을 보장하는 지표는 아니다.

## 일반 텍스트 프로필 (`plain-v1`, #223)

`.txt` 업로드는 Markdown으로 해석하면 `<user>`, `<div>` 같은 태그 문자열이 `unsupported_construct`로 전체 실패한다. `plain-v1`은 이런 문서를 원문 그대로 색인하는 프로필이다.

- 입력은 `media_type: text/plain`, `parser_profile: plain-v1`이며 정규화(`utf8-lf-v1`)·`text_hash` 대조·한도는 Markdown과 같다.
- 공백(스페이스·탭)만 있는 행을 빈 행으로 보고, 빈 행으로 구분한 연속 행 묶음 하나가 `paragraph` 블록 하나다. 마지막 행에 LF가 없어도 블록에 포함한다.
- HTML·이미지·링크·제목·목록·표·코드 fence 등 어떤 문법도 해석하지 않는다. `#`, `-`, `>`, `|`, 백틱으로 시작하는 행도 그냥 문단 내용이며 `unsupported_construct`·`invalid_document_syntax`는 발생하지 않는다.
- `heading_path`·`container_path`·`cells`·`diagnostics`는 비어 있고 `excluded_ranges`의 reason은 `blank`뿐이다. 모든 행은 블록 또는 빈 행 범위에 정확히 한 번 속한다.
- `block_id`·`text_hash`·locator·`resolve_source` 계약은 Markdown과 같다. 같은 원문이라도 프로필이 다르면 block id가 다르다. `parser_version`은 `beez-plain/1.0.0`이다.
- 문단은 4,000개까지다. 넘기면 `document_limit_exceeded`이며 부분 결과를 돌려주지 않는다.
- 청킹(#129, `md-structure-v1`)은 그대로 적용한다. 제목·표 문맥이 없으므로 연속 문단을 600토큰 안에서 묶고, 한 문단이 한도를 넘으면 줄·코드 포인트 경계로 나눈다.

## 지원 경계와 실패

`markdown-v1`은 CommonMark + pipe table 프로필이다. HTML(인라인·주석 포함), 이미지(다른 정상 내용과 섞여 있어도)는 전체 실패다. 이미지가 참고용이면 업로드할 검색용 Markdown에서는 이미지 문법 대신 일반 링크로 바꾸고 필요한 정보를 텍스트로 적는다. 태그 문자열을 그대로 담아야 하는 문서는 `.txt`로 올리면 `plain-v1`이 원문 그대로 색인한다. 코드 블록 속 HTML 문자열은 그대로 보존한다. 링크 대상은 가져오지 않고 코드는 실행하지 않는다. 닫히지 않은 코드 fence와 잘못된 표는 성공으로 복구하지 않는다.

초기 내부 한도는 전달 텍스트의 UTF-8 1 MiB, 정규화 후 100,000 Unicode 코드 포인트, AST 10,000 노드/깊이 32(`markdown-v1`), 문단 4,000개(`plain-v1`), 표 128열, 직렬화 결과 2 MiB다. 블록 파싱 중과 전체 토큰 검증 시 확인한다. 실제 업로드 bytes 한도는 BE에서도 검사해야 한다. 비용/SLA를 검증한 수치가 아니며, 한도를 넘기면 조용히 자르지 않는다.

`SourceParsingError`는 `code`, 메시지, 가능한 경우 `locator`를 제공한다. 실패 시 부분 `SourceDocument`는 반환하지 않는다. Pydantic 입력 타입·해시 형식 위반은 `ValidationError`다. 아래는 내부 오류 코드이며 HTTP 상태 매핑은 아직 추가하지 않았다.

| 코드 | 의미 |
| --- | --- |
| `unsupported_media_type` | 미지원 kind/media_type 조합 (`text/markdown`·`text/plain` 외) |
| `pipeline_version_mismatch` | 미지원 정규화 프로필, 또는 media_type의 프로필과 다른 `parser_profile` |
| `source_hash_mismatch` | 전달된 text_hash와 정규화 원문 불일치 |
| `invalid_document_syntax` | 깨진 Unicode/NUL, 잘못된 표·fence 등 |
| `unsupported_construct` | HTML·이미지 또는 처리되지 않은 원문 범위 (`markdown-v1`만) |
| `no_extractable_content` | 빈 파일 또는 구분선만 있는 문서 |
| `document_limit_exceeded` | 입력·AST·표·출력 한도 초과 |
| `invalid_source_locator` | 원문 밖의 위치 요청 |

## 검증과 다음 연결

```text
python -m pytest -q
python -m ruff check .
python -m ruff format --check .
```

`tests/fixtures/parsing/`의 4종 입력과 `tests/test_source_parsing.py`에서 원문 복원·전체 행 범위·표/제목 경계·중복 위치·BOM/CRLF/emoji·중첩 목록/인용·오류/한도를 확인한다. `tests/test_plain_text_chunks.py`는 `plain-v1`의 태그 문자열·빈 줄 문단·600토큰 초과 한 줄을 실제 토크나이저·청커로 돌려 응답 계약과 `docs/api/examples/document-chunks/plain-*.json` fixture를 확인한다. Python 3.12 Windows에서 검증하며 Linux는 저장소의 기존 AI CI가 담당한다.

`markdown-it-py==4.2.0`(MIT, Python >=3.10, 플랫폼 독립 wheel)을 고정했다. 표·fence 규칙을 감싼 구현은 이 버전의 내부 API를 사용하므로 업그레이드 시 프로필/파서 버전과 fixture를 함께 검토한다. [공식 사용 문서](https://markdown-it-py.readthedocs.io/en/latest/using.html), [패키지 정보](https://pypi.org/project/markdown-it-py/4.2.0/), [변경 이력](https://github.com/executablebooks/markdown-it-py/blob/master/CHANGELOG.md).

BE는 `.md`를 `text/markdown`, `.txt`를 `text/plain`으로 보내고 manifest의 `parser_profile`을 media_type의 프로필과 대조한다(#223). 남은 공동 작업은 원본·정규화 해시/버전 전달, 오류 매핑, 실제 호출 통합이다. 신규 청킹 API, #129 청킹·임베딩, 승인 baseline 검증, #128 설계 항목 추출은 이 모듈의 구현 완료 범위에 포함하지 않는다. 기준: [개발 계획 Task 1](../../../../docs/superpowers/plans/2026-10-26-document-rag-development.md), [Jira #127](https://ssafy.atlassian.net/browse/S15P31A108-127).
