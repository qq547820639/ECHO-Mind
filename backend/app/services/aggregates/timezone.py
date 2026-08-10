"""用户本地日窗口换算（Milestone B）。

日界线必须使用用户 local timezone（User.timezone，默认 Asia/Shanghai），
禁止用 UTC 日期作为"一天"（否则跨日线用户的聚合会错位）。
"""
from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo


def local_day_window(tz_name: str, local_date: date) -> tuple[datetime, datetime]:
    """返回本地日 [local_date 00:00, 次日 00:00) 对应的 (utc_start, utc_end)。

    DST 处理：datetime.combine 使用 fold=0（fall-back 日取第一次出现），
    经 astimezone 换算到 UTC；返回的 utc_end 在 spring-forward 日为 23 小时后、
    fall-back 日为 25 小时后——由 astimezone 自动处理，无需手工补偿。
    """
    tz = ZoneInfo(tz_name)
    start_local = datetime.combine(local_date, time.min, tzinfo=tz)
    end_local = start_local + timedelta(days=1)
    utc_start = start_local.astimezone(timezone.utc)
    utc_end = end_local.astimezone(timezone.utc)
    return utc_start, utc_end
