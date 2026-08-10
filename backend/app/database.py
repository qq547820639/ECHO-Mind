from collections.abc import Generator
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker
from app.config import get_settings


class Base(DeclarativeBase):
    pass


settings = get_settings()
# SQLite：check_same_thread=False 支持多线程会话；timeout=30 提供 busy-wait（并发写不立即报 locked）
connect_args = (
    {"check_same_thread": False, "timeout": 30} if settings.database_url.startswith("sqlite") else {}
)
engine_kwargs = {"connect_args": connect_args, "pool_pre_ping": True}
if settings.database_url in {"sqlite:///:memory:", "sqlite+pysqlite:///:memory:"}:
    engine_kwargs["poolclass"] = StaticPool
engine = create_engine(settings.database_url, **engine_kwargs)
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)


def get_db() -> Generator[Session, None, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
