import hashlib, json, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKS = ROOT / "content-packs"
errors=[]
records=[]
for path in sorted(PACKS.rglob("*.json")):
    if path.name == "MANIFEST.generated.json":
        continue
    try:
        data=json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        errors.append(f"{path}: invalid json: {exc}")
        continue
    raw=path.read_bytes()
    records.append({"path":str(path.relative_to(ROOT)),"sha256":hashlib.sha256(raw).hexdigest(),"status":data.get("status","versioned_static")})
    text=json.dumps(data,ensure_ascii=False)
    for phrase in ["保证治愈","保证疗效","替代医生","AI医生","自动诊断","确诊为"]:
        if phrase in text:
            errors.append(f"{path}: prohibited claim {phrase}")
    if "version" not in data:
        errors.append(f"{path}: missing version")
manifest={"schema_version":"content-manifest.v1","records":records}
(ROOT/"content-packs"/"MANIFEST.generated.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
if errors:
    print("\n".join(errors),file=sys.stderr); raise SystemExit(2)
print(f"validated {len(records)} content packs")
