"""Single-operator HTTPS bridge for EVE Android -> Hermes runs API.

This is not a model service. It requires a separately running Hermes gateway.
"""
import hmac
import os
import re
from contextlib import asynccontextmanager
from typing import Any

import httpx
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

RUN_ID = re.compile(r"^run_[0-9a-f]{32}$")


class NewRun(BaseModel):
    input: str = Field(min_length=1, max_length=100000)
    instructions: str | None = Field(default=None, max_length=20000)
    session_id: str | None = Field(default=None, max_length=256)


def build_app(*, hermes_url: str | None = None, app_token: str | None = None,
              hermes_key: str | None = None, client: httpx.AsyncClient | None = None) -> FastAPI:
    base = (hermes_url or os.getenv("HERMES_BASE_URL", "http://127.0.0.1:8642")).rstrip("/")
    token = app_token if app_token is not None else os.getenv("AGENT_APP_TOKEN", "")
    key = hermes_key if hermes_key is not None else os.getenv("HERMES_API_KEY", "")
    if len(token) < 24 or len(key) < 24:
        raise RuntimeError("Both AGENT_APP_TOKEN and HERMES_API_KEY must contain at least 24 characters")
    if not base.startswith(("https://", "http://127.0.0.1:", "http://localhost:")):
        raise RuntimeError("HERMES_BASE_URL must be HTTPS or loopback")
    managed_client = client is None

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.upstream = client or httpx.AsyncClient(timeout=httpx.Timeout(30, read=None))
        try:
            yield
        finally:
            if managed_client:
                await app.state.upstream.aclose()

    app = FastAPI(title="Manus Hybrid Hermes Bridge", lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)

    def auth(request: Request) -> None:
        header = request.headers.get("authorization", "")
        candidate = header[7:] if header.startswith("Bearer ") else ""
        if not hmac.compare_digest(candidate, token):
            raise HTTPException(status_code=401, detail="Unauthorized")

    def backend_headers() -> dict[str, str]:
        return {"Authorization": f"Bearer {key}", "Content-Type": "application/json"}

    def upstream_path(run_id: str, suffix: str = "") -> str:
        if not RUN_ID.fullmatch(run_id):
            raise HTTPException(status_code=422, detail="Invalid run ID")
        return f"{base}/v1/runs/{run_id}{suffix}"

    async def call(request: Request, method: str, url: str, **kwargs: Any):
        try:
            response = await request.app.state.upstream.request(
                method, url, headers=backend_headers(), **kwargs)
        except httpx.RequestError:
            raise HTTPException(status_code=502, detail="Agent backend unreachable")
        try:
            body = response.json()
        except ValueError:
            raise HTTPException(status_code=502, detail="Invalid backend JSON")
        if response.status_code >= 400:
            raise HTTPException(status_code=response.status_code, detail="Agent backend rejected request")
        return body

    @app.get("/health")
    async def health():
        return {"status": "up", "service": "hermes-bridge"}

    @app.get("/ready", dependencies=[Depends(auth)])
    async def ready(request: Request):
        await call(request, "GET", f"{base}/health")
        return {"service": "hermes-bridge", "backend": "reachable"}

    @app.post("/api/runs", dependencies=[Depends(auth)], status_code=202)
    async def start(body: NewRun, request: Request):
        response = await call(request, "POST", f"{base}/v1/runs",
                              json=body.model_dump(exclude_none=True))
        if not isinstance(response, dict) or not RUN_ID.fullmatch(str(response.get("run_id", ""))):
            raise HTTPException(status_code=502, detail="Invalid backend run ID")
        return response

    @app.get("/api/runs/{run_id}", dependencies=[Depends(auth)])
    async def status(run_id: str, request: Request):
        return await call(request, "GET", upstream_path(run_id))

    @app.post("/api/runs/{run_id}/stop", dependencies=[Depends(auth)])
    async def stop(run_id: str, request: Request):
        return await call(request, "POST", upstream_path(run_id, "/stop"))

    @app.get("/api/runs/{run_id}/events", dependencies=[Depends(auth)])
    async def events(run_id: str, request: Request):
        url = upstream_path(run_id, "/events")
        async def stream():
            try:
                async with request.app.state.upstream.stream(
                        "GET", url, headers=backend_headers()) as response:
                    if response.status_code != 200:
                        yield 'event: bridge.error\ndata: {"error":"upstream_rejected"}\n\n'
                        return
                    async for line in response.aiter_lines():
                        if await request.is_disconnected():
                            break
                        yield line + "\n"
            except httpx.RequestError:
                yield 'event: bridge.error\ndata: {"error":"upstream_unavailable"}\n\n'
        return StreamingResponse(stream(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache",
                                          "X-Accel-Buffering": "no",
                                          "X-Content-Type-Options": "nosniff"})

    return app


# Fail closed: no valid credential set -> uvicorn cannot serve an exposed API.
app = build_app() if os.getenv("AGENT_APP_TOKEN") else None
