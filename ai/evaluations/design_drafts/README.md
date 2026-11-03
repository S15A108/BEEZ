# #134 답변 반영 설계 초안 검토 자료

가상 기획서 `evaluations/data/plan.md`에 #133 파이프라인(대본 가짜 공급자)을 돌려 얻은 질문 7건에 사람이 쓴 답변 4건을 붙이고, 기존 설계 항목 3건(APPROVED 1, DRAFT 2)과 함께 #134 서비스에 넣어 **초안과 변경 목록을 사람이 검토하는 자료**다. 기본 실행은 `cases.py`의 **대본 초안 제안**을 쓰며 모델을 호출하지 않는다. 모든 초안은 미승인이다.

## 실행

`ai/`에서:

```text
python -m evaluations.design_drafts.run                        # 보고서를 표준 출력으로
python -m evaluations.design_drafts.run --output <새 경로>.md    # 파일로. 기존 파일은 --overwrite 필요
python -m scripts.generate_design_draft_examples                # 커밋된 보고서와 docs/api 예시 재생성
```

실제 모델로 돌리려면 `AI_DRAFT_EVAL_ENDPOINT`, `AI_DRAFT_EVAL_API_KEY`, `AI_DRAFT_EVAL_MODEL`을 설정하고 `--live`를 붙인다. 유료 호출이며 재시도는 없다. 아직 실행하지 않았다.

## 대본 제안이 의도적으로 담은 것

| 제안 | 기대 처리 |
| --- | --- |
| APPROVED 항목 1101(발행 권한) 갱신 | `UPDATE`, `base_status=APPROVED`, `before`에 승인 본문 그대로, 답변 참조 1건 |
| DRAFT 항목 1103(첫 출시 범위) 갱신 | `UPDATE`, 답변(모바일 제외) 반영 |
| 정식 운영 담당 신규 | `CREATE`, 표 행 인용 + 답변 참조(`DOCUMENT_AND_ANSWER`) |
| 검토 모드 정책 신규 | `CREATE`, 원문 인용만(`DOCUMENT`) |
| 항목 1102를 본문 그대로 "갱신" | 변경 없음 → 제외, 1102는 `untouched_existing_ids` |
| 정식 운영 날짜 신규, 인용 "2027년 1월"은 원문에 없음 | 인용 제거, 답변 참조만 남아 `ANSWER` |
| 응답 시간 1초 제약, 근거 없음 | `NONE`, 맨 뒤 |

## 2026-11-03 검토 결과 (가짜 공급자)

보고서: [results-2026-11-03-dry.md](results-2026-11-03-dry.md). `tests/test_design_drafts_evaluation.py`가 같은 입력으로 다시 만든 보고서와 대조한다.

사람 검토 메모(박상은):

- 7건 제안 중 6건이 초안으로 남았고 동일 재진술 1건은 제외됐다. 승인 항목 1101의 본문은 `before`에 그대로 남고 초안은 DRAFT로만 나온다(완료 조건 "승인본이 변경되지 않음").
- 변경 목록의 `before`/`after`/`changed_fields`/`answer_refs`로 어떤 답변이 어떤 본문을 바꿨는지 추적된다(완료 조건 "답변 전후 차이 확인").
- 조작 인용은 결과에 나오지 않고 근거 없는 항목은 `NONE`으로 맨 뒤에 온다. 화면에서 `NONE` 항목은 별도 표시가 필요하다.
- 본문 차이는 최상위 필드 단위라 `fields` 배열 안의 세부 변경은 "fields 바뀜"으로만 보인다. DATA_CONTRACT 세부 diff는 필요하면 후속으로 둔다.
- 대본 제안은 사람이 쓴 가상 출력이다. 실제 모델이 기준 항목 ID·답변 키를 정확히 쓰는지는 `--live` 평가로 확인해야 한다.
