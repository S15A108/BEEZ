# 인증 API

## POST /api/auth/login

로그인 요청. 인증 토큰 없이 호출한다.

### 요청

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| code | string | Y | 일회용 인가 코드 |

### 성공 응답

```json
{"access_token": "example", "expires_in": 3600}
```

### 오류

- 401: 코드가 만료된 경우
- 429: 요청 한도를 초과한 경우

## GET /api/auth/login

이 경로는 로그인 상태만 확인한다.
