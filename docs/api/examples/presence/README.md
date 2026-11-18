# presence (MON-A06·A07) mock 응답

S15P31A108-386. FE 가 백엔드 없이 접속 점·위치·마지막 접속을 그릴 때 쓰는 예시다. 실제 응답과 같은 형태다.

| 파일 | 내용 |
| --- | --- |
| `report-request.json` | PUT `/api/projects/{id}/presence` 요청. 응답은 204, 본문 없음. 필드는 모두 선택이고 보낼 때마다 현재 값을 모두 보낸다 |
| `list-response.json` | GET `/api/projects/{id}/presence` 응답. 첫째 online, 둘째 오프라인(마지막 위치 유지), 셋째 접속한 적 없음 |
| `errors.json` | 400 `validation_failed`, 429 `rate_limited`, 404 `not_found`, 401 `unauthenticated` |

- `zone`: `meeting`, `lead`, `dev`, `review`, `board`, `lobby`, `humandesk`, `control`, `hr`
- `seat`: 영문·숫자·`-`·`_` 1~16자
- `panel`: `documents`, `planning`, `changes`, `tasks`, `meetings`, `relations`, `dashboard`, `settings`, `inbox`, `chat`, `context`
- 15초마다 보내고, 마지막 보고 45초 뒤 오프라인이다. 보고는 (계정, 프로젝트)당 30초에 10회까지다.
- 값이 없는 필드는 생략한다(null 아님). `activity` 는 받지만 저장하지 않는다.
