#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../backend"
python scripts/seed_demo.py
TOKEN=$(python scripts/create_demo_tokens.py | awk '$1=="user"{print $2}')
curl -s http://127.0.0.1:8000/health
curl -s -X POST http://127.0.0.1:8000/v1/checkins \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"event_id":"evt_smoke_0001","user_id":"u_demo","mood":3,"stress":3,"energy":3,"sleep_recovery":3,"client_time":"2026-07-29T10:00:00+08:00","device_timezone":"Asia/Shanghai"}'
