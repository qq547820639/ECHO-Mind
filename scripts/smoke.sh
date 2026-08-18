#!/usr/bin/env bash
# T7-P2-9：curl -f（HTTP 4xx/5xx 即败，不再静默"通过"）+ python3 回退 + 端口探测提示。
set -euo pipefail
cd "$(dirname "$0")/../backend"
PY=python3
command -v python >/dev/null 2>&1 && PY=python
if ! curl -sf -o /dev/null http://127.0.0.1:8000/health; then
  echo "FAIL：127.0.0.1:8000 未就绪——先启动 backend（如 uvicorn app.main:app --port 8000）" >&2
  exit 1
fi
"$PY" scripts/seed_demo.py
TOKEN=$("$PY" scripts/create_demo_tokens.py | awk '$1=="user"{print $2}')
curl -sf http://127.0.0.1:8000/health
curl -sf -X POST http://127.0.0.1:8000/v1/checkins \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"event_id":"evt_smoke_0001","user_id":"u_demo","mood":3,"stress":3,"energy":3,"sleep_recovery":3,"client_time":"2026-07-29T10:00:00+08:00","device_timezone":"Asia/Shanghai"}'
