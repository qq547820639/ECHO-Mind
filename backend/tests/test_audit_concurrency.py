"""T04 审计 tenant 串行化并发测试（v0.6）。

断言：
- 50 个线程并发 append_audit（每线程独立 DB session/连接，文件型 SQLite）
- 使用 tenant_append_serialized 上下文（等价 PostgreSQL advisory xact lock 语义，
  锁覆盖 append+commit）
- verify_audit_chain：valid=True、恰好一条线性链（无 previous_hash 分叉）、
  events 数量正确
"""
import threading

from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from app.database import Base
from app.models import AuditEvent
from app.services.audit import append_audit, tenant_append_serialized, verify_audit_chain

THREAD_COUNT = 50


def test_concurrent_append_audit_forms_single_linear_chain(tmp_path):
    """并发 append 不产生 previous_hash 分叉，链线性且可验证。"""
    db_file = tmp_path / "audit_concurrency.db"
    engine = create_engine(f"sqlite:///{db_file}")
    Base.metadata.create_all(bind=engine)

    errors: list[BaseException] = []

    def worker(index: int) -> None:
        # 每线程独立 engine + session（文件型 SQLite，多连接）
        engine = create_engine(f"sqlite:///{db_file}", connect_args={"timeout": 30})
        session_factory = sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)
        try:
            with session_factory() as db, tenant_append_serialized("t_conc"):
                append_audit(
                    db,
                    tenant_id="t_conc",
                    actor_type="user",
                    actor_id=f"u_{index}",
                    action="conc.append",
                    object_type="test",
                    object_id=f"obj_{index}",
                    metadata={"index": index},
                )
                db.commit()
        except BaseException as exc:  # noqa: BLE001 记录线程内异常供主线程断言
            errors.append(exc)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(THREAD_COUNT)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert not errors, f"worker 线程异常: {errors}"

    engine = create_engine(f"sqlite:///{db_file}")
    session_factory = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    with session_factory() as db:
        result = verify_audit_chain(db, "t_conc")
        assert result["valid"] is True, result["failures"]
        assert result["events"] == THREAD_COUNT
        assert result["head_hash"]

        rows = db.scalars(
            select(AuditEvent)
            .where(AuditEvent.tenant_id == "t_conc")
            .order_by(AuditEvent.occurred_at.asc(), AuditEvent.id.asc())
        ).all()
        # 恰好一条线性链：每行 previous_event_hash == 前一行 event_hash
        previous = None
        for row in rows:
            assert row.previous_event_hash == previous, (
                f"链分叉或乱序: event={row.event_id}, prev={row.previous_event_hash}, expected={previous}"
            )
            previous = row.event_hash
        # 无重复 previous_event_hash（不存在两个事件共享同一前驱）
        previous_hashes = [row.previous_event_hash for row in rows]
        non_null_previous = [h for h in previous_hashes if h is not None]
        assert len(non_null_previous) == len(set(non_null_previous)), "存在 previous_hash 分叉"
        assert len(rows) == THREAD_COUNT
