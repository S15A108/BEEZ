# #133 후보·질문 검토 자료

`evaluations/data/*.md`(가상 프로젝트 "나래"의 기획서·요구사항·API 명세·데이터 설계)에 #133 서비스를 돌려 **후보·질문 결과를 사람이 검토하는 자료**다. 기본 실행은 `cases.py`의 **대본(scripted) 제안**을 가짜 공급자로 쓰며 모델을 호출하지 않는다. 결과는 점수가 아니라 검토표이며, 후보가 문서 결함으로 확정된 것이 아니다.

## 실행

`ai/`에서:

```text
python -m evaluations.planning_questions.run                       # 보고서를 표준 출력으로
python -m evaluations.planning_questions.run --output <새 경로>.md   # 파일로. 기존 파일은 --overwrite 필요
python -m scripts.generate_planning_question_examples               # 커밋된 보고서와 docs/api 예시 재생성
```

실제 모델로 돌리려면 `AI_PLANNING_EVAL_ENDPOINT`(Chat Completions URL), `AI_PLANNING_EVAL_API_KEY`, `AI_PLANNING_EVAL_MODEL`을 설정하고 `--live`를 붙인다. 유료 호출이며 재시도는 없다. 아직 실행하지 않았으므로 정확도나 통과 여부는 기록하지 않는다.

## 대본 제안이 의도적으로 담은 것

`cases.py`의 각 문서 제안 4건에는 검증 로직이 걸러야 하는 출력이 하나 이상 들어 있다.

| 문서 | 미확인으로 남아야 하는 제안 | 이유 |
| --- | --- | --- |
| plan.md | "미승인 초안은 모든 멤버가 조회한다" 인용 | 원문과 다른 인용(`QUOTE_NOT_IN_BLOCK`) |
| plan.md | 개인정보 정책 부재 | 인용할 문장이 없음(`NO_EVIDENCE`) |
| requirements.md | 같은 문단의 두 문장으로 만든 CONFLICT | 블록 2개 미만(`INSUFFICIENT_EVIDENCE`) |
| requirements.md | 존재하지 않는 `block_id` | `BLOCK_NOT_FOUND` |
| api-spec.md | "업로드할 PDF 원문" 인용 | 원문은 Markdown(`QUOTE_NOT_IN_BLOCK`) |

나머지 제안은 원문 인용이 정확해 `REVIEW_CANDIDATE`로 남고, 규칙 후보와 같은 문장을 인용한 제안(plan.md 운영 일정 표, requirements.md 로그 보관 기간, api-spec.md DELETE, data-design.md 복구 기간)은 규칙의 템플릿 질문을 대체한다.

## 2026-11-02 검토 결과 (가짜 공급자)

보고서: [results-2026-11-02-dry.md](results-2026-11-02-dry.md). `tests/test_planning_questions_evaluation.py`가 같은 입력으로 다시 만든 보고서와 대조한다.

사람 검토 메모(박상은):

- 규칙 후보 10건은 모두 문서가 스스로 "정하지 않았다·미정·별도 정책에 따른다"라고 말한 문장이며 인용이 원문 블록에서 복원된다. "서비스는 없는 일정을 추측해 확정하지 않는다"(plan.md 7행)처럼 동작을 설명한 문장은 문서 범위 표지가 없어 잡히지 않는다. `-` 셀(data-design.md 키 열)은 "없음"의 뜻이라 제외했다.
- plan.md 29행 "이 문서는 가격표나 장애 보상 약관을 포함하지 않는다"는 범위 선언이라 질문 가치가 낮을 수 있다. 화면에서 사용자가 `DISMISSED`로 닫는 경로가 필요하다.
- 미확인 후보 5건은 모두 `UNVERIFIED`와 사유가 붙었고 잘못된 인용문은 결과에 나오지 않는다. 근거 없는 검토 후보는 0건이다.
- 템플릿 질문(ESSAY)은 문장을 그대로 되묻는 형식이라 모델 질문보다 길고 단조롭다. 실제 모델 평가 뒤 템플릿 문구를 다듬는다.
- 질문 수는 문서당 5~7건으로 상한 20 안이다. 실제 문서는 더 길므로 상한 초과 시 `truncated_candidate_keys` 표시가 화면에 필요하다.
- 대본 제안은 사람이 쓴 가상 출력이다. 실제 모델이 같은 품질의 인용을 내는지는 `--live` 평가로 확인해야 하며, 이 자료는 검증 로직과 계약 모양을 검토하는 용도다.
