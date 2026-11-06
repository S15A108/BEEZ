# 역할 분담 추천 제안 (S15P31A108-136)

## 범위와 상태

비비가 작업별 우선 후보와 대안을 **제안**한다. AI는 DB를 읽거나 담당자·역할을
바꾸지 않는다. 사람의 확인과 실제 배정은 기존 TSK-A06 및 BE 권한 정책을 따른다.
이 문서는 [AI-A07](../api/03-ai-internal.md#ai-a07)와 외부
[BEE-A02](../api/01-backend-rest.md#bee-a02)의 구현 제안이다. 변경된 계약은 리뷰가 필요하다.
AI 내부 경로 `POST /v1/bee/assignments`는 기본 비활성이며 내부 토큰이 필요하다.

BE는 사람 세션과 프로젝트 멤버십을 확인하고 같은 프로젝트의 현재 활성 멤버와 작업
스냅샷을 구성한다. 모델 호출 뒤 스냅샷이 달라졌으면 409로 다시 요청하게 한다.
FE 작업 화면은 후보와 대안, 이유 및 미확인 정보를 표시한다. 실제 배정은 기존
담당자 변경 절차에서 사람이 선택한다. AI 입력의 ID 일치는 DB 소속을 증명하지 않는다.

## 입력 제안

| 필드 | 설명 |
| --- | --- |
| `project_id`, `request_key` | BE가 확인한 프로젝트와 요청 식별자. 모델 호출마다 별도 usage 키를 발급한다 |
| `tasks` | 추천할 TaskDetail 1~20건. `id`, `project_id`, `status`, `assignee_member_id?`, 현재 계획의 제목·목표·포함 범위·접근 방식·`dependency_task_ids`를 사용한다. 완료·취소 작업은 거절한다 |
| `members` | BE가 확인한 활성 Member 0~100명. `id`, 프로젝트 `role`, `domain?`, `role_label?`을 사용한다. 계정 이름과 이메일은 모델에 보내지 않는다 |
| `current_tasks` | **제안 확장**. 업무량과 선행 관계 확인을 위한 같은 프로젝트 작업 최대 500건. 각 작업은 `id`, `project_id`, `status`, `assignee_member_id?`가 필요하다. 선행 작업은 완료 상태여도 포함할 수 있다 |
| `current_tasks_complete` | **제안 확장**. BE가 담당자가 있는 모든 초안·준비·진행·대기 작업을 포함했을 때만 `true`. 기본 `false`. `current_tasks`가 없으면 `true`일 수 없다 |

`current_tasks`를 생략하면 업무량은 **미확인**이다. 빈 목록과 다르게 취급한다.
목록이 일부이면 관측된 건수만 표시하고 전체 업무량을 알 수 없다고 알린다.
`true`일 때도 AI가 목록의 완전성을 DB와 대조할 수 없으므로 BE가 보장해야 한다.
추천 대상과 현재 작업에 같은 ID가 있으면 상태·담당자는 일치해야 하고, 완전하다고
표시한 목록에서 이미 담당자가 있는 초안·준비·진행·대기 추천 대상을 빼면 거절한다.
계획의 선행 ID가 입력에 없으면 상태와 담당자는 미확인이다. 선행 목록이 없는 것과
빈 목록도 구분한다.

## 추천과 검증

- 모델은 작업 내용, 담당 영역·역할 라벨, 관측된 업무량과 선행 작업 상태·담당자를
  바탕으로 입력된 팀원 ID를 순서대로 고른다. 프로젝트 역할 `LEADER`는 권한이지
  기술 역량으로 취급하지 않는다. 순위는 모델의 제안이며 적합성·공정성 보증이 아니다.
- 업무량은 `DRAFT`, `READY`, `IN_PROGRESS`, `WAITING` 작업의 **건수**다.
  `COMPLETED`, `CANCELLED`는 세지 않으며, 현재 추천 대상이 이미 그 사람의 업무량에
  포함되어 있으면 다른 작업 수에서 한 번 뺀다. 건수는 소요 시간이나 난이도가 아니다.
- 모델은 후보마다 주요 기준을 `AREA`, `WORKLOAD`, `DEPENDENCY`, `INSUFFICIENT` 중
  하나로 제시한다. AI는 해당 입력이 실제로 제공됐는지 확인하고 모델의 기준 판단과
  입력된 담당 영역·역할 라벨, 관측 건수, 확인된 선행 작업의 담당자 수를 조합해
  이유를 만든다. 모델이 자유롭게 만든 숫자는 쓰지 않는다. 영역 적합성의 의미 판단과
  후보 순위 자체는 검증하지 못하므로 사람이 확인해야 한다.
- 입력에 없는 작업·팀원, 중복 후보, 빠진 작업, 팀원이 둘 이상인데 대안이 없는
  응답은 전체 거절하고 실제 호출 usage를 실패로 기록한다. 한 명이면 대안은 빈 배열과
  안내를 반환하며, 팀원이 없으면 모델 호출 없이 빈 제안을 반환한다.
- 한 요청의 여러 작업은 함께 모델에 보이지만, 각 업무량은 제공된 시점의 스냅샷이다.
  추천을 실제 배정으로 간주해 업무량을 확정하거나 최적 배분을 보장하지 않는다.
- 긴 계획은 제목 400자·목표 800자·접근 방식 400자, 포함 범위 앞 6개(각 200자)만
  모델에 보내고 잘렸으면 `unresolved`에 남긴다. 요청 본문은 기본 2 MiB, 모델 입력은
  256 KiB까지다. 프로젝트별 인가·활성 사용자 수·입력 수집은 BE 책임이다.

## 입출력 예시

가상 입력이며 `current_tasks_complete=true`일 때는 해당 스냅샷의 담당자 있는
초안·준비·진행·대기 작업을 빠짐없이 담아야 한다.

```json
{
  "project_id": "101",
  "request_key": "assignment-136-example",
  "tasks": [{
    "id": "501", "project_id": "101", "status": "READY",
    "current_plan_revision": {
      "task_id": "501",
      "plan": {
        "title": "로그인 API 구현", "goal": "승인된 인증 계약을 구현한다.",
        "included_scope": ["로그인 API"], "approach": "기존 인증 사용",
        "dependency_task_ids": ["500", "999"]
      }
    }
  }],
  "members": [
    {"id": "21", "role": "MEMBER", "domain": "backend", "role_label": "백엔드"},
    {"id": "22", "role": "MEMBER", "domain": "frontend", "role_label": "프론트엔드"}
  ],
  "current_tasks": [
    {"id": "500", "project_id": "101", "status": "IN_PROGRESS", "assignee_member_id": "21"},
    {"id": "502", "project_id": "101", "status": "WAITING", "assignee_member_id": "21"}
  ],
  "current_tasks_complete": true
}
```

다음은 모델이 21, 22 순으로 선택했을 때의 가상 응답이다. 순서와 usage는 실제
호출마다 달라진다. `alternatives`와 `unresolved`는 기존 AI-A07의
`suggestions[{task_id, member_id, reason}]`을 확장한 필드로 계약 리뷰가 필요하다.

```json
{
  "result": {
    "suggestions": [{
      "task_id": "501",
      "member_id": "21",
      "reason": "모델이 작업 내용과 담당 영역·역할 라벨의 관련성을 추천 기준으로 판단했습니다. 입력된 담당 영역 backend · 역할 백엔드입니다. 전체 다른 초안·준비·진행·대기 작업은 2건입니다. 확인된 선행 작업 1건 중 이 사람이 담당한 작업은 1건입니다. 선행 작업 1건의 상태와 담당자는 확인되지 않았습니다.",
      "alternatives": [{
        "member_id": "22",
        "reason": "모델이 작업 내용과 담당 영역·역할 라벨의 관련성을 추천 기준으로 판단했습니다. 입력된 담당 영역 frontend · 역할 프론트엔드입니다. 전체 다른 초안·준비·진행·대기 작업은 0건입니다. 확인된 선행 작업 1건 중 이 사람이 담당한 작업은 0건입니다. 선행 작업 1건의 상태와 담당자는 확인되지 않았습니다."
      }]
    }],
    "unresolved": ["일부 선행 작업의 상태와 담당자를 확인하지 못했습니다."]
  },
  "origin": "MODEL",
  "model": "gpt-6-luna",
  "usage": [{
    "request_key": "llm-assignment-example", "feature": "TASK_ASSIGNMENT",
    "model": "gpt-6-luna", "latency_ms": 1000, "failed": false,
    "tokens_known": true, "input_tokens": 400, "output_tokens": 50
  }]
}
```

모델 입력·출력 한도, 인증, 오류·usage 처리는
[AI-A07](../api/03-ai-internal.md#ai-a07)의 내부 공통 규약을 따른다. 켜려면
`ASSIGNMENT_RECOMMENDATION_ENABLED=true`와 `GMS_KEY`, 내부 토큰이 필요하다.
입력 구조 오류는 400, 프로젝트 불일치 등 업무 오류는 422, 잘못된 모델 출력은
502, 호출 시간 초과는 504다. 설정 오류는 503, 본문 초과는 413이다.

## 연동과 리뷰

BE는 담당자가 있는 열린 작업을 최대 500건까지 먼저 담고, 공간이 남으면 대상 작업의
선행 작업 상태를 추가한다. 열린 작업이 500건을 넘으면 전체 업무량이 아니라 관측된
건수로 표시한다. AI 호출 중에는 DB 트랜잭션을 유지하지 않고 호출 전후의 짧은 읽기
트랜잭션에서 작업·팀원 상태를 비교한다. 추천을 새로 요청할 때마다 모델을 다시 호출할
수 있다. 외부 계약은 BE 담당자의 리뷰가 필요하며, 합성 입력의 결과는 실제 팀의 적합성이나
업무 배분 품질을 보장하지 않는다.
