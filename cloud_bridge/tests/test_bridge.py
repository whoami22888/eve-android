import httpx
import pytest
from fastapi.testclient import TestClient
from cloud_bridge.app import build_app

APP_TOKEN = "test-mobile-access-token-123456789"
HERMES_KEY = "test-hermes-api-secret-123456789"
RUN_ID = "run_" + "b"*32
AUTH = {"Authorization": "Bearer " + APP_TOKEN}

def connect(handler):
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    app = build_app(hermes_url="http://127.0.0.1:8642", app_token=APP_TOKEN,
                    hermes_key=HERMES_KEY, client=client)
    return TestClient(app)

def test_unauthenticated_blocked():
    with connect(lambda req: httpx.Response(200, json={"ok":True})) as c:
        assert c.post("/api/runs",json={"input":"hello"}).status_code == 401
        assert c.get("/ready").status_code == 401
        assert c.get("/health").status_code == 200

def test_rejects_wrong_token():
    with connect(lambda req: httpx.Response(200,json={})) as c:
        assert c.post("/api/runs",headers={"Authorization":"Bearer wrong"},
                      json={"input":"hi"}).status_code == 401

def test_ready_queries_hermes():
    def fake(req):
        assert req.url.path == "/health"
        assert req.headers["authorization"] == "Bearer " + HERMES_KEY
        return httpx.Response(200,json={"ok":True})
    with connect(fake) as c:
        assert c.get("/ready",headers=AUTH).json()["backend"] == "reachable"

def test_create_and_status():
    def fake(req):
        assert req.headers["authorization"] == "Bearer " + HERMES_KEY
        if req.method == "POST":
            assert req.url.path == "/v1/runs"
            return httpx.Response(202,json={"run_id":RUN_ID,"status":"started"})
        assert req.url.path == "/v1/runs/" + RUN_ID
        return httpx.Response(200,json={"run_id":RUN_ID,"status":"completed"})
    with connect(fake) as c:
        assert c.post("/api/runs",headers=AUTH,json={"input":"build"}).json()["run_id"] == RUN_ID
        assert c.get("/api/runs/"+RUN_ID,headers=AUTH).json()["status"] == "completed"

def test_invalid_id_not_forwarded():
    def unexpected(req):
        raise AssertionError("invalid id was forwarded")
    with connect(unexpected) as c:
        assert c.get("/api/runs/incorrect",headers=AUTH).status_code == 422

def test_backend_invalid_id_rejected():
    with connect(lambda req: httpx.Response(202,json={"run_id":"bad"})) as c:
        assert c.post("/api/runs",headers=AUTH,json={"input":"task"}).status_code == 502

def test_stop():
    def stop(req):
        assert req.url.path.endswith("/stop")
        return httpx.Response(200,json={"status":"stopping"})
    with connect(stop) as c:
        assert c.post("/api/runs/"+RUN_ID+"/stop",headers=AUTH).json()["status"]=="stopping"

def test_no_secret_leak_from_remote_errors():
    with connect(lambda req: httpx.Response(500,json={"error":"sensitive backend data"})) as c:
        r=c.post("/api/runs",headers=AUTH,json={"input":"task"})
        assert r.status_code==500 and "sensitive backend data" not in r.text

def test_weak_tokens_fail():
    with pytest.raises(RuntimeError):
        build_app(app_token="short",hermes_key=HERMES_KEY)
    with pytest.raises(RuntimeError):
        build_app(app_token=APP_TOKEN,hermes_key="short")

def test_http_remote_rejected():
    with pytest.raises(RuntimeError):
        build_app(app_token=APP_TOKEN,hermes_key=HERMES_KEY,hermes_url="http://example.com:8642")

def test_sse_data():
    with connect(lambda req: httpx.Response(200,content=b'data: {"event":"message.delta"}\n\n')) as c:
        assert "message.delta" in c.get("/api/runs/"+RUN_ID+"/events",headers=AUTH).text
