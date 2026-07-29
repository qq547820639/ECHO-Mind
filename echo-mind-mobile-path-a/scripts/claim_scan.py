import re, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
TARGETS=[ROOT/"android/app/src/main",ROOT/"content-packs"]
patterns={
 "diagnosis_claim":re.compile(r"(已|被|为你|可以).{0,5}(确诊|诊断为)"),
 "treatment_claim":re.compile(r"(自动|个性化|为你生成).{0,6}治疗方案"),
 "guarantee":re.compile(r"保证.{0,4}(安全|治愈|疗效|康复)"),
 "doctor_replacement":re.compile(r"(AI医生|AI治疗师|替代.{0,4}(医生|专业人员))"),
}
fail=[]
for base in TARGETS:
 for path in base.rglob("*"):
  if not path.is_file() or path.suffix.lower() not in {".kt",".xml",".json"}: continue
  text=path.read_text(encoding="utf-8",errors="ignore")
  for name,pattern in patterns.items():
   for m in pattern.finditer(text): fail.append(f"{path.relative_to(ROOT)}:{name}:{m.group(0)}")
if fail:
 print("\n".join(fail),file=sys.stderr);raise SystemExit(2)
print("claim scan passed")
