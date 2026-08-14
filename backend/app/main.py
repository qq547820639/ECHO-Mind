from contextlib import asynccontextmanager
import time
from pathlib import Path
from uuid import uuid4

from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy import text

from app.api.routes import router
from app.config import APP_VERSION, get_settings
from app.database import Base, SessionLocal, engine
from app.request_context import current_request_id
from app.services import immutability  # noqa: F401  registers the append-only ORM guard
from app.services.telemetry import log_request
from collections.abc import AsyncIterator
from starlette.middleware.base import RequestResponseEndpoint

BASE_DIR = Path(__file__).resolve().parent
TEMPLATES = Jinja2Templates(directory=str(BASE_DIR / "templates"))
settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    # Local/demo convenience. Pilot/production deployment must use Alembic migrations instead.
    if settings.environment == "local":
        Base.metadata.create_all(bind=engine)
    yield


app = FastAPI(
    title="ECHO Mind Portrait Core API",
    version=APP_VERSION,
    description="ECHO-Mind 每日个人画像：被动行为节律 → 个人基线 → 每日画像（Me vs Me）。"
                "行为观察，不做心理诊断；非诊断、非紧急服务替代。人工支持与画像链相互独立。",
    lifespan=lifespan,
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-Request-ID", "X-Bootstrap-Key"],
)
app.include_router(router)


@app.middleware("http")
async def request_context_and_security_headers(request: Request, call_next: RequestResponseEndpoint) -> Response:
    request_id = request.headers.get("x-request-id") or f"req_{uuid4().hex}"
    request.state.request_id = request_id
    token = current_request_id.set(request_id)
    started = time.perf_counter()
    try:
        response = await call_next(request)
    finally:
        current_request_id.reset(token)
    response.headers["X-Request-ID"] = request_id
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Cache-Control"] = "no-store"
    response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
    # v0.6.1 privacy-safe operational telemetry（P2-16）：
    # 只记录 method/path/status/duration_ms/request_id，不记录任何 body、
    # 传感数据、token、journal/free text 或 consent 明文内容。
    duration_ms = int((time.perf_counter() - started) * 1000)
    log_request(
        request_id=request_id,
        method=request.method,
        path=request.url.path,
        status=response.status_code,
        duration_ms=duration_ms,
    )
    return response


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "service": "echo-mind-path-a", "version": APP_VERSION, "environment": settings.environment}


@app.get("/ready")
def ready(response: Response) -> dict:
    try:
        with SessionLocal() as db:
            db.execute(text("SELECT 1"))
        return {"status": "ready", "database": "ok"}
    except Exception as exc:
        response.status_code = 503
        return {"status": "not_ready", "database": "error", "detail": type(exc).__name__}


@app.get("/console", response_class=HTMLResponse)
def console(request: Request) -> Response:
    return TEMPLATES.TemplateResponse(request=request, name="console.html", context={"version": APP_VERSION})
