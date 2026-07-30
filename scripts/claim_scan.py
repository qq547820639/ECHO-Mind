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
# 商店文案与合规材料（pilot-pack Markdown 草案）：禁用宣称逐行扫描。
# 含否定语义的行（如“不替代医生”“不是诊断工具”）为合规免责声明，不计命中。
DOC_BANNED=["诊断","确诊","治疗","治愈","疗效保证","处方","AI医生","AI 医生","替代医生","自动治疗方案"]
negation=re.compile(r"不|非|无|绝不|不得|严禁|禁止|避免|阻断")
fail=[]
for base in TARGETS:
 for path in base.rglob("*"):
  if not path.is_file() or path.suffix.lower() not in {".kt",".xml",".json"}: continue
  text=path.read_text(encoding="utf-8",errors="ignore")
  for name,pattern in patterns.items():
   for m in pattern.finditer(text): fail.append(f"{path.relative_to(ROOT)}:{name}:{m.group(0)}")
for path in (ROOT/"pilot-pack").rglob("*.md"):
 for lineno,line in enumerate(path.read_text(encoding="utf-8",errors="ignore").splitlines(),1):
  if negation.search(line): continue
  for phrase in DOC_BANNED:
   if phrase in line: fail.append(f"{path.relative_to(ROOT)}:{lineno}:doc_claim:{phrase}")
if fail:
 print("\n".join(fail),file=sys.stderr);raise SystemExit(2)
print("claim scan passed")
