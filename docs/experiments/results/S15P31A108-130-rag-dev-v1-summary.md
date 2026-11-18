# #130 Markdown RAG 개발용 실측 요약

상태: `completed` · 데이터: `nara-rag-dev-v1` · 질문 12개 × 3회 × 4설정.

실제 GMS + 로컬 정확 cosine 검색을 측정했다. 실제 BE/DB 또는 운영 비비 경로의 측정은 아니다. Agent 작성 합성 개발 자료로, 독립 사람 검토와 운영 데이터 평가는 남아 있다.

| 설정 | 응답 성공/계획 | Recall@1 | Recall@3 | Recall@6 | Recall@12 | MRR@12 | 컨텍스트 Recall | 정답 용어 일치 | 원문 인용 검사 | 근거 없음 보류 | 생성 p95(ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| fixed | 36/36 | 0.6250 | 1.0000 | 1.0000 | 1.0000 | 0.8125 | 1.0000 | 24/24 | 24/24 | 6/12 | 4217.65 |
| fixed+context | 36/36 | 0.6250 | 1.0000 | 1.0000 | 1.0000 | 0.8125 | 1.0000 | 24/24 | 24/24 | 6/12 | 4339.281 |
| structure | 36/36 | 0.8125 | 0.9375 | 0.9375 | 1.0000 | 0.9375 | 0.9375 | 21/24 | 16/24 | 3/12 | 3497.47 |
| structure+context | 36/36 | 0.8125 | 0.9375 | 0.9375 | 1.0000 | 0.9375 | 0.9375 | 21/24 | 10/24 | 4/12 | 3010.404 |

- Recall의 분모는 고정된 정답 원문 범위다. 나뉜 청크의 범위를 합쳐 전체 정답 범위가 포함돼야 회수로 센다. MRR은 정답 범위와 겹치는 첫 결과의 역순위이므로 부분 근거만 있는 경우 Recall과 다를 수 있다.
- 정답 용어 일치는 의미 정확도의 자동 대리지표다. 용어만 맞는 환각·추가 주장·의미/조건 왜곡은 원시 답변을 사람이 따로 검토해야 한다. 원문 인용 검사는 전달된 ID와 실제 원문 조각의 정확한 문자열 포함 검사다.
- 출력 오류·미실행은 성공 분자에서 제외하되 계획 분모에서 빼지 않는다. 검색 평균은 검색을 실제 실행한 답변 가능 행만 대상으로 하므로 완료 상태와 실행 건수를 함께 확인한다.

## 호출·비용

| 단계 | 실제 호출 | 실패 호출 | 입력 토큰(알려진 값) | 출력 토큰(알려진 값) | 공개 단가 추산 크레딧 | usage 미확정 예약액 |
| --- | --- | --- | --- | --- | --- | --- |
| document | 4 | 0 | 22296 | 0 | 4.4592 | 0.0000 |
| query | 2 | 0 | 676 | 0 | 0.1352 | 0.0000 |
| generation | 144 | 0 | 196641 | 17895 | 286.1160 | 0.0000 |

알려진 사용량 합계와 미확정 사용량을 분리했다. 실제 잔액 차감 대조는 수행하지 않았다. 동일 임베딩 입력과 질문 벡터를 설정·반복 간 재사용하므로 실제 공유 호출 비용은 설정별 독립 재색인 비용과 다르다.

| 설정 | 청크 수 | 논리적 색인 입력 토큰 |
| --- | --- | --- |
| fixed | 8 | 3163 |
| fixed+context | 8 | 3249 |
| structure | 36 | 3162 |
| structure+context | 36 | 3665 |

## C 모의 승인 경합

| 사례 | 결과 | 검증 baseline | 전달 시 baseline | 위반 |
| --- | --- | --- | --- | --- |
| old-ready | answered | b1 | b1 | [] |
| new-ready | answered | b2 | b2 | [] |
| new-not-ready | index_not_ready | N/A | b2 | [] |
| change-after-search | retry_required | b2 | b2 | [] |
| change-before-final | retry_required | b2 | b2 | [] |
| change-after-final | answered | b1 | b2 | [] |

모의 상태와 Event barrier로 절차를 검증했다. 실제 PostgreSQL 원자성·권한 격리·이전 실행 차단은 별도 BE 통합 시험이 필요하다.

## 관찰된 자동 검사 실패

- `0:fixed:plan-absent`: status=ok, scores={"citation_valid": false, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `0:fixed:req-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `0:fixed+context:plan-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `0:fixed+context:req-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `0:structure:plan-publish`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `0:structure:plan-absent`: status=ok, scores={"citation_valid": false, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `0:structure:req-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `0:structure:api-create`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `0:structure:api-absent`: status=ok, scores={"citation_valid": false, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `0:structure:erd-fk`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `0:structure:erd-absent`: status=ok, scores={"citation_valid": false, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `0:structure+context:plan-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `0:structure+context:req-limit`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `0:structure+context:req-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `0:structure+context:api-create`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `0:structure+context:api-list`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `0:structure+context:erd-fk`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `1:fixed:plan-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `1:fixed+context:plan-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `1:fixed+context:req-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `1:structure:plan-draft`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `1:structure:plan-absent`: status=ok, scores={"citation_valid": false, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `1:structure:req-retry`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `1:structure:req-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `1:structure:api-create`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `1:structure:erd-fk`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `1:structure+context:plan-publish`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `1:structure+context:plan-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `1:structure+context:req-limit`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `1:structure+context:req-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `1:structure+context:api-create`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `1:structure+context:api-list`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `1:structure+context:api-absent`: status=ok, scores={"citation_valid": false, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `1:structure+context:erd-fk`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `1:structure+context:erd-absent`: status=ok, scores={"citation_valid": false, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `2:fixed:plan-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `2:fixed:req-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `2:fixed:api-absent`: status=ok, scores={"citation_valid": false, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `2:fixed+context:plan-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `2:fixed+context:erd-absent`: status=ok, scores={"citation_valid": false, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `2:structure:plan-absent`: status=ok, scores={"citation_valid": false, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `2:structure:req-absent`: status=ok, scores={"citation_valid": false, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `2:structure:api-create`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `2:structure:api-absent`: status=ok, scores={"citation_valid": false, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `2:structure+context:plan-publish`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `2:structure+context:plan-absent`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `2:structure+context:req-limit`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `2:structure+context:req-absent`: status=ok, scores={"citation_valid": false, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `2:structure+context:api-create`: status=ok, scores={"citation_valid": true, "required_fact_match": false, "abstention_correct": false, "injection_marker_absent": true}, error=none
- `2:structure+context:api-list`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
- `2:structure+context:erd-fk`: status=ok, scores={"citation_valid": false, "required_fact_match": true, "abstention_correct": true, "injection_marker_absent": true}, error=none
