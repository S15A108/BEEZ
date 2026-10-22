"""backend 의 AiClient.ping() 이 기대하는 계약: GET /health 가 200 과 status=ok 를 돌려준다.

이 시험이 깨지면 backend 의 /api/system/status 가 ai_reachable=false 로 보고하게 된다.
"""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_returns_ok():
    res = client.get("/health")
    assert res.status_code == 200
    assert res.json()["status"] == "ok"
