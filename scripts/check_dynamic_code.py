from pathlib import Path
import re,sys
ROOT=Path(__file__).resolve().parents[1]

def android_main_roots():
 """T7-P2-3：从 android/settings.gradle.kts 自动发现全部 module 的 src/main（java/kotlin）。
 单 module 时代假设（只扫 android/app）已删除——仓库已是多 module（:core:* / :feature:*）。"""
 settings=ROOT/"android"/"settings.gradle.kts"
 modules=re.findall(r'include\("?([\w:]+)"?\)',settings.read_text(encoding="utf-8"))
 roots=[]
 for m in sorted({n.lstrip(":") for n in modules}):
  base=ROOT/"android"/Path(*m.split(":"))/"src"/"main"
  roots.extend(d for d in (base/"java",base/"kotlin") if d.is_dir())
 return roots

# (?<!\.) 排除成员调用（.eval(/.exec(）：AGSL 着色器字符串内的 iVectorMask.eval(fragCoord)
# 是 RuntimeShader 求值入口（shader 语言），非宿主（Kotlin/Python）动态代码执行。
patterns=[re.compile(r"(?<!\.)\beval\s*\("),re.compile(r"(?<!\.)\bexec\s*\("),re.compile(r"subprocess\.(run|Popen|call)"),re.compile(r"Runtime\.getRuntime\(\)\.exec")]
bases=[ROOT/"backend/app"]+android_main_roots()
fail=[]
scanned=0
for base in bases:
 for path in base.rglob("*"):
  if not path.is_file() or path.suffix not in {".py",".kt"}:continue
  scanned+=1
  text=path.read_text(encoding="utf-8",errors="ignore")
  for p in patterns:
   if p.search(text):fail.append(f"{path.relative_to(ROOT)}:{p.pattern}")
if fail:
 print("\n".join(fail),file=sys.stderr);raise SystemExit(2)
print(f"no dynamic code execution in production paths ({scanned} files across {len(bases)} roots: backend/app + all {len(bases)-1} android modules src/main)")
