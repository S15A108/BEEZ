# 131 합성 데이터 BE–AI 경계 시험

명시적으로 켠 이 테스트는 격리된 Testcontainers PostgreSQL과 합성 데이터로 실제 Spring Boot의 인증된 현재 기준, 의미 검색, 승인 설계, 관계·이력, 작업·작업 관계 API를 조회한다. 테스트 전용 조립기가 기준·원문 범위·버전·관계 리비전·작업 계획 리비전을 확인한 뒤 실제 AI ASGI 라우터와 컨텍스트 선택기에 스냅샷을 전달한다. 검색 근거, 확정 의존 관계, 작업 참조와 색인 미준비를 확인한다. BE 임베딩 클라이언트와 AI 임베딩 공급자는 결정적인 테스트 대역이며, probe는 외부 HTTP를 거부한다. 내부 토큰과 모델 키도 테스트 전용 문자열이다.

이 시험은 운영 BE–AI 연결, MCP E2E, 문서 수집, 검색 관련성 평가 또는 운영 권한 보장 검증이 아니다. fixture는 준비된 색인을 DB에 직접 넣는다. BE 검색 `snippet`이 fixture 원문 전체와 같다는 점은 테스트에서 별도로 확인하며, 운영 검색 결과의 전문 제공을 가정하지 않는다. 검색 상태·승인·관계 이력을 AI 입력으로 바꾸는 조립기는 테스트 전용이며 합의된 연동 계약이 아니다. 전후 조회에서는 멤버 접근과 관계·기준·작업 리비전 유지를 확인하지만, 이를 실제 운영의 원자적 스냅샷이나 경합 방지 증명으로 해석할 수 없다. SeaweedFS 컨테이너는 사용하지 않는다.

테스트 준비 과정이 테이블을 초기화하므로 반드시 격리된 Testcontainers DB만 사용해야 한다. `BEEZ_131_CROSS_STACK=true`일 때만 켜지며, 일반 통합 테스트에는 Python 환경이 필요하지 않다. opt-in 실행에는 Docker와 AI 프로젝트 의존성이 설치된 Python이 필요하다. 테스트가 `backend/src/test/java`, probe가 `ai/tests`에 있을 때 Windows에서 `backend` 디렉터리 기준 실행 예시는 다음과 같다.

```powershell
$env:BEEZ_131_CROSS_STACK='true'
$env:BEEZ_131_PYTHON='<AI 의존성이 설치된 Python 실행 파일>'
$env:BEEZ_131_AI_DIR=(Resolve-Path '..\ai').Path
$env:BEEZ_131_PROBE=(Resolve-Path '..\ai\tests\cross_stack_probe_131.py').Path
.\gradlew.bat integrationTest --tests com.beez.backend.context.ContextSelectionCrossStackIntegrationTest
```

Python 자식 프로세스는 작은 합성 JSON을 stdin으로 받고 결과를 stdout으로 반환하며 외부 서비스를 시작하지 않는다. 테스트는 stdout을 실행 중에 병렬로 읽어 파이프 버퍼 크기와 무관하게 동작하고, stderr는 버리지 않고 테스트 출력에 그대로 표시한다. 입력 전송과 프로세스 종료는 30초를 공유하고, 초과하면 자식 프로세스를 강제 종료한다. 실제 GMS·내부 토큰 환경변수는 제거하고 가짜 공급자를 사용한다.
