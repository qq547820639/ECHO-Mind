from pathlib import Path
import re,sys
ROOT=Path(__file__).resolve().parents[1]
patterns=[re.compile(r"\beval\s*\("),re.compile(r"\bexec\s*\("),re.compile(r"subprocess\.(run|Popen|call)"),re.compile(r"Runtime\.getRuntime\(\)\.exec")]
fail=[]
for base in [ROOT/"backend/app",ROOT/"android/app/src/main"]:
 for path in base.rglob("*"):
  if not path.is_file() or path.suffix not in {".py",".kt"}:continue
  text=path.read_text(encoding="utf-8",errors="ignore")
  for p in patterns:
   if p.search(text):fail.append(f"{path.relative_to(ROOT)}:{p.pattern}")
if fail:
 print("\n".join(fail),file=sys.stderr);raise SystemExit(2)
print("no dynamic code execution in production paths")
