from collections import defaultdict
from datetime import datetime, timedelta, timezone
from statistics import median
from sqlalchemy import select
from sqlalchemy.orm import Session
from app.models import Checkin


def _mad(values: list[float]) -> float:
    if not values:
        return 0.0
    center = median(values)
    return median([abs(x - center) for x in values])


def build_trend(db: Session, tenant_id: str, user_id: str, days: int) -> dict:
    since = datetime.now(timezone.utc) - timedelta(days=days)
    rows = db.scalars(
        select(Checkin).where(
            Checkin.tenant_id == tenant_id,
            Checkin.user_id == user_id,
            Checkin.client_time >= since,
        ).order_by(Checkin.client_time)
    ).all()
    grouped: dict[str, list[Checkin]] = defaultdict(list)
    for row in rows:
        grouped[row.client_time.date().isoformat()].append(row)
    points = []
    for date, items in sorted(grouped.items()):
        count = len(items)
        points.append({
            "date": date,
            "mood": round(sum(x.mood for x in items) / count, 2),
            "stress": round(sum(x.stress for x in items) / count, 2),
            "energy": round(sum(x.energy for x in items) / count, 2),
            "sleep_recovery": round(sum(x.sleep_recovery for x in items) / count, 2),
        })
    baselines = {}
    for key in ("mood", "stress", "energy", "sleep_recovery"):
        values = [float(p[key]) for p in points]
        baselines[key] = {"median": median(values) if values else None, "mad": _mad(values) if values else None}
    return {
        "user_id": user_id,
        "window_days": days,
        "data_days": len(grouped),
        "coverage": round(len(grouped) / days, 3),
        "baseline_ready": len(grouped) >= 7,
        "baselines": baselines,
        "points": points,
        "neutral_message": "这些变化只用于个人趋势回顾，不构成诊断或治疗建议。",
    }
