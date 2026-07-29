from contextlib import asynccontextmanager
from pathlib import Path
from uuid import uuid4

from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy import text

from app.api.routes import router
from app.config import get_settings
from app.database import Base, SessionLocal, engine

BASE_DIR = Path(__file__).resolve().parent
TEMPLATES = Jinja2Templates(directory=str(BASE_DIR / "templates"))
settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Local/demo convenience. Pilot/production deployment must use Alembic migrations instead.
    if settings.environment == "local":
        Base.metadata.create_all(bind=engine)
    yield


app = FastAPI(
    title="ECHO Mind Path A API",
    version="0.2.0",
    description="心理健康记录、筛查提示、审核练习、人工接管与审计。非诊断、非紧急服务替代。",
    lifespan=lifespan,
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_credentials=True,
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-Request-ID", "X-Bootstrap-Key"],
)
app.include_router(router)


@app.middleware("http")
async def request_context_and_security_headers(request: Request, call_next):
    request_id = request.headers.get("x-request-id") or f"req_{uuid4().hex}"
    response = await call_next(request)
    response.headers["X-Request-ID"] = request_id
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Cache-Control"] = "no-store"
    response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
    return response


@app.get("/health")
def health():
    return {"status": "ok", "service": "echo-mind-path-a", "version": "0.2.0", "environment": settings.environment}


@app.get("/ready")
def ready(response: Response):
    try:
        with SessionLocal() as db:
            db.execute(text("SELECT 1"))
        return {"status": "ready", "database": "ok"}
    except Exception as exc:
        response.status_code = 503
        return {"status": "not_ready", "database": "error", "detail": type(exc).__name__}


@app.get("/console", response_class=HTMLResponse)
def console(request: Request):
    return TEMPLATES.TemplateResponse(request=request, name="console.html", context={"version": "0.2.0"})
